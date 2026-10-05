package io.github.lvl45t3r.koku

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Options map directly to the pinned Aether core. Never exported in diagnostic logs. */
object EngineSettings {
    private const val PREFS = "aether_engine_settings"
    fun load(context: Context): JSONObject = runCatching {
        JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("options", "{}") ?: "{}")
    }.getOrDefault(JSONObject())
    fun save(context: Context, options: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("options", options.toString()).apply()
    }
}
private data class CoreField(val key: String, val label: String, val help: String, val secret: Boolean = false)
private val coreFields = listOf(
    CoreField("AETHER_UPSTREAM", "Upstream proxy", "socks5://127.0.0.1:1080 or http://host:8080; TCP MASQUE uses H2 through a proxy"),
    CoreField("AETHER_ROUTE_BLOCK", "Block rules", "Comma/newline separated: example.com, full:example.com, keyword:ads, regexp:..., 10.0.0.0/8, port:25, private"),
    CoreField("AETHER_ROUTE_DIRECT", "Direct rules", "Same syntax; bypass the tunnel. Block rules take priority."),
    CoreField("AETHER_EXIT_LOC", "Exit country policy", "DE,NL to allow countries, !IR to exclude. NO-IR switch takes priority."),
    CoreField("AETHER_NOIZE", "Obfuscation profile", "off, light, firewall, balanced, gfw, aggressive; blank uses network profile"),
    CoreField("AETHER_IP", "Endpoint address family", "v4, v6, both; default v4"),
    CoreField("AETHER_QUICK_RECONNECT", "Quick reconnect", "1 (default) or 0; validate the saved gateway before rescanning"),
    CoreField("AETHER_PEER", "MASQUE endpoint", "IP:port; blank discovers automatically"),
    CoreField("AETHER_WG_PEER", "WireGuard endpoint", "IP:port; blank discovers automatically"),
    CoreField("AETHER_WIW_OUTER_PEER", "Classic gool outer hop", "IP:port; the two hops must have different IPs"),
    CoreField("AETHER_WIW_INNER_PEER", "Classic gool inner hop", "IP:port; blank scans for this hop"),
    CoreField("AETHER_GOOL_INNER", "WireGuard inside MASQUE hop", "IP:port; blank discovers automatically"),
    CoreField("AETHER_MIM_OUTER_PEER", "Nested MASQUE outer hop", "IP:port"),
    CoreField("AETHER_MIM_INNER_PEER", "Nested MASQUE inner hop", "IP:port, different from outer hop"),
    CoreField("AETHER_MASQUE_H2_FRAGMENT", "TLS ClientHello fragmentation", "1 (default) or 0; applies to H2"),
    CoreField("AETHER_MASQUE_H2_FRAGMENT_SIZE", "Fragment size", "Bytes: 16-32 (default) or a single number"),
    CoreField("AETHER_MASQUE_H2_FRAGMENT_DELAY", "Fragment delay", "Milliseconds: 2-10 (default)"),
    CoreField("AETHER_QUIC_V2", "QUIC v2 opener", "1 (default) or 0"),
    CoreField("AETHER_ECH", "Encrypted Client Hello", "auto or base64 key; blank disables. Fails closed when no usable key exists."),
    CoreField("AETHER_ECH_DNS", "ECH key resolver", "udp://1.1.1.1, tcp://IP:53, or HTTPS DoH URL; @address= and @sni= supported"),
    CoreField("AETHER_ECH_DOMAIN", "ECH domain", "cloudflare-ech.com by default"),
    CoreField("AETHER_TLS_GROUPS", "TLS groups", "P-256:X25519:P-384 by default"),
    CoreField("AETHER_TLS_CIPHERS", "TLS 1.2 cipher suites", "Colon separated BoringSSL cipher names"),
    CoreField("AETHER_DISABLE_GREASE", "Disable TLS GREASE", "0 (default) or 1"),
    CoreField("AETHER_TLS_VERIFY", "Verify TLS certificates", "1 enables upstream certificate verification"),
    CoreField("AETHER_ENROLL_ADDRESS", "WARP API address", "IP or host[:port]; retains Cloudflare API SNI"),
    CoreField("AETHER_API_FRAGMENT", "Fragment API enrollment", "1 to use the fragmented registration route"),
    CoreField("AETHER_DNS", "Tunnel resolvers", "1.1.1.1,1.0.0.1 by default; used by core hostname resolution"),
    CoreField("AETHER_WG_KEEPALIVE", "WireGuard keepalive", "Seconds, default 5"),
    CoreField("AETHER_WG_NO_PROFILE_RETRY", "Disable profile fallback", "1 to use only the selected WireGuard profile"),
    CoreField("AETHER_MASQUE_VALIDATE_SECS", "MASQUE validation timeout", "Seconds, default 10"),
    CoreField("AETHER_WG_VALIDATE_SECS", "WireGuard validation timeout", "Seconds"),
    CoreField("AETHER_MASQUE_STARTUP_SECS", "MASQUE startup timeout", "Seconds, default 30"),
    CoreField("AETHER_MASQUE_RECONNECT_SECS", "MASQUE reconnect delay", "Seconds, default 2"),
    CoreField("AETHER_WG_RECONNECT_SECS", "WireGuard reconnect delay", "Seconds, default 2"),
    CoreField("AETHER_PERF_PROFILE", "Resource profile", "low, medium, high; blank detects CPU/RAM"),
    CoreField("AETHER_TEAM", "Zero Trust team", "Organization team name; use an enrollment JWT or service token below"),
    CoreField("AETHER_ACCESS_CLIENT_ID", "Access service token ID", "For headless organization enrollment", true),
    CoreField("AETHER_ACCESS_CLIENT_SECRET", "Access service token secret", "Stored only in private app settings", true),
    CoreField("AETHER_ACCESS_TOKEN", "Access enrollment JWT", "From https://TEAM.cloudflareaccess.com/warp", true),
    CoreField("AETHER_GATEWAY", "Organization gateway", "1 to apply the organization's HTTP/HTTPS gateway policies"),
    CoreField("AETHER_TOR_BRIDGES", "Tor bridges", "auto, off, or bridge lines separated by semicolons"),
    CoreField("AETHER_TOR_RELAYS", "Tor relay discovery", "auto, only, off or count such as 80"),
    CoreField("AETHER_TOR_RELAY_PORTS", "Tor relay ports", "web (default) or any"),
    CoreField("AETHER_PSIPHON_MODE", "Psiphon transport", "auto, cdn or direct"),
    CoreField("AETHER_PSIPHON_REGION", "Psiphon exit region", "Two-letter country, e.g. DE"),
    CoreField("AETHER_PSIPHON_CDN_IPS", "Psiphon CDN edges", "Comma/space separated IP addresses"),
    CoreField("AETHER_PSIPHON_CDN_SNI", "Psiphon CDN server names", "Server names presented to fronting edges"),
    CoreField("AETHER_PSIPHON_CDN_SETS", "Psiphon CDN lists", "cloudflare, fastly, cloudfront, vercel, github and upstream curated lists"),
)

