package link.mccoy88f.zephyr.torrent

import java.net.URLDecoder

/**
 * A magnet link as Zephyr TV hands it: the torrent's info hash (xt=urn:btih:), its trackers (tr=), its name (dn=) and
 * the file to play or save (so=, BEP 53's "select only": one index here). Read without android.net.Uri, so the
 * tests run off a TV.
 */
internal class Magnet(val hash: String, val trackers: List<String>, val name: String?, val file: Int?) {
    companion object {
        fun parse(link: String): Magnet? {
            if (!link.startsWith("magnet:?", ignoreCase = true)) return null
            val params = link.substringAfter('?').split('&').mapNotNull { p ->
                val k = p.substringBefore('=', "").lowercase()
                if (k.isEmpty()) null else k to runCatching { URLDecoder.decode(p.substringAfter('='), "UTF-8") }.getOrDefault(p.substringAfter('='))
            }
            val hash = params.firstOrNull { (k, v) -> k == "xt" && v.startsWith("urn:btih:", ignoreCase = true) }
                ?.second?.substring(9)?.let { hex(it) } ?: return null
            return Magnet(hash, params.filter { it.first == "tr" }.map { it.second }.distinct(),
                params.firstOrNull { it.first == "dn" }?.second, params.firstOrNull { it.first == "so" }?.second?.substringBefore('-')?.substringBefore(',')?.toIntOrNull())
        }

        /** The hash as 40 hex digits, lower case: as it is, or from base32 (32 letters, as older links write it). */
        internal fun hex(s: String): String? {
            if (Regex("[0-9a-fA-F]{40}").matches(s)) return s.lowercase()
            if (!Regex("[A-Za-z2-7]{32}").matches(s)) return null
            var bits = 0L
            var n = 0
            val out = StringBuilder()
            for (c in s.uppercase()) {
                bits = (bits shl 5) or (if (c in 'A'..'Z') c - 'A' else c - '2' + 26).toLong()
                n += 5
                if (n >= 8) {
                    n -= 8
                    out.append("%02x".format(((bits shr n) and 0xff).toInt()))
                }
            }
            return out.toString()
        }
    }
}
