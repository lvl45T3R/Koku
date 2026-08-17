package io.github.lvl45t3r.koku

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Resolver fallback adapted from network-checker's GPL-3.0 DNS Hunter.
 *
 * Upstream: mirarr-app/network-checker@f2a259b3e53449c512183baf6805c0e99ed83500
 * Attribution and license information: third_party/network-checker/UPSTREAM.md
 */
internal object DnsHunter {
    private const val DEFAULT_RESOLVER = "1.1.1.1"
    private const val TARGET = "x.com"
    private const val TIMEOUT_MS = 1_500
    private const val TRANSACTION_ID = 0xABCD
    private const val IRANIAN_RANGE_ASSET = "network_checker_dns_ranges.dart"
    private const val MAX_IRANIAN_PROBES = 128
    private const val MAX_CONCURRENT_PROBES = 16

    // Small, auditable seed list. Unlike the upstream UI scanner, Koku never sweeps
    // arbitrary CIDRs during VPN startup.
    private val fallbackResolvers = listOf(
        "1.0.0.1",
        "8.8.8.8",
        "8.8.4.4",
        "9.9.9.9",
        "149.112.112.112",
    )

    enum class Mode(val wireName: String) {
        DEFAULT("default"),
        PUBLIC_FALLBACK("public"),
        IRANIAN_HUNTER("iran"),
        CUSTOM("custom");

        companion object {
            fun fromWireName(value: String) = entries.firstOrNull { it.wireName == value } ?: DEFAULT
        }
    }

    data class Selection(val resolver: String, val source: String)

    /**
     * Keep the normal Cloudflare resolver if it gives a clean response. Only hunt
     * the small fallback set when that direct resolver fails validation.
     */
    fun selectResolver(
        context: Context,
        mode: Mode,
        customResolvers: String,
        log: (String, String) -> Unit,
    ): Selection = when (mode) {
        Mode.DEFAULT -> Selection(DEFAULT_RESOLVER, "default")
        Mode.PUBLIC_FALLBACK -> selectPublicFallback(log)
        Mode.IRANIAN_HUNTER -> selectIranianResolver(context, log)
        Mode.CUSTOM -> selectCustomResolver(customResolvers, log)
    }

    private fun selectPublicFallback(log: (String, String) -> Unit): Selection {
        val direct = test(DEFAULT_RESOLVER)
        if (direct != null) {
            log("INFO", "DNS Hunter: default resolver $DEFAULT_RESOLVER passed (${direct.latencyMs} ms)")
            return Selection(DEFAULT_RESOLVER, "default")
        }

        log("WARN", "DNS Hunter: default resolver failed; testing fallback resolvers")
        val winner = fallbackResolvers
            .mapNotNull { resolver -> test(resolver)?.let { resolver to it } }
            .minByOrNull { (_, result) -> result.latencyMs }

        return if (winner == null) {
            log("WARN", "DNS Hunter: no clean fallback resolver; retaining $DEFAULT_RESOLVER")
            Selection(DEFAULT_RESOLVER, "default")
        } else {
            log("INFO", "DNS Hunter: selected ${winner.first} (${winner.second.latencyMs} ms)")
            Selection(winner.first, "public fallback")
        }
    }

    private fun selectCustomResolver(value: String, log: (String, String) -> Unit): Selection {
        val candidates = value.split(Regex("[,\\s]+"))
            .map(String::trim)
            .filter(::isIpv4)
            .distinct()
            .take(MAX_IRANIAN_PROBES)
        if (candidates.isEmpty()) {
            log("WARN", "DNS Storming: custom mode has no valid IPv4 resolver; retaining $DEFAULT_RESOLVER")
            return Selection(DEFAULT_RESOLVER, "default")
        }
        return chooseFastest(candidates, "custom", log)
    }

    private fun selectIranianResolver(context: Context, log: (String, String) -> Unit): Selection {
        val candidates = runCatching { iranianCandidates(context) }.getOrElse {
            log("WARN", "DNS Storming: Iranian range list could not be read; ${it.message}")
            emptyList()
        }
        if (candidates.isEmpty()) {
            log("WARN", "DNS Storming: no Iranian candidates available; retaining $DEFAULT_RESOLVER")
            return Selection(DEFAULT_RESOLVER, "default")
        }
        log("INFO", "DNS Storming: testing ${candidates.size} sampled addresses from Iranian Hunter ranges")
        return chooseFastest(candidates, "Iranian Hunter", log)
    }

