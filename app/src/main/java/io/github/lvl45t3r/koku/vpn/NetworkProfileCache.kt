package io.github.lvl45t3r.koku.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.lvl45t3r.koku.AetherNative
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal object NetworkProfileCache {
    private const val PREFS = "koku_network_profiles"
    private const val LOOKUP_URL = "https://speed.cloudflare.com/meta"
    private const val TIMEOUT_MS = 2_000
    private val supportedProfiles =
        setOf("balanced", "aggressive", "light", "off", "firewall", "gfw")

    fun resolveNetworkKey(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return "unknown"
        val capabilities = manager.getNetworkCapabilities(network)
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            else -> "other"
        }

        val asn = runCatching {
            val connection = network.openConnection(URL(LOOKUP_URL)) as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val payload = connection.inputStream.bufferedReader().use { it.readText() }
                JSONObject(payload).optString("asn")
                    .trim()
                    .lowercase(Locale.US)
                    .takeIf { it.matches(Regex("(?:as)?\\d+")) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

        return if (asn == null) transport else "$transport:$asn"
    }

    fun load(context: Context, networkKey: String, protocol: String): String {
        val value = preferences(context).getString(cacheKey(networkKey, protocol), null)
        return value?.takeIf(supportedProfiles::contains)
            ?: if (protocol.startsWith("masque", ignoreCase = true)) "firewall" else "balanced"
    }

    fun save(context: Context, networkKey: String, protocol: String, profile: String) {
        if (profile !in supportedProfiles) return
        preferences(context).edit().putString(cacheKey(networkKey, protocol), profile).apply()
        AetherNative.log("INFO", "Saved working profile '$profile' for $networkKey/$protocol")
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cacheKey(networkKey: String, protocol: String) =
        "${protocol.lowercase(Locale.US)}|${networkKey.lowercase(Locale.US)}"
}
