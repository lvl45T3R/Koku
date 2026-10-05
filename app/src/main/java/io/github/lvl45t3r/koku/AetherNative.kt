package io.github.lvl45t3r.koku

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AetherNative {
    private const val MAX_LOG_LINES = 300
    private val trafficPattern = Regex(
        """traffic: TUN -> tunnel (\d+) packets / (\d+) bytes; tunnel -> TUN (\d+) packets / (\d+) bytes"""
    )
    private val workingProfilePattern = Regex("""working noize profile: ([a-z]+)""")
    private val tcpPayloadPattern = Regex("""TCP payload: out (\d+) / in (\d+) bytes""")
    private val exitRoutePattern = Regex(
        """exit route verified: ip=([^,]+), country=([A-Za-z]{2}), colo=([^,]*), latency=(\d+)ms""",
    )
    private val mutableLogs = MutableStateFlow<List<String>>(emptyList())
    private val mutableTunnelState = MutableStateFlow(TunnelState.IDLE)
    private val mutableTraffic = MutableStateFlow(TrafficSnapshot())
    private val mutableTestState = MutableStateFlow(ConnectionTestState.IDLE)
    private val mutableDiagnosticsEnabled = MutableStateFlow(false)
    private val logSink = NativeLogSink(::appendLog)
    private var testBaseline = TrafficSnapshot()
    @Volatile private var workerFinishedReceiver: (() -> Unit)? = null
    private var finalCarrier = false
    private val coreExitPattern = Regex("""exit: ([^,]+), (?:([A-Za-z]{2})(?: via ([^,]+))?, )?(\d+)ms""")
    private val coreProfilePattern = Regex("""selected WireGuard endpoint .* using aethernoize profile '([a-z]+)'""" )
    @Volatile private var workingProfileReceiver: ((String) -> Unit)? = null

    val logs: StateFlow<List<String>> = mutableLogs.asStateFlow()
    val tunnelState: StateFlow<TunnelState> = mutableTunnelState.asStateFlow()
    val traffic: StateFlow<TrafficSnapshot> = mutableTraffic.asStateFlow()
    val testState: StateFlow<ConnectionTestState> = mutableTestState.asStateFlow()
    val diagnosticsEnabled: StateFlow<Boolean> = mutableDiagnosticsEnabled.asStateFlow()

    private val loaded = runCatching {
        System.loadLibrary("aether_android")
    }.onFailure {
        appendLog("ERROR", "Failed to load native engine: ${it.message}")
    }.isSuccess

    fun isAvailable(): Boolean = loaded

    fun markStarting() {
        mutableTunnelState.value = TunnelState.STARTING
        mutableTraffic.value = TrafficSnapshot()
        mutableTestState.value = ConnectionTestState.IDLE
        appendLog("INFO", "VPN start requested")
    }

    fun markStopping() {
        if (mutableTunnelState.value != TunnelState.IDLE) {
            mutableTunnelState.value = TunnelState.STOPPING
        }
    }

    fun markStopped() {
        if (loaded && nativeIsRunning()) { mutableTunnelState.value = TunnelState.STOPPING; return }
        mutableTunnelState.value = TunnelState.IDLE
        mutableTestState.value = ConnectionTestState.IDLE
    }

    fun beginConnectionTest() {
        testBaseline = mutableTraffic.value
        mutableTestState.value = ConnectionTestState.RUNNING
        appendLog(
            "INFO",
            "Google test started in an external browser; waiting for tunneled request and response packets",
        )
    }

    fun markTestLaunchFailed(message: String) {
        mutableTestState.value = ConnectionTestState.FAILED
        appendLog("ERROR", message)
    }

    fun markTestTimedOut() {
        if (mutableTestState.value == ConnectionTestState.RUNNING) {
            mutableTestState.value = ConnectionTestState.FAILED
            appendLog(
                "ERROR",
                "Google test timed out: no bidirectional tunneled traffic was confirmed",
            )
        }
    }

    fun logText(): String = buildString {
        appendLine("Koku diagnostic log")
        appendLine("State: ${mutableTunnelState.value}")
        appendLine(
            "Traffic: outbound=${mutableTraffic.value.outboundPackets} packets/" +
                "${mutableTraffic.value.outboundBytes} bytes, " +
                "inbound=${mutableTraffic.value.inboundPackets} packets/" +
                "${mutableTraffic.value.inboundBytes} bytes",
        )
        appendLine()
        append(mutableLogs.value.joinToString("\n"))
    }

    fun start(
        protocol: String,
        scanMode: String,
        noizeProfile: String,
        noIrExit: Boolean,
        configDir: String,
        tun: ParcelFileDescriptor,
        onWorkingProfile: (String) -> Unit,
        options: JSONObject = JSONObject(),
        psiphonBin: String = "",
        onWorkerFinished: () -> Unit = {},
    ): Long {
        check(loaded) { "Native Aether library is unavailable." }
        val configJson = JSONObject()
            .put("protocol", protocol)
            .put("scanMode", scanMode)
            .put("ipMode", "v4")
            .put("noizeProfile", noizeProfile)
            .put("noIrExit", noIrExit)
            .put("configDir", configDir)
            .put("options", options)
            .put("psiphonBin", psiphonBin)
            .toString()
        workingProfileReceiver = onWorkingProfile
        workerFinishedReceiver = onWorkerFinished
        finalCarrier = options.optString("AETHER_TOR", "off") != "off" || options.optString("AETHER_PSIPHON", "off") != "off"
        appendLog(
            "INFO",
            "Starting native engine: protocol=$protocol, scan=$scanMode, " +
                "profile=$noizeProfile, noIrExit=$noIrExit",
        )
        return nativeStart(configJson, tun.detachFd(), logSink)
    }

    fun stop(handle: Long) {
        if (loaded && handle != 0L) {
            appendLog("INFO", "Stopping native engine")
            nativeStop(handle)
        }
        workingProfileReceiver = null
    }

    fun log(level: String, message: String) {
        appendLog(level, message)
    }

    fun clearLogs() {
        mutableLogs.value = emptyList()
    }

    @Synchronized
    fun startDiagnostics() {
        mutableLogs.value = emptyList()
        mutableDiagnosticsEnabled.value = true
        storeLine("INFO", "Diagnostic logging started")
    }

    @Synchronized
    fun stopDiagnostics() {
        if (mutableDiagnosticsEnabled.value) {
            storeLine("INFO", "Diagnostic logging stopped")
            mutableDiagnosticsEnabled.value = false
        }
    }

    @Synchronized
    private fun appendLog(level: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        if (mutableDiagnosticsEnabled.value) {
            val line = "$time ${level.uppercase(Locale.US)}  $message"
            mutableLogs.value = (mutableLogs.value + line).takeLast(MAX_LOG_LINES)
        }

        if ("starting Android TUN packet bridge" in message) {
            mutableTunnelState.value = TunnelState.CONNECTED
        } else if (("reconnecting" in message || "rescanning" in message) && tunnelIsActive()) {
            mutableTunnelState.value = TunnelState.STARTING
        } else if (level.equals("ERROR", ignoreCase = true) && ("Aether engine stopped" in message || "NO-IR Exit:" in message) && tunnelIsActive()) {
            mutableTunnelState.value = TunnelState.FAILED
        }

        if ("native worker finished" in message) {
            if (mutableTunnelState.value == TunnelState.STOPPING) markStopped()
            workerFinishedReceiver?.invoke(); workerFinishedReceiver = null
        }
        val isFinalExit = !finalCarrier || message.contains("tor", true) || message.contains("psiphon", true) || message.contains("Android final proxy")
        if (isFinalExit) coreExitPattern.find(message)?.groupValues?.let { values ->
            ConnectionProbe.acceptNativeExit(values[1], values[2].ifBlank { "ZZ" }, values[4].toLongOrNull() ?: 0L)
        }
        coreProfilePattern.find(message)?.groupValues?.getOrNull(1)?.let { workingProfileReceiver?.invoke(it) }
        workingProfilePattern.find(message)?.groupValues?.getOrNull(1)?.let { profile ->
            workingProfileReceiver?.invoke(profile)
        }

        exitRoutePattern.find(message)?.groupValues?.let { values ->
            ConnectionProbe.acceptNativeExit(
                ip = values[1],
                countryCode = values[2],
                latencyMs = values[4].toLongOrNull() ?: 0L,
            )
        }
        if (level.equals("ERROR", ignoreCase = true) && "NO-IR Exit" in message) {
            ConnectionProbe.failNativeVerification(message.substringAfter(": ", message))
        }
        if ("Final proxy exit verification unavailable" in message) {
            ConnectionProbe.failNativeVerification("Final proxy exit verification unavailable")
        }

        trafficPattern.find(message)?.destructured?.let {
            (outPackets, outBytes, inPackets, inBytes) ->
            val payload = tcpPayloadPattern.find(message)?.groupValues
            val snapshot = TrafficSnapshot(
                outboundPackets = outPackets.toLong(),
                outboundBytes = outBytes.toLong(),
                inboundPackets = inPackets.toLong(),
                inboundBytes = inBytes.toLong(),
                outboundTcpPayload = payload?.get(1)?.toLongOrNull() ?: 0L,
                inboundTcpPayload = payload?.get(2)?.toLongOrNull() ?: 0L,
            )
            val previous = mutableTraffic.value
            mutableTraffic.value = snapshot
            if (mutableTunnelState.value == TunnelState.STARTING && snapshot.inboundTcpPayload > previous.inboundTcpPayload) {
                mutableTunnelState.value = TunnelState.CONNECTED
            }
            if (
                mutableTestState.value == ConnectionTestState.RUNNING &&
                snapshot.outboundTcpPayload > testBaseline.outboundTcpPayload &&
                snapshot.inboundTcpPayload > testBaseline.inboundTcpPayload
            ) {
                mutableTestState.value = ConnectionTestState.PASSED
                if (mutableDiagnosticsEnabled.value) {
                    val successLine =
                        "$time INFO  Google test passed: bidirectional TCP payload confirmed"
                    mutableLogs.value = (mutableLogs.value + successLine)
                        .takeLast(MAX_LOG_LINES)
                }
            }
        }
    }

    private fun storeLine(level: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$time ${level.uppercase(Locale.US)}  $message"
        mutableLogs.value = (mutableLogs.value + line).takeLast(MAX_LOG_LINES)
    }

    private fun tunnelIsActive(): Boolean {
        val state = mutableTunnelState.value
        return state != TunnelState.IDLE && state != TunnelState.STOPPING
    }

    fun teamCall(action: String, payload: JSONObject): JSONObject {
        check(loaded) { "Native engine unavailable" }
        return JSONObject(nativeTeam(action, payload.toString()))
    }
    private external fun nativeIsRunning(): Boolean
    private external fun nativeTeam(action: String, payload: String): String
    @JvmStatic
    private external fun nativeStart(
        configJson: String,
        tunFd: Int,
        logSink: NativeLogSink,
    ): Long

    @JvmStatic
    private external fun nativeStop(handle: Long)
}

enum class TunnelState {
    IDLE,
    STARTING,
    CONNECTED,
    STOPPING,
    FAILED,
}

enum class ConnectionTestState {
    IDLE,
    RUNNING,
    PASSED,
    FAILED,
}

data class TrafficSnapshot(
    val outboundTcpPayload: Long = 0,
    val inboundTcpPayload: Long = 0,
    val outboundPackets: Long = 0,
    val outboundBytes: Long = 0,
    val inboundPackets: Long = 0,
    val inboundBytes: Long = 0,
)

@Keep
class NativeLogSink(
    private val receiver: (String, String) -> Unit,
) {
    @Keep
    fun onNativeLog(level: String, message: String) {
        receiver(level, message)
    }
}