private val extraCoreFields = listOf(
    CoreField("AETHER_LOG_LEVEL", "Diagnostic detail", "info, debug or trace; lifecycle/statistics always remain available"),
    CoreField("AETHER_ROUTE_SNIFF", "TLS/HTTP name sniffing", "1 (default) or 0; enables domain rules behind Android TUN"),
    CoreField("AETHER_ROUTE_SNIFF_MS", "Name sniff timeout", "Milliseconds, default 400"),
    CoreField("AETHER_MASQUE_H2_PEER", "H2 endpoint override", "IP:port; overrides only the HTTP/2 carrier"),
    CoreField("AETHER_TOR_COUNTRY", "Tor bridge discovery country", "Two-letter country used for BridgeDB, e.g. ir"),
    CoreField("AETHER_TOR_CHECK", "Tor proof destination", "Host:port, default check.torproject.org:443"),
    CoreField("AETHER_TOR_DIRECT_SECS", "Tor direct attempt timeout", "Seconds before trying bridges, default 75"),
    CoreField("AETHER_TOR_STALL_SECS", "Tor bridge stall timeout", "Seconds without bootstrap progress, default 75"),
    CoreField("AETHER_TOR_BRIDGE_SECS", "Tor bridge attempt timeout", "Seconds, default 360"),
    CoreField("AETHER_EXIT_LOC_SECS", "WARP country check interval", "Seconds, default 60; final NO-IR proxy check stays at 60 seconds"),
    CoreField("AETHER_PSIPHON_READY_SECS", "Psiphon startup deadline", "Seconds allowed for the helper to connect"),
    CoreField("AETHER_MASQUE_H2_KEEPALIVE_SECS", "H2 keepalive interval", "Seconds between idle pings"),
    CoreField("AETHER_MASQUE_H2_KEEPALIVE_TIMEOUT_SECS", "H2 keepalive deadline", "Seconds to wait for a ping response"),
    CoreField("AETHER_WG_STALE_SECS", "WireGuard stale threshold", "Seconds without useful replies before reconnecting"),
    CoreField("AETHER_WG_ENDPOINT_COOLDOWN_SECS", "WireGuard endpoint cooldown", "Seconds to avoid repeatedly failing gateways"),
    CoreField("AETHER_TCP_CONNECT_SECS", "Tunnel TCP connection timeout", "Seconds"),
    CoreField("AETHER_TCP_KEEPALIVE_SECS", "Tunnel TCP keepalive", "Seconds"),
    CoreField("AETHER_HALF_CLOSE_SECS", "TCP half-close deadline", "Seconds"),
    CoreField("AETHER_MAX_CLIENTS", "Proxy client limit", "Maximum concurrent proxy clients"),
    CoreField("AETHER_IRONCLAD_PORT", "Ironclad probe port", "Destination port for end-to-end validation"),
)
private val importFields = listOf(
    "AETHER_ROUTES_FILE" to "Routing file",
    "AETHER_TOR_BRIDGE_FILE" to "Tor bridge file",
    "AETHER_PSIPHON_CONFIG" to "Psiphon JSON config",
    "AETHER_PSIPHON_SERVER_ENTRIES" to "Psiphon server entries",
)
@Composable
fun EngineOptionsCard(enabled: Boolean) {
    val context = LocalContext.current
    var options by remember { mutableStateOf(EngineSettings.load(context)) }
    var expanded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var importKey by remember { mutableStateOf("") }
    var importStatus by remember { mutableStateOf("") }
    fun update(key: String, value: String) {
        options = JSONObject(options.toString()).apply { if (value.isBlank()) remove(key) else put(key, value) }
        EngineSettings.save(context, options)
    }
    val fileImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = importKey
        if (uri != null && key.isNotEmpty()) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val directory = java.io.File(context.filesDir, "engine-imports").apply { mkdirs() }
                    val output = java.io.File(directory, "$key.txt")
                    val temporary = java.io.File(directory, "$key.tmp")
                    try {
                        (context.contentResolver.openInputStream(uri) ?: error("Cannot open file")).use { input ->
                            temporary.outputStream().use { sink ->
                                val buffer = ByteArray(8192); var total = 0L
                                while (true) { val count = input.read(buffer); if (count < 0) break
                                    total += count; check(total <= 16 * 1024 * 1024) { "File exceeds 16 MB" }; sink.write(buffer, 0, count) }
                            }
                        }
                        temporary.copyTo(output, overwrite = true)
                        output.absolutePath
                    } finally { temporary.delete() }
                }
            }.onSuccess { update(key, it); importStatus = "File imported" }
                .onFailure { importStatus = it.message ?: "Import failed" }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Aether 2.3.0", style = MaterialTheme.typography.titleMedium)
            Text("Local SOCKS5 127.0.0.1:1819 · HTTP CONNECT 127.0.0.1:1830. DNS Storming remains available; VPN DNS travels through the proxy over TCP.")
            for ((key, label) in listOf("AETHER_TOR" to "Tor", "AETHER_PSIPHON" to "Psiphon")) {
                Text("$label exit", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((mode, title) in listOf("off" to "Off", "chain" to "Inside", "reverse" to "Outside", "only" to "Only")) {
                        FilterChip(selected = options.optString(key, "off") == mode, enabled = enabled,
                            onClick = {
                                update(key, mode)
                                if (mode != "off") update(if (key == "AETHER_TOR") "AETHER_PSIPHON" else "AETHER_TOR", "off")
                            }, label = { Text(title) })
                    }
                }
            }
            Text("Inside: tunnel → carrier; Outside: carrier → MASQUE H2 (select a MASQUE protocol); Only: carrier without WARP. Tor/Psiphon carry TCP; UDP applications require a WARP exit.")
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide advanced settings" else "Advanced settings") }
            if (expanded) {
                OutlinedTextField(search, { search = it }, label = { Text("Find a setting") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(options.optString("AETHER_TEAM"), { update("AETHER_TEAM", it) }, enabled = enabled, label = { Text("Zero Trust team") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                TeamLoginPanel(enabled, options.optString("AETHER_TEAM")) { token -> update("AETHER_ACCESS_TOKEN", token) }
                for (field in (coreFields + extraCoreFields).filter { it.key != "AETHER_TEAM" && (search.isBlank() || it.label.contains(search, true) || it.help.contains(search, true) || it.key.contains(search, true)) }) {
                    OutlinedTextField(value = options.optString(field.key), onValueChange = { update(field.key, it) },
                        enabled = enabled, modifier = Modifier.fillMaxWidth(), label = { Text(field.label) },
                        supportingText = { Text(field.help) },
                        visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        singleLine = field.secret)
                }
                for ((key, label) in importFields) {
                    Row {
                        TextButton(enabled = enabled, onClick = { importKey = key; fileImporter.launch(arrayOf("*/*")) }) { Text("Import $label") }
                        if (options.has(key)) TextButton(enabled = enabled, onClick = { update(key, "") }) { Text("Clear") }
                    }
                }
                if (importStatus.isNotBlank()) Text(importStatus)
                TextButton(enabled = enabled, onClick = { options = JSONObject(); EngineSettings.save(context, options) }) { Text("Reset advanced settings") }
            }
        }
    }
}
private suspend fun awaitTeam(action: String, payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
    val started = AetherNative.teamCall(action, payload)
    check(started.optBoolean("ok")) { started.optString("error", "Sign-in could not start") }
    val id = started.getLong("job")
    try {
        withTimeout(90_000) {
            while (true) {
                val polled = AetherNative.teamCall("poll", JSONObject().put("id", id))
                check(polled.optBoolean("ok")) { polled.optString("error") }
                if (polled.optString("state") == "done") {
                    val result = polled.getJSONObject("result")
                    check(result.optBoolean("ok")) { result.optString("error", "Sign-in failed") }
                    return@withTimeout result
                }
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE") JSONObject()
        }
    } finally { AetherNative.teamCall("free", JSONObject().put("id", id)) }
}
@Composable
private fun TeamLoginPanel(enabled: Boolean, team: String, onToken: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var session by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    DisposableEffect(team) {
        onDispose { if (session != 0L && AetherNative.isAvailable()) AetherNative.teamCall("sessionFree", JSONObject().put("id", session)); session = 0 }
    }
    Text("Zero Trust email sign-in", style = MaterialTheme.typography.titleSmall)
    Text("Enter the organization team name below, then request and submit its email code. The resulting enrollment token is saved privately.")
    OutlinedTextField(email, { email = it }, enabled = enabled && !busy, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row {
        TextButton(enabled = enabled && !busy && team.isNotBlank() && email.isNotBlank(), onClick = {
            scope.launch {
                busy = true
                runCatching { awaitTeam("request", JSONObject().put("team", team).put("email", email)) }
                    .onSuccess {
                        if (session != 0L) AetherNative.teamCall("sessionFree", JSONObject().put("id", session))
                        session = it.getLong("session"); status = "Code sent"
                    }.onFailure { status = it.message ?: "Request failed" }
                busy = false
            }
        }) { Text("Send code") }
        TextButton(enabled = enabled && !busy && session != 0L, onClick = {
            scope.launch { busy = true; runCatching { awaitTeam("resend", JSONObject().put("id", session)) }
                .onSuccess { status = "Code resent" }.onFailure { status = it.message ?: "Resend failed" }; busy = false }
        }) { Text("Resend") }
    }
    if (session != 0L) {
        OutlinedTextField(code, { code = it }, enabled = enabled && !busy, label = { Text("Email code") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        TextButton(enabled = enabled && !busy && code.isNotBlank(), onClick = {
            scope.launch { busy = true; runCatching { awaitTeam("submit", JSONObject().put("id", session).put("code", code)) }
                .onSuccess {
                    if (it.optBoolean("signed_in")) { onToken(it.getString("token")); status = "Signed in"; code = "";
                        AetherNative.teamCall("sessionFree", JSONObject().put("id", session)); session = 0
                    } else status = "Code was rejected"
                }.onFailure { status = it.message ?: "Sign-in failed" }; busy = false }
        }) { Text("Sign in") }
    }
    if (status.isNotBlank()) Text(status)
}
