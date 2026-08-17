package io.github.lvl45t3r.koku

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

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

    // Small, auditable seed list. Unlike the upstream UI scanner, Koku never sweeps
    // arbitrary CIDRs during VPN startup.
    private val fallbackResolvers = listOf(
        "1.0.0.1",
        "8.8.8.8",
        "8.8.4.4",
        "9.9.9.9",
        "149.112.112.112",
    )

    data class Selection(val resolver: String, val usedFallback: Boolean)

    /**
     * Keep the normal Cloudflare resolver if it gives a clean response. Only hunt
     * the small fallback set when that direct resolver fails validation.
     */
    fun selectResolver(log: (String, String) -> Unit): Selection {
        val direct = test(DEFAULT_RESOLVER)
        if (direct != null) {
            log("INFO", "DNS Hunter: default resolver $DEFAULT_RESOLVER passed (${direct.latencyMs} ms)")
            return Selection(DEFAULT_RESOLVER, usedFallback = false)
        }

        log("WARN", "DNS Hunter: default resolver failed; testing fallback resolvers")
        val winner = fallbackResolvers
            .mapNotNull { resolver -> test(resolver)?.let { resolver to it } }
            .minByOrNull { (_, result) -> result.latencyMs }

        return if (winner == null) {
            log("WARN", "DNS Hunter: no clean fallback resolver; retaining $DEFAULT_RESOLVER")
            Selection(DEFAULT_RESOLVER, usedFallback = false)
        } else {
            log("INFO", "DNS Hunter: selected ${winner.first} (${winner.second.latencyMs} ms)")
            Selection(winner.first, usedFallback = true)
        }
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
