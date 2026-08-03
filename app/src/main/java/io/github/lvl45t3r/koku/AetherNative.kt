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
    private val mutableLogs = MutableStateFlow<List<String>>(emptyList())
    private val mutableTunnelState = MutableStateFlow(TunnelState.IDLE)
    private val mutableTraffic = MutableStateFlow(TrafficSnapshot())
    private val mutableTestState = MutableStateFlow(ConnectionTestState.IDLE)
    private val mutableDiagnosticsEnabled = MutableStateFlow(false)
    private val logSink = NativeLogSink(::appendLog)
    private var testBaseline = TrafficSnapshot()
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
        configDir: String,
        tun: ParcelFileDescriptor,
        onWorkingProfile: (String) -> Unit,
    ): Long {
        check(loaded) { "Native Aether library is unavailable." }
        val configJson = JSONObject()
            .put("protocol", protocol)
            .put("scanMode", scanMode)
            .put("ipMode", "v4")
            .put("noizeProfile", noizeProfile)
            .put("configDir", configDir)
            .toString()
        workingProfileReceiver = onWorkingProfile
        appendLog(
            "INFO",
            "Starting native engine: protocol=$protocol, scan=$scanMode, profile=$noizeProfile",
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
        } else if (level.equals("ERROR", ignoreCase = true) && tunnelIsActive()) {
            mutableTunnelState.value = TunnelState.FAILED
        }

        workingProfilePattern.find(message)?.groupValues?.getOrNull(1)?.let { profile ->
            workingProfileReceiver?.invoke(profile)
        }

        trafficPattern.find(message)?.destructured?.let {
            (outPackets, outBytes, inPackets, inBytes) ->
            val snapshot = TrafficSnapshot(
                outboundPackets = outPackets.toLong(),
                outboundBytes = outBytes.toLong(),
                inboundPackets = inPackets.toLong(),
                inboundBytes = inBytes.toLong(),
            )
            mutableTraffic.value = snapshot
            if (
                mutableTestState.value == ConnectionTestState.RUNNING &&
                snapshot.outboundPackets > testBaseline.outboundPackets &&
                snapshot.inboundPackets > testBaseline.inboundPackets
            ) {
                mutableTestState.value = ConnectionTestState.PASSED
                if (mutableDiagnosticsEnabled.value) {
                    val successLine =
                        "$time INFO  Google test passed: bidirectional tunnel traffic confirmed"
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
