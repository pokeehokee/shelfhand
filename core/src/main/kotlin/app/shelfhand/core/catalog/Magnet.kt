package app.shelfhand.core.catalog

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A magnet link reduced to what is worth keeping: the info hash per item, and the trackers, which a
 * feed nearly always repeats on every item and so are kept once per source. A magnet with the hash
 * alone still works, but it must find peers through DHT, which is slower and fails on some swarms.
 */
data class Magnet(val infoHash: String, val name: String?, val trackers: List<String>) {
    companion object {
        private val hex = Regex("^[0-9a-fA-F]{40}$")
        private val b32 = Regex("^[A-Za-z2-7]{32}$")

        /** Null for anything that is not a v1 BitTorrent magnet. The hash is always 40 lower-case hex digits. */
        fun parse(link: String): Magnet? {
            if (!link.startsWith("magnet:?", ignoreCase = true)) return null
            val params = link.substringAfter('?').split('&').mapNotNull { p ->
                val k = p.substringBefore('=', "").lowercase()
                if (k.isEmpty()) null else k to runCatching { URLDecoder.decode(p.substringAfter('='), "UTF-8") }.getOrElse { p.substringAfter('=') }
            }
            val xt = params.filter { it.first == "xt" }.map { it.second }
                .firstOrNull { it.startsWith("urn:btih:", ignoreCase = true) }?.substring(9) ?: return null
            val hash = when {
                hex.matches(xt) -> xt.lowercase()
                b32.matches(xt) -> base32ToHex(xt.uppercase())
                else -> return null
            }
            return Magnet(hash, params.firstOrNull { it.first == "dn" }?.second?.ifBlank { null },
                params.filter { it.first == "tr" }.map { it.second.trim() }.filter { it.isNotEmpty() }.distinct())
        }

        /** Rebuilds a magnet a torrent app can open, from the stored hash, the title and the source's trackers. */
        fun link(infoHash: String, name: String?, trackers: List<String>): String = buildString {
            append("magnet:?xt=urn:btih:").append(infoHash)
            name?.let { append("&dn=").append(enc(it)) }
            trackers.forEach { append("&tr=").append(enc(it)) }
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun base32ToHex(s: String): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var bits = 0L
            var n = 0
            val out = StringBuilder()
            for (c in s) {
                bits = (bits shl 5) or alphabet.indexOf(c).toLong()
                n += 5
                while (n >= 8) {
                    n -= 8
                    out.append("%02x".format(((bits shr n) and 0xff).toInt()))
                }
            }
            return out.toString()
        }
    }
}