    private fun chooseFastest(
        candidates: List<String>,
        source: String,
        log: (String, String) -> Unit,
    ): Selection {
        val executor = Executors.newFixedThreadPool(MAX_CONCURRENT_PROBES)
        return try {
            val winner = executor.invokeAll(candidates.map { resolver ->
                Callable { test(resolver)?.let { resolver to it } }
            }).mapNotNull { it.get() }.minByOrNull { (_, result) -> result.latencyMs }
            if (winner == null) {
                log("WARN", "DNS Storming: no clean $source resolver found; retaining $DEFAULT_RESOLVER")
                Selection(DEFAULT_RESOLVER, "default")
            } else {
                log("INFO", "DNS Storming: selected ${winner.first} from $source (${winner.second.latencyMs} ms)")
                Selection(winner.first, source)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * The upstream list contains ISP CIDRs, not a claim that every address is a
     * DNS server. Sample one deterministic usable host per range, capped so a
     * VPN start remains bounded; manual mode is available for known resolver IPs.
     */
    private fun iranianCandidates(context: Context): List<String> {
        val cidrs = context.assets.open(IRANIAN_RANGE_ASSET).bufferedReader().use { reader ->
            Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}/(?:[0-9]|[12][0-9]|3[0-2])\\b")
                .findAll(reader.readText())
                .map { it.value }
                .toList()
        }
        return cidrs.distinct().take(MAX_IRANIAN_PROBES).mapNotNull(::sampleCidr)
    }

    private fun sampleCidr(cidr: String): String? {
        val (address, prefixText) = cidr.split('/', limit = 2).let { it[0] to it[1] }
        val octets = address.split('.').mapNotNull { it.toLongOrNull() }
        val prefix = prefixText.toIntOrNull() ?: return null
        if (octets.size != 4 || prefix !in 0..32 || octets.any { it !in 0..255 }) return null
        val value = octets.fold(0L) { current, octet -> (current shl 8) or octet }
        val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val hostCount = 1L shl (32 - prefix)
        val offset = when {
            hostCount <= 2L -> 0L
            else -> 1L + ((value xor prefix.toLong()) % (hostCount - 2L))
        }
        val selected = ((value and mask) + offset) and 0xffffffffL
        return listOf(24, 16, 8, 0).joinToString(".") { shift -> ((selected shr shift) and 0xff).toString() }
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.').mapNotNull { it.toIntOrNull() }
        return parts.size == 4 && parts.all { it in 0..255 }
    }

    private data class Result(val latencyMs: Long)

    private fun test(resolver: String): Result? {
        val startedAt = System.nanoTime()
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                val query = buildQuery(TARGET)
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName(resolver), 53))
                val response = ByteArray(1_500)
                val packet = DatagramPacket(response, response.size)
                socket.receive(packet)
                val addresses = parseResponse(response.copyOf(packet.length))
                if (addresses.any(::isExpectedCloudflareAddress)) {
                    Result((System.nanoTime() - startedAt) / 1_000_000)
                } else {
                    null
                }
            }
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun buildQuery(domain: String): ByteArray {
        val bytes = ArrayList<Byte>(64)
        fun put(value: Int) { bytes += value.toByte() }
        put(TRANSACTION_ID ushr 8); put(TRANSACTION_ID)
        put(0x01); put(0x00) // standard recursive query
        put(0x00); put(0x01) // one question
        repeat(6) { put(0x00) }
        domain.split('.').forEach { label ->
            put(label.length)
            label.encodeToByteArray().forEach { bytes += it }
        }
        put(0x00)
        put(0x00); put(0x01) // A / IN
        put(0x00); put(0x01)
        return bytes.toByteArray()
    }

    private fun parseResponse(data: ByteArray): List<String> {
        if (data.size < 12 || data[0].u8() != 0xAB || data[1].u8() != 0xCD) return emptyList()
        if (data[2].u8() and 0x80 == 0 || data[3].u8() and 0x0f != 0) return emptyList()
        val answerCount = (data[6].u8() shl 8) or data[7].u8()
        var offset = skipName(data, 12) ?: return emptyList()
        if (offset + 4 > data.size) return emptyList()
        offset += 4
        val addresses = mutableListOf<String>()
        repeat(answerCount) {
            offset = skipName(data, offset) ?: return addresses
            if (offset + 10 > data.size) return addresses
            val type = (data[offset].u8() shl 8) or data[offset + 1].u8()
            val length = (data[offset + 8].u8() shl 8) or data[offset + 9].u8()
            offset += 10
            if (offset + length > data.size) return addresses
            if (type == 1 && length == 4) {
                addresses += "${data[offset].u8()}.${data[offset + 1].u8()}.${data[offset + 2].u8()}.${data[offset + 3].u8()}"
            }
            offset += length
        }
        return addresses
    }

    private fun skipName(data: ByteArray, start: Int): Int? {
        var offset = start
        while (offset < data.size) {
            val length = data[offset].u8()
            if (length and 0xc0 == 0xc0) return if (offset + 2 <= data.size) offset + 2 else null
            if (length == 0) return offset + 1
            offset += length + 1
        }
        return null
    }

    private fun isExpectedCloudflareAddress(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || isPrivate(parts)) return false
        return (parts[0] == 104 && parts[1] in 16..23) ||
            (parts[0] == 172 && parts[1] in 64..71) ||
            (parts[0] == 108 && parts[1] == 162) ||
            (parts[0] == 162 && parts[1] in 158..159)
    }

    private fun isPrivate(parts: List<Int>) =
        parts[0] == 10 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)

    private fun Byte.u8() = toInt() and 0xff
}
