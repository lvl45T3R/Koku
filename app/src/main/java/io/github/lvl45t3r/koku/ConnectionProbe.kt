package io.github.lvl45t3r.koku

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.util.Locale

data class ExitNodeInfo(
    val ip: String,
    val country: String,
    val countryCode: String,
    val latencyMs: Long,
)

sealed interface ProbeState {
    data object Idle : ProbeState
    data object Loading : ProbeState
    data class Ready(val info: ExitNodeInfo) : ProbeState
    data class Failed(val message: String) : ProbeState
}

object ConnectionProbe {
    private const val PROBE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    private const val TIMEOUT_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<ProbeState>(ProbeState.Idle)
    private var activeJob: Job? = null

    val state: StateFlow<ProbeState> = mutableState.asStateFlow()

    fun beginNativeVerification() {
        activeJob?.cancel()
        activeJob = null
        mutableState.value = ProbeState.Loading
    }

    fun acceptNativeExit(ip: String, countryCode: String, latencyMs: Long) {
        val normalizedCode = countryCode.trim().uppercase(Locale.US)
        val country = Locale("", normalizedCode).displayCountry.ifBlank { normalizedCode }
        mutableState.value = ProbeState.Ready(
            ExitNodeInfo(
                ip = ip.trim(),
                country = country,
                countryCode = normalizedCode,
                latencyMs = latencyMs,
            ),
        )
    }

    fun failNativeVerification(message: String) {
        mutableState.value = ProbeState.Failed(message)
    }

    fun refresh(context: Context) {
        activeJob?.cancel()
        mutableState.value = ProbeState.Loading
        activeJob = scope.launch {
            val result = runCatching { downloadProbe(context.applicationContext) }
            result.onSuccess { info ->
                mutableState.value = ProbeState.Ready(info)
                AetherNative.log(
                    "INFO",
                    "Exit probe: ip=${info.ip}, country=${info.countryCode}, latency=${info.latencyMs}ms",
                )
            }.onFailure { error ->
                if (error is CancellationException) {
                    return@launch
                }
                val message = error.message ?: "Connection probe failed"
                mutableState.value = ProbeState.Failed(message)
                AetherNative.log("WARN", "Exit probe failed: $message")
            }
        }
    }

    fun reset() {
        activeJob?.cancel()
        activeJob = null
        mutableState.value = ProbeState.Idle
    }

    private suspend fun downloadProbe(context: Context): ExitNodeInfo {
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: error("Download service is unavailable")
        val filename = "koku-probe-${System.currentTimeMillis()}.json"
        val target = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            filename,
        )
        target.delete()

        val request = DownloadManager.Request(Uri.parse(PROBE_URL))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setMimeType("text/plain")
            .setTitle("Koku connection check")
            .setDescription("Checking the VPN exit address")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                filename,
            )

        val startedAt = SystemClock.elapsedRealtime()
        val downloadId = manager.enqueue(request)
        try {
            while (SystemClock.elapsedRealtime() - startedAt < TIMEOUT_MS) {
                val snapshot = query(manager, downloadId)
                when (snapshot.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val body = manager.openDownloadedFile(downloadId).use { descriptor ->
                            FileInputStream(descriptor.fileDescriptor)
                                .bufferedReader()
                                .use { it.readText() }
                        }
                        val trace = body.lineSequence()
                            .mapNotNull { line ->
                                val separator = line.indexOf('=')
                                if (separator <= 0) null
                                else line.substring(0, separator) to line.substring(separator + 1)
                            }
                            .associate { (key, value) -> key.trim() to value.trim() }
                        val ip = trace["ip"].orEmpty()
                        val countryCode = trace["loc"].orEmpty().uppercase(Locale.US)
                        if (ip.isEmpty() || countryCode.length != 2) {
                            error("Cloudflare trace returned an incomplete response")
                        }
                        val country = Locale("", countryCode).displayCountry.ifBlank { countryCode }
                        return ExitNodeInfo(
                            ip = ip,
                            country = country,
                            countryCode = countryCode,
                            latencyMs = SystemClock.elapsedRealtime() - startedAt,
                        )
                    }

                    DownloadManager.STATUS_FAILED -> {
                        error("Network check failed (${snapshot.reason})")
                    }
                }
                delay(150)
            }
            error("Network check timed out")
        } finally {
            manager.remove(downloadId)
            target.delete()
        }
    }

    private fun query(manager: DownloadManager, id: Long): DownloadSnapshot {
        val cursor = manager.query(DownloadManager.Query().setFilterById(id))
        cursor.use {
            if (!it.moveToFirst()) {
                error("Network check disappeared")
            }
            return DownloadSnapshot(
                status = it.intValue(DownloadManager.COLUMN_STATUS),
                reason = it.intValue(DownloadManager.COLUMN_REASON),
            )
        }
    }

    private fun Cursor.intValue(column: String): Int {
        return getInt(getColumnIndexOrThrow(column))
    }

    private data class DownloadSnapshot(
        val status: Int,
        val reason: Int,
    )
}
