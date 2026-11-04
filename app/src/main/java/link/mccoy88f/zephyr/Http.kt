package link.mccoy88f.zephyr

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Zephyr TV's one way to the internet: the same timeouts everywhere (a TV on a slow Wi-Fi shouldn't hang a thread for
 * minutes), and a clear failure for anything but a 2xx answer. Network: call it off the UI thread.
 */
internal object Http {
    init {
        // Java's HttpURLConnection drops Origin (and a few others). InnerTube needs Origin with SAPISIDHASH.
        runCatching { System.setProperty("sun.net.http.allowRestrictedHeaders", "true") }
    }

    private const val TIMEOUT_MS = 6000

    /**
     * For an add-on's streams: an aggregator asks every add-on of its own before it answers, often
     * past 6 s; the first time for a title, past 10.
     */
    const val STREAMS_TIMEOUT_MS = 20_000
    private const val USER_AGENT = "ZephyrTV/1 (Android TV launcher; https://github.com/mccoy88f/Zephyr_TV)"

    /**
     * [agent]: who's asking, as Wikimedia (most channel logos) wants: Android's anonymous default gets throttled there.
     * Null leaves Android's own, as the player sends (see Stremio.probe). [connect]: how long to wait to connect.
     */
    fun connect(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = TIMEOUT_MS,
                agent: String? = USER_AGENT, connect: Int = TIMEOUT_MS): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connect
            readTimeout = timeout
            agent?.let { setRequestProperty("User-Agent", it) }
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    /**
     * A film's or episode's stream: a debrid service's link goes through the add-on's resolver first, which asks
     * the service for the file before it answers, often past the 6 s a channel gets. At most this long: a stream
     * that answers sooner starts at once, and one that doesn't answer at all costs no more than this.
     */
    const val VOD_STREAM_TIMEOUT_MS = 15_000
    const val STREAM_CONNECT_MS = 10_000

    /** The body of [url]; throws on a network error or a non-2xx answer. */
    fun open(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = TIMEOUT_MS): InputStream = connect(url, headers, timeout).inputStream

    /** A page, a list, a JSON answer: none is bigger than this; more is a server gone wrong (or a stream). */
    const val MAX_TEXT = 16L * 1024 * 1024

    /** An image: a backdrop is a few hundred KB, a poster less. */
    const val MAX_IMAGE = 12L * 1024 * 1024

    fun text(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = TIMEOUT_MS) =
        open(url, headers, timeout).use { it.readCapped(MAX_TEXT).decodeToString() }

    fun json(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = TIMEOUT_MS) =
        JSONObject(text(url, headers + ("Accept" to "application/json"), timeout))

    /**
     * Sends [body] (JSON) to [url] with [method] (POST, or DELETE without a body); the answer's status and body,
     * whatever the status (an API telling "not yet" or "not found" by it). Throws on a network error only.
     */
    fun send(url: String, body: JSONObject?, headers: Map<String, String> = emptyMap(), method: String = "POST"): Pair<Int, String> {
        val c = connect(url, headers + ("Accept" to "application/json") + ("Content-Type" to "application/json"))
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readCapped(MAX_TEXT).decodeToString() }.orEmpty()
        return code to text
    }
}

/**
 * All of the stream, up to [max] bytes; past them it throws. What add-ons, other apps' listings and the web hand
 * over isn't trusted to end: an endless or huge answer would take the home screen's memory (or the disk) with it.
 */
internal fun InputStream.readCapped(max: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    copyCapped(out, max)
    return out.toByteArray()
}

/** Copies the stream to [out], up to [max] bytes; past them it throws. */
internal fun InputStream.copyCapped(out: java.io.OutputStream, max: Long) {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) return
        total += n
        if (total > max) throw java.io.IOException("more than ${max / 1024 / 1024} MB")
        out.write(buf, 0, n)
    }
}

/**
 * [task] for each of [items], [threads] at a time, waiting [seconds] at most for them all: the answers that came,
 * in [items]' order. A task that fails, answers null or is still running by then is left out.
 */
internal fun <T, R : Any> parallel(items: List<T>, threads: Int, seconds: Long, task: (T) -> R?): List<R> {
    if (items.isEmpty()) return emptyList()
    val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(threads, items.size))
    return try {
        pool.invokeAll(items.map { java.util.concurrent.Callable { runCatching { task(it) }.getOrNull() } }, seconds, java.util.concurrent.TimeUnit.SECONDS)
            .mapNotNull { runCatching { it.get() }.getOrNull() }
    } finally {
        pool.shutdownNow()
    }
}
