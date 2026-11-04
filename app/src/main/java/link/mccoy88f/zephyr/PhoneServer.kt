package link.mccoy88f.zephyr

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

internal const val TEXT = "text/plain; charset=utf-8"
internal const val HTML = "text/html; charset=utf-8"
internal const val JSON = "application/json; charset=utf-8"

/**
 * One request from a phone: its [method], its [path] without the query, and its [headers] by lowercase name.
 * The body is read at most once, with [body] or [saveBody]; the answer is sent once, with [reply].
 */
internal class PhoneRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val from: InetAddress,
    private val input: InputStream,
    private val out: OutputStream,
) {
    /** What the body says it holds; -1 when it doesn't say. */
    val length get() = headers["content-length"]?.toLongOrNull() ?: -1

    /** The body as text, when it is at most [max] bytes; null when it is longer or cut short. */
    fun body(max: Long): String? {
        val n = length.coerceAtLeast(0)
        if (n > max) return null
        val buf = ByteArray(n.toInt())
        var got = 0
        while (got < buf.size) {
            val r = input.read(buf, got, buf.size - got)
            if (r < 0) return null
            got += r
        }
        return buf.decodeToString()
    }

    /** The body into [file], when it is 1 to [max] bytes. False when it doesn't fit. Throws when it is cut short. */
    fun saveBody(max: Long, file: File): Boolean {
        val n = length
        if (n !in 1..max) return false
        file.outputStream().use { f ->
            val buf = ByteArray(64 * 1024)
            var left = n
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) throw IOException("upload cut short")
                f.write(buf, 0, r)
                left -= r
            }
        }
        return true
    }

    /** The answer: [status], [type] and [body]; [extra] are more header lines, each ending in \r\n. */
    fun reply(status: Int, type: String = TEXT, body: String = "", extra: String = "") {
        val bytes = body.toByteArray()
        out.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    /**
     * [task] answers: GET, its page; POST, what the phone sent, as a temporary file in [temp] (deleted after),
     * to [PhoneTask.onSend]. The status sent.
     */
    fun serve(task: PhoneTask, temp: File): Int {
        if (method == "GET") return 200.also { reply(it, HTML, task.page.html) }
        if (method != "POST" || task.maxBytes < 1) return 405.also { reply(it, TEXT, "Not allowed") }
        val file = File.createTempFile("phone", ".part", temp)
        try {
            if (!saveBody(task.maxBytes, file)) return 413.also { reply(it, TEXT, "Too big.") }
            val (status, text) = task.onSend(file)
            reply(status, TEXT, text)
            return status
        } finally {
            file.delete()
        }
    }

    companion object {
        /** The request on [s], or null when the connection closed first. */
        fun read(s: Socket): PhoneRequest? {
            val input = BufferedInputStream(s.getInputStream())
            val line = readLine(input) ?: return null
            val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
                .take(100)
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val path = line.substringAfter(' ').substringBefore(' ').substringBefore('?')
            return PhoneRequest(line.substringBefore(' '), path, headers, s.inetAddress, input, s.getOutputStream())
        }

        /** One header line, without the line break; null at the end. Capped, so junk can't fill memory. */
        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (sb.length < 8192) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
            }
            throw IOException("header line too long")
        }
    }
}

/**
 * Zephyr TV's one web server for phones on the home network: remote access and every page a phone opens (see
 * Remote). A few requests at once, each on a worker, so one slow or silent connection can't hold up the phone.
 * [handle] answers each request; what it throws is logged and the connection closes.
 */
internal class PhoneServer private constructor(private val socket: ServerSocket, name: String, private val handle: (PhoneRequest) -> Unit) {
    private val workers = Executors.newFixedThreadPool(4)
    val port: Int get() = socket.localPort
    val running get() = !socket.isClosed

    init {
        Thread(::serve, name).start()
    }

    fun stop() {
        try {
            socket.close()
        } catch (e: IOException) {
        }
        workers.shutdownNow()
    }

    private fun serve() {
        while (!socket.isClosed) {
            val s = try {
                socket.accept()
            } catch (e: IOException) {
                return // stopped
            }
            try {
                workers.execute {
                    try {
                        s.use {
                            it.soTimeout = 30_000
                            PhoneRequest.read(it)?.let(handle)
                        }
                    } catch (e: Exception) {
                        warn("phone request failed ($e)")
                    }
                }
            } catch (e: RejectedExecutionException) {
                s.close() // stopping
            }
        }
    }

    companion object {
        /** A server on [port] when it is free, so a saved address keeps working; on any free port when it isn't. */
        fun start(port: Int, name: String, handle: (PhoneRequest) -> Unit): PhoneServer? {
            fun bind(p: Int) = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(p))
            }
            val socket = try {
                bind(port)
            } catch (e: IOException) {
                try {
                    bind(0)
                } catch (e2: IOException) {
                    warn("phone server didn't start ($e2)")
                    return null
                }
            }
            return PhoneServer(socket, name, handle)
        }
    }
}
