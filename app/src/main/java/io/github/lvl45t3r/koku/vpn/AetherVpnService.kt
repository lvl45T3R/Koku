package io.github.lvl45t3r.koku.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import io.github.lvl45t3r.koku.AetherNative
import io.github.lvl45t3r.koku.MainActivity
import io.github.lvl45t3r.koku.PerAppProxyMode
import io.github.lvl45t3r.koku.R
import io.github.lvl45t3r.koku.TunnelState

class AetherVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var nativeHandle: Long = 0
    @Volatile private var startupGeneration: Long = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                startupGeneration++
                AetherNative.log("INFO", "Stop requested")
                AetherNative.markStopping()
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> prepareTunnelStart(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        startupGeneration++
        stopTunnel()
        super.onDestroy()
    }

    private fun prepareTunnelStart(intent: Intent) {
        if (tun != null) {
            AetherNative.log("WARN", "VPN is already running")
            return
        }

        if (AetherNative.tunnelState.value != TunnelState.STARTING) {
            AetherNative.markStarting()
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())

        val protocol = intent.getStringExtra(EXTRA_PROTOCOL) ?: "masque-h3"
        val scanMode = intent.getStringExtra(EXTRA_SCAN_MODE) ?: "turbo"
        val perAppMode = intent.getStringExtra(EXTRA_PER_APP_MODE)
            ?: PerAppProxyMode.ALL.wireName
        val perAppPackages = intent.getStringArrayListExtra(EXTRA_PER_APP_PACKAGES)
            ?.toSet()
            ?: emptySet()
        val generation = ++startupGeneration

        Thread({
            val networkKey = NetworkProfileCache.resolveNetworkKey(this)
            val profile = NetworkProfileCache.load(this, networkKey, protocol)
            AetherNative.log(
                "INFO",
                "Network profile scope: $networkKey; trying '$profile' first",
            )
            Handler(Looper.getMainLooper()).post {
                if (generation != startupGeneration) return@post
                runCatching {
                    startTunnel(
                        protocol,
                        scanMode,
                        perAppMode,
                        perAppPackages,
                        networkKey,
                        profile,
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
        }, "koku-network-profile").apply {
            isDaemon = true
            start()
        }
    }

    private fun startTunnel(
        protocol: String,
        scanMode: String,
        perAppMode: String,
        perAppPackages: Set<String>,
        networkKey: String,
        noizeProfile: String,
    ) {
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
        val builder = Builder()
            .setSession("Koku")
            .setMtu(1280)
            .addAddress("172.31.19.2", 32)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .also { builder ->
                if (Build.VERSION.SDK_INT >= 29) {
                    builder.setMetered(false)
                }
            }

        configurePerAppProxy(builder, perAppMode, perAppPackages)

        val descriptor = builder.establish()
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
            noizeProfile,
            filesDir.absolutePath,
            descriptor,
        ) { workingProfile ->
            NetworkProfileCache.save(this, networkKey, protocol, workingProfile)
        }
        if (nativeHandle == 0L) {
            AetherNative.log("ERROR", "Native engine rejected startup")
            stopTunnel()
            stopSelf()
        } else {
            AetherNative.log("INFO", "Native worker started (handle=$nativeHandle)")
        }
    }

    private fun configurePerAppProxy(
        builder: Builder,
        mode: String,
        selectedPackages: Set<String>,
    ) {
        val packages = selectedPackages
            .filter { it.isNotBlank() && it != packageName }
            .toSortedSet()

        when (mode) {
            PerAppProxyMode.SELECTED.wireName -> {
                if (packages.isEmpty()) {
                    AetherNative.log(
                        "WARN",
                        "Per-app proxy selected-only mode has no apps; falling back to all apps",
                    )
                    addDisallowedPackage(builder, packageName)
                } else {
                    val includedCount = packages.count { addAllowedPackage(builder, it) }
                    if (includedCount == 0) {
                        AetherNative.log(
                            "WARN",
                            "No selected apps could be included; falling back to all apps",
                        )
                        addDisallowedPackage(builder, packageName)
                    } else {
                        AetherNative.log(
                            "INFO",
                            "Per-app proxy mode: selected apps only ($includedCount)",
                        )
                    }
                }
            }
            PerAppProxyMode.BYPASS.wireName -> {
                addDisallowedPackage(builder, packageName)
                packages.forEach { addDisallowedPackage(builder, it) }
                AetherNative.log(
                    "INFO",
                    "Per-app proxy mode: bypass selected apps (${packages.size})",
                )
            }
            else -> {
                addDisallowedPackage(builder, packageName)
                AetherNative.log("INFO", "Per-app proxy mode: all apps")
            }
        }
    }

    private fun addAllowedPackage(builder: Builder, targetPackage: String): Boolean {
        return runCatching {
            builder.addAllowedApplication(targetPackage)
            true
        }.onFailure {
            AetherNative.log("WARN", "Cannot include $targetPackage in per-app proxy: ${it.message}")
        }.getOrDefault(false)
    }

    private fun addDisallowedPackage(builder: Builder, targetPackage: String) {
        runCatching {
            builder.addDisallowedApplication(targetPackage)
        }.onFailure {
            AetherNative.log("WARN", "Cannot bypass $targetPackage from per-app proxy: ${it.message}")
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
        private const val EXTRA_PER_APP_MODE = "perAppMode"
        private const val EXTRA_PER_APP_PACKAGES = "perAppPackages"
        private const val CHANNEL_ID = "aether_vpn"
        private const val NOTIFICATION_ID = 1819

        fun start(
            context: Context,
            protocol: String,
            scanMode: String,
            perAppMode: PerAppProxyMode,
            perAppPackages: Set<String>,
        ) {
            val intent = Intent(context, AetherVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROTOCOL, protocol)
                .putExtra(EXTRA_SCAN_MODE, scanMode)
                .putExtra(EXTRA_PER_APP_MODE, perAppMode.wireName)
                .putStringArrayListExtra(
                    EXTRA_PER_APP_PACKAGES,
                    ArrayList(perAppPackages.toList()),
                )
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
