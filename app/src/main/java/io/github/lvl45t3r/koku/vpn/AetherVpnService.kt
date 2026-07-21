package io.github.lvl45t3r.koku.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import io.github.lvl45t3r.koku.AetherNative
import io.github.lvl45t3r.koku.MainActivity
import io.github.lvl45t3r.koku.R
import io.github.lvl45t3r.koku.TunnelState

class AetherVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var nativeHandle: Long = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AetherNative.log("INFO", "Stop requested")
                AetherNative.markStopping()
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> runCatching {
                startTunnel(
                    intent.getStringExtra(EXTRA_PROTOCOL) ?: "masque-h3",
                    intent.getStringExtra(EXTRA_SCAN_MODE) ?: "turbo",
                )
            }.onFailure { error ->
                AetherNative.log(
                    "ERROR",
                    "VPN startup failed: ${error.message ?: error.javaClass.simpleName}",
                )
                stopTunnel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun startTunnel(protocol: String, scanMode: String) {
        if (AetherNative.tunnelState.value != TunnelState.STARTING) {
            AetherNative.markStarting()
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())

        if (tun != null) {
            AetherNative.log("WARN", "VPN is already running")
            return
        }

        if (!AetherNative.isAvailable()) {
            AetherNative.log("ERROR", "Native Aether library is unavailable")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        AetherNative.log("INFO", "Creating Android TUN interface 172.31.19.2/32")
        val descriptor = Builder()
            .setSession("Koku")
            .setMtu(1280)
            .addAddress("172.31.19.2", 32)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .addDisallowedApplication(packageName)
            .also { builder ->
                if (Build.VERSION.SDK_INT >= 29) {
                    builder.setMetered(false)
                }
            }
            .establish()
            ?: throw IllegalStateException("Unable to establish VPN interface")

        tun = descriptor
        AetherNative.log("INFO", "TUN established; handing fd to vendored Aether engine")
        AetherNative.log(
            "INFO",
            "Aether itself bypasses the VPN to prevent a routing loop; generate test traffic in another app",
        )

        nativeHandle = AetherNative.start(
            protocol,
            scanMode,
            filesDir.absolutePath,
            descriptor,
        )
        if (nativeHandle == 0L) {
            AetherNative.log("ERROR", "Native engine rejected startup")
            stopTunnel()
            stopSelf()
        } else {
            AetherNative.log("INFO", "Native worker started (handle=$nativeHandle)")
        }
    }

    private fun stopTunnel() {
        AetherNative.stop(nativeHandle)
        nativeHandle = 0
        runCatching { tun?.close() }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        AetherNative.markStopped()
    }

    private fun notification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aether_status)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_START = "io.github.lvl45t3r.koku.START"
        private const val ACTION_STOP = "io.github.lvl45t3r.koku.STOP"
        private const val EXTRA_PROTOCOL = "protocol"
        private const val EXTRA_SCAN_MODE = "scanMode"
        private const val CHANNEL_ID = "aether_vpn"
        private const val NOTIFICATION_ID = 1819

        fun start(context: Context, protocol: String, scanMode: String) {
            val intent = Intent(context, AetherVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROTOCOL, protocol)
                .putExtra(EXTRA_SCAN_MODE, scanMode)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AetherVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
