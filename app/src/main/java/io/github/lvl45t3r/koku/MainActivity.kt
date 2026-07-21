package io.github.lvl45t3r.koku

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import io.github.lvl45t3r.koku.vpn.AetherVpnService

private val AetherRed = Color(0xFFE20D1D)
private val AetherDarkRed = Color(0xFF9F0712)
private val Ink = Color(0xFF121113)
private val Canvas = Color(0xFFF7F4F2)
private val Muted = Color(0xFF716A6B)
private val Online = Color(0xFF138A5B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            AetherTheme {
                AetherScreen()
            }
        }
    }
}

@Composable
private fun AetherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = AetherRed,
            secondary = Ink,
            tertiary = AetherDarkRed,
            background = Canvas,
            surface = Color.White,
            surfaceVariant = Color(0xFFF0E9E7),
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = Ink,
            onSurface = Ink,
            onSurfaceVariant = Muted,
            error = Color(0xFFBA1A1A),
        ),
        content = content,
    )
}

private enum class AppTab {
    DEBUG,
    HOME,
    SETTINGS,
}

@Composable
private fun AetherScreen() {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    var protocol by remember { mutableStateOf("masque-h3") }
    var scanMode by remember { mutableStateOf("turbo") }
    val logs by AetherNative.logs.collectAsState()
    val tunnelState by AetherNative.tunnelState.collectAsState()
    val traffic by AetherNative.traffic.collectAsState()
    val testState by AetherNative.testState.collectAsState()
    val diagnosticsEnabled by AetherNative.diagnosticsEnabled.collectAsState()
    val probeState by ConnectionProbe.state.collectAsState()

    val startEnabled = AetherNative.isAvailable() && tunnelState == TunnelState.IDLE
    val stopEnabled = tunnelState != TunnelState.IDLE && tunnelState != TunnelState.STOPPING
    val connected = tunnelState == TunnelState.CONNECTED

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            AetherVpnService.start(context, protocol, scanMode)
        } else {
            AetherNative.markStopped()
            AetherNative.log("WARN", "VPN permission denied")
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun startTunnel() {
        if (!startEnabled) {
            return
        }
        AetherNative.markStarting()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val permissionIntent = VpnService.prepare(context)
        if (permissionIntent != null) {
            vpnPermission.launch(permissionIntent)
        } else {
            AetherVpnService.start(context, protocol, scanMode)
        }
    }

    fun stopTunnel() {
        if (!stopEnabled) {
            return
        }
        AetherNative.markStopping()
        AetherVpnService.stop(context)
    }

    fun openGoogleTest() {
        AetherNative.beginConnectionTest()
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE),
            )
        }.onFailure { error ->
            AetherNative.markTestLaunchFailed(
                "Could not open an external browser: ${error.message}",
            )
        }
    }

    LaunchedEffect(testState) {
        if (testState == ConnectionTestState.RUNNING) {
            delay(15_000)
            AetherNative.markTestTimedOut()
        }
    }
    LaunchedEffect(tunnelState) {
        if (tunnelState == TunnelState.CONNECTED) {
            ConnectionProbe.refresh(context)
        } else {
            ConnectionProbe.reset()
        }
    }

    Scaffold(
        containerColor = Canvas,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        bottomBar = {
            Box(Modifier.navigationBarsPadding()) {
                AetherBottomBar(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFFFFFAF8),
                            Canvas,
                            Color(0xFFF2EAE8),
                        ),
                    ),
                )
                .padding(padding),
        ) {
            when (selectedTab) {
                AppTab.HOME -> HomeScreen(
                    tunnelState = tunnelState,
                    traffic = traffic,
                    probeState = probeState,
                    startEnabled = startEnabled,
                    stopEnabled = stopEnabled,
                    onStart = ::startTunnel,
                    onStop = ::stopTunnel,
                )

                AppTab.DEBUG -> DebugScreen(
                    logs = logs,
                    diagnosticsEnabled = diagnosticsEnabled,
                    connected = connected,
                    testState = testState,
                    onTestGoogle = ::openGoogleTest,
                )

                AppTab.SETTINGS -> SettingsScreen(
                    protocol = protocol,
                    scanMode = scanMode,
                    protocolEnabled = startEnabled,
                    onProtocolChange = { protocol = it },
                    onScanModeChange = { scanMode = it },
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    tunnelState: TunnelState,
    traffic: TrafficSnapshot,
    probeState: ProbeState,
    startEnabled: Boolean,
    stopEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val connected = tunnelState == TunnelState.CONNECTED
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppHeader()

        Spacer(Modifier.weight(0.55f))

        Text(
            text = when (tunnelState) {
                TunnelState.IDLE -> "Your connection is ready"
                TunnelState.STARTING -> "Building a private route"
                TunnelState.CONNECTED -> "Your traffic is protected"
                TunnelState.STOPPING -> "Closing the private route"
                TunnelState.FAILED -> "The connection needs attention"
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when (tunnelState) {
                TunnelState.CONNECTED -> "Tap to disconnect"
                TunnelState.STARTING -> "This usually takes a few seconds"
                TunnelState.STOPPING -> "Please wait"
                TunnelState.FAILED -> "Open Debug for the exact error"
                TunnelState.IDLE -> "Tap the button to connect"
            },
            modifier = Modifier.padding(top = 5.dp),
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(22.dp))

        ConnectButton(
            state = tunnelState,
            startEnabled = startEnabled,
            stopEnabled = stopEnabled,
            onStart = onStart,
            onStop = onStop,
        )

        Spacer(Modifier.weight(0.45f))

        ConnectionSummary(
            connected = connected,
            probeState = probeState,
            traffic = traffic,
        )
    }
}

@Composable
private fun AppHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.aether_logo),
                contentDescription = "Koku logo",
                modifier = Modifier
                    .size(54.dp)
                    .shadow(9.dp, CircleShape)
                    .clip(CircleShape),
            )
            Column {
                Text(
                    "Koku",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    "Private connection",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ConnectButton(
    state: TunnelState,
    startEnabled: Boolean,
    stopEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val connected = state == TunnelState.CONNECTED
    val busy = state == TunnelState.STARTING || state == TunnelState.STOPPING
    val enabled = if (connected || state == TunnelState.FAILED) stopEnabled else startEnabled
    Box(contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .size(190.dp)
                .shadow(24.dp, CircleShape, ambientColor = AetherRed, spotColor = AetherRed),
            shape = CircleShape,
            color = AetherRed.copy(alpha = 0.08f),
        ) {}
        Surface(
            modifier = Modifier
                .size(158.dp)
                .clickable(
                    enabled = enabled,
                    onClick = {
                        if (connected || state == TunnelState.FAILED) onStop() else onStart()
                    },
                ),
            shape = CircleShape,
            color = when {
                connected -> Ink
                busy -> Color(0xFF6F6667)
                state == TunnelState.FAILED -> AetherDarkRed
                else -> AetherRed
            },
            border = BorderStroke(8.dp, Color.White.copy(alpha = 0.16f)),
            shadowElevation = 13.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (connected) R.drawable.ic_power_on else R.drawable.ic_power,
                    ),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (state) {
                        TunnelState.IDLE -> "CONNECT"
                        TunnelState.STARTING -> "CONNECTING"
                        TunnelState.CONNECTED -> "DISCONNECT"
                        TunnelState.STOPPING -> "STOPPING"
                        TunnelState.FAILED -> "RESET"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    letterSpacing = 0.8.sp,
                )
            }
        }
    }
}

