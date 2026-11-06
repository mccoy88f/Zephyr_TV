package link.mccoy88f.zephyr.torrent

import android.util.Log
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * The file being played, served to Zephyr TV's player over http on 127.0.0.1 only, at /<token>/<name>: the token is
 * random for each start, so another app on the TV can't read it by guessing the port. Byte ranges, as players ask
 * them to seek; each read waits for its pieces, which the engine asks for first (see Engine.Play.waitFor).
 */
internal class StreamServer(private val find: (String) -> Engine.Play?) {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    init {
        Thread({
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: continue
                Thread({ serve(c) }, "torrent-http").apply { isDaemon = true }.start()
            }
        }, "torrent-server").apply { isDaemon = true }.start()
    }

    private fun serve(c: Socket): Unit = c.use {
        try {
            c.soTimeout = REQUEST_TIMEOUT_MS
            val input = c.getInputStream()
            val first = line(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val l = line(input) ?: return
                if (l.isEmpty()) break
                headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':', "").trim()
            }
            val parts = first.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty()
            val out = BufferedOutputStream(c.getOutputStream(), 64 * 1024)
            val play = find(path.trimStart('/').substringBefore('/'))
            if (play == null || (method != "GET" && method != "HEAD")) return answer(out, if (play == null) 404 else 405)
            val size = play.size
            val range = range(headers["range"], size)
            if (range == null && headers["range"] != null) {
                return answer(out, 416, "Content-Range: bytes */$size\r\n")
            }
            val (from, to) = range ?: (0L to size - 1)
            val head = StringBuilder()
            head.append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            head.append("Content-Type: ").append(play.mime).append("\r\n")
            head.append("Accept-Ranges: bytes\r\n")
            head.append("Content-Length: ").append(to - from + 1).append("\r\n")
            if (range != null) head.append("Content-Range: bytes $from-$to/$size\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (method == "HEAD") return out.flush()
            out.flush()
            c.soTimeout = 0
            send(play, out, from, to)
        } catch (_: SocketException) {
            // The player closed it (a seek, or it stopped): nothing to say.
        } catch (e: Exception) {
            Log.w(TAG, "stream request failed: $e")
        }
    }

    /** Bytes [from]..[to] of the file, each piece once it's here. */
    private fun send(play: Engine.Play, out: OutputStream, from: Long, to: Long) {
        val buf = ByteArray(64 * 1024)
        play.reading(from)
        // Its first piece here first: libtorrent makes the file only as it writes into it.
        if (!play.waitFor(from)) return
        RandomAccessFile(play.file(), "r").use { f ->
            var pos = from
            while (pos <= to) {
                if (!play.waitFor(pos)) return // stopped, or nothing came for too long: the player tries again or the next
                val end = minOf(to + 1, play.pieceEnd(pos))
                f.seek(pos)
                while (pos < end) {
                    val n = f.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
                    if (n <= 0) return
                    out.write(buf, 0, n)
                    pos += n
                    play.reading(pos)
                }
                out.flush()
            }
        }
    }

    private fun answer(out: OutputStream, code: Int, extra: String = "") {
        val text = when (code) { 404 -> "Not Found"; 405 -> "Method Not Allowed"; 416 -> "Range Not Satisfiable"; else -> "Error" }
        out.write("HTTP/1.1 $code $text\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    fun close() = runCatching { socket.close() }

    companion object {
        private const val TAG = "ZephyrTorrent"
        private const val REQUEST_TIMEOUT_MS = 10_000

        /** One header line, without its CR LF; null at the end. */
        private fun line(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                if (sb.length > 8192) return null
                sb.append(b.toChar())
            }
        }

        /** "bytes=a-b", "bytes=a-" or "bytes=-n" against [size]: the first and last byte, or null when it can't be had. */
        internal fun range(header: String?, size: Long): Pair<Long, Long>? {
            val spec = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) }?.substring(6)?.substringBefore(',')?.trim() ?: return null
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-', "").trim()
            val (from, to) = when {
                a.isEmpty() -> (size - (b.toLongOrNull() ?: return null)).coerceAtLeast(0) to size - 1
                else -> (a.toLongOrNull() ?: return null) to (b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1))
            }
            return if (from > to || from >= size) null else from to to
        }
    }
}
