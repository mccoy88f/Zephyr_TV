package link.mccoy88f.zephyr

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The streams each source found for a film, an episode or a channel, kept a little while on disk for both processes:
 * the launcher looks for them ahead (a title's page open, a channel's card picked: see warm), the player takes them
 * from here when it starts, and looks only for those missing. One still being looked for (by either) is waited for,
 * not asked again. Network and disk: off the UI thread, but [fresh].
 */
internal object StreamCache {
    const val FRESH_MS = 90_000L // their addresses can expire
    private const val WAIT_MS = 30_000L // a search going on elsewhere: waited for at most this long
    private const val POLL_MS = 200L
    private const val KEEP_MS = 10 * 60_000L // older files are let go
    private const val DIR = "streams"

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "streams").apply { isDaemon = true } }

    private fun key(base: String, type: String, id: String) =
        MessageDigest.getInstance("SHA-1").digest("$base|$type|$id".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun folder(cache: File) = File(cache, DIR).apply { mkdirs() }

    /** [base]'s streams for this item when found less than FRESH_MS ago, else null. */
    fun fresh(cache: File, base: String, type: String, id: String): List<Stremio.Stream>? = runCatching {
        val o = JSONObject(File(folder(cache), key(base, type, id) + ".json").readText())
        if (System.currentTimeMillis() - o.getLong("at") >= FRESH_MS) return null
        val a = o.getJSONArray("streams")
        (0 until a.length()).map { PlayerActivity.streamOf(a.getJSONObject(it)) }
    }.getOrNull()

    /**
     * [base]'s streams for this item: kept ones while fresh; else those of a search going on elsewhere, once done;
     * else [find]'s, kept. Throws as [find] does (nothing kept then).
     */
    fun get(cache: File, base: String, type: String, id: String, find: () -> List<Stremio.Stream>): List<Stremio.Stream> {
        fresh(cache, base, type, id)?.let { return it }
        val dir = folder(cache)
        val k = key(base, type, id)
        val wait = File(dir, "$k.wait")
        val since = wait.lastModified()
        if (since > 0 && System.currentTimeMillis() - since < WAIT_MS) {
            while (wait.exists() && System.currentTimeMillis() - since < WAIT_MS) Thread.sleep(POLL_MS)
            fresh(cache, base, type, id)?.let { return it }
        }
        runCatching { wait.writeText("") }
        try {
            val list = find()
            runCatching {
                val now = System.currentTimeMillis()
                dir.listFiles()?.forEach { if (now - it.lastModified() > KEEP_MS) it.delete() }
                val o = JSONObject().put("at", now).put("streams", JSONArray(list.map { PlayerActivity.streamJson(it) }))
                val part = File(dir, "$k.part")
                part.writeText(o.toString())
                part.renameTo(File(dir, "$k.json"))
            }
            return list
        } finally {
            wait.delete()
        }
    }

    /** [block] off the UI thread, on the searches' threads. */
    fun later(block: () -> Unit) = pool.execute { runCatching(block) }

    /**
     * Each of [sources]' streams for this item looked for ahead, all at once ([find] asks one); [done] (off the UI
     * thread) with how many each has, once all have answered.
     */
    fun warm(cache: File, sources: List<String>, type: String, id: String, find: (String) -> List<Stremio.Stream>,
             done: ((Map<String, Int>) -> Unit)? = null) {
        if (sources.isEmpty()) return done?.invoke(emptyMap()) ?: Unit
        val left = AtomicInteger(sources.size)
        val counts = ConcurrentHashMap<String, Int>()
        for (b in sources) pool.execute {
            counts[b] = runCatching { Torrents.offered(get(cache, b, type, id) { find(b) }).size }.getOrDefault(0) // torrents: with the engine
            if (left.decrementAndGet() == 0) done?.invoke(counts.toMap())
        }
    }
}