@Composable
private fun ConnectionSummary(
    connected: Boolean,
    probeState: ProbeState,
    traffic: TrafficSnapshot,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.94f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Latest status",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (connected) "Exit route is being verified" else "No active tunnel",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    if (connected) "● ONLINE" else "● OFFLINE",
                    color = if (connected) Online else Color(0xFF8A8384),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SummaryItem(
                    label = "EXIT IP",
                    value = when (probeState) {
                        is ProbeState.Ready -> probeState.info.ip
                        ProbeState.Loading -> "Checking…"
                        is ProbeState.Failed -> "Unavailable"
                        ProbeState.Idle -> "—"
                    },
                    modifier = Modifier.weight(1.35f),
                )
                SummaryItem(
                    label = "PING",
                    value = when (probeState) {
                        is ProbeState.Ready -> "${probeState.info.latencyMs} ms"
                        ProbeState.Loading -> "…"
                        else -> "—"
                    },
                    modifier = Modifier.weight(0.75f),
                )
            }

            val location = when (probeState) {
                is ProbeState.Ready -> {
                    "${countryFlag(probeState.info.countryCode)} ${probeState.info.country}"
                }
                is ProbeState.Failed -> probeState.message
                ProbeState.Loading -> "Detecting the new IP and country"
                ProbeState.Idle -> null
            }
            if (location != null) {
                Text(
                    text = location,
                    color = if (probeState is ProbeState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        Muted
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Text(
                "Traffic  ↑ ${formatBytes(traffic.outboundBytes)}   ↓ ${formatBytes(traffic.inboundBytes)}",
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SummaryItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color(0xFFF7F1EF),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, color = AetherRed, fontSize = 10.sp, fontWeight = FontWeight.Black)
            Text(
                value,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DebugScreen(
    logs: List<String>,
    diagnosticsEnabled: Boolean,
    connected: Boolean,
    testState: ConnectionTestState,
    onTestGoogle: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(
                "Debug",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
            Text(
                if (diagnosticsEnabled) "Diagnostic logging is running" else "Diagnostic logging is stopped",
                color = if (diagnosticsEnabled) Online else Muted,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DebugControlButton(
                label = "Start",
                enabled = !diagnosticsEnabled,
                modifier = Modifier.weight(1f),
                onClick = AetherNative::startDiagnostics,
            )
            DebugControlButton(
                label = "Stop",
                enabled = diagnosticsEnabled,
                modifier = Modifier.weight(1f),
                onClick = AetherNative::stopDiagnostics,
            )
            DebugControlButton(
                label = "Copy",
                enabled = logs.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val clipboard = context.getSystemService(
                        Context.CLIPBOARD_SERVICE,
                    ) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Koku log", AetherNative.logText()),
                    )
                    Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                },
            )
            DebugControlButton(
                label = "Clear",
                enabled = logs.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = AetherNative::clearLogs,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = connected && testState != ConnectionTestState.RUNNING,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AetherRed),
                    onClick = onTestGoogle,
                ) {
                    Text(testButtonLabel(testState), fontWeight = FontWeight.Bold)
                }
                Text(
                    text = when (testState) {
                        ConnectionTestState.IDLE -> "Opens Google and confirms traffic in both directions."
                        ConnectionTestState.RUNNING -> "Waiting for tunneled request and response packets…"
                        ConnectionTestState.PASSED -> "Passed — bidirectional tunnel traffic confirmed."
                        ConnectionTestState.FAILED -> "Failed — inspect the log below for the exact stage."
                    },
                    color = when (testState) {
                        ConnectionTestState.PASSED -> Online
                        ConnectionTestState.FAILED -> MaterialTheme.colorScheme.error
                        else -> Muted
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Surface(
            color = Ink,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (logs.isEmpty()) {
                    item {
                        Text(
                            if (diagnosticsEnabled) {
                                "Waiting for diagnostic events…"
                            } else {
                                "Press Start to begin diagnostic logging."
                            },
                            color = Color(0xFFBEB6B7),
                            fontSize = 12.sp,
                        )
                    }
                }
                items(logs) { line ->
                    Text(
                        text = line,
                        color = when {
                            " ERROR " in line -> Color(0xFFFF8A80)
                            " WARN " in line -> Color(0xFFFFD180)
                            else -> Color(0xFFE8E1E2)
                        },
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugControlButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
    ) {
        Text(
            label,
            maxLines = 1,
            softWrap = false,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SettingsScreen(
    protocol: String,
    scanMode: String,
    protocolEnabled: Boolean,
    onProtocolChange: (String) -> Unit,
    onScanModeChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
        )
        Text(
            "Connection preferences",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Tunnel protocol", fontWeight = FontWeight.Bold)
                ProtocolOption(
                    label = "MASQUE H3",
                    detail = "Fast QUIC transport",
                    value = "masque-h3",
                    selected = protocol,
                    enabled = protocolEnabled,
                    onSelect = onProtocolChange,
                )
                ProtocolOption(
                    label = "MASQUE H2",
                    detail = "HTTPS-compatible transport",
                    value = "masque-h2",
                    selected = protocol,
                    enabled = protocolEnabled,
                    onSelect = onProtocolChange,
                )
                ProtocolOption(
                    label = "WireGuard",
                    detail = "Classic UDP tunnel",
                    value = "wireguard",
                    selected = protocol,
                    enabled = protocolEnabled,
                    onSelect = onProtocolChange,
                )
                if (!protocolEnabled) {
                    Text(
                        "Disconnect before changing protocol.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Gateway scan", fontWeight = FontWeight.Bold)
                ProtocolOption(
                    label = "Fast",
                    detail = "Turbo handshake scan",
                    value = "turbo",
                    selected = scanMode,
                    enabled = protocolEnabled,
                    onSelect = onScanModeChange,
                )
                ProtocolOption(
                    label = "Reliable",
                    detail = "Real tunnel + HTTP check",
                    value = "ironclad",
                    selected = scanMode,
                    enabled = protocolEnabled,
                    onSelect = onScanModeChange,
                )
                Text(
                    "Reliable takes longer, but only selects a gateway after an end-to-end request succeeds.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "Koku · Powered by Aether",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ProtocolOption(
    label: String,
    detail: String,
    value: String,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        enabled = enabled,
        label = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, fontWeight = FontWeight.Bold)
                Text(detail, color = if (selected == value) Color.White else Muted, fontSize = 11.sp)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AetherRed,
            selectedLabelColor = Color.White,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected == value,
            borderColor = Color(0xFFE1D8D6),
            selectedBorderColor = AetherRed,
        ),
    )
}

@Composable
private fun AetherBottomBar(
    selectedTab: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            color = Ink,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            shadowElevation = 18.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomItem(
                    label = "Debug",
                    icon = R.drawable.ic_nav_debug,
                    selected = selectedTab == AppTab.DEBUG,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(AppTab.DEBUG) },
                )
                Spacer(Modifier.weight(1f))
                BottomItem(
                    label = "Settings",
                    icon = R.drawable.ic_nav_settings,
                    selected = selectedTab == AppTab.SETTINGS,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(AppTab.SETTINGS) },
                )
            }
        }
        Column(
            modifier = Modifier
                .offset(y = (-9).dp)
                .clickable { onSelect(AppTab.HOME) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier
                    .size(66.dp)
                    .shadow(12.dp, CircleShape),
                shape = CircleShape,
                color = if (selectedTab == AppTab.HOME) AetherRed else Color(0xFF332F30),
                border = BorderStroke(6.dp, Canvas),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_nav_home),
                    contentDescription = "Home",
                    tint = Color.White,
                    modifier = Modifier.padding(15.dp),
                )
            }
            Text(
                "Home",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun BottomItem(
    label: String,
    icon: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = if (selected) AetherRed else Color(0xFFCFC7C8),
            modifier = Modifier.size(23.dp),
        )
        Text(
            label,
            color = if (selected) AetherRed else Color(0xFFCFC7C8),
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

private fun testButtonLabel(state: ConnectionTestState): String = when (state) {
    ConnectionTestState.IDLE -> "Test Google through VPN"
    ConnectionTestState.RUNNING -> "Testing Google…"
    ConnectionTestState.PASSED -> "Test Google again"
    ConnectionTestState.FAILED -> "Retry Google test"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private fun countryFlag(countryCode: String): String {
    if (countryCode.length != 2 || countryCode.any { it !in 'A'..'Z' }) {
        return "🌐"
    }
    return countryCode
        .map { char -> Character.toChars(0x1F1E6 + char.code - 'A'.code).concatToString() }
        .joinToString("")
}
