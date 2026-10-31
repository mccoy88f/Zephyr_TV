package link.mccoy88f.zephyr

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

// Scripts from elsewhere run in processes of their own, apart from the home screen and the player: Nuvio's plugins in
// ":plugins", extensions in ":extensions". A script that loops forever can't be stopped inside QuickJS, and a native
// crash would take its process with it: here that's only its own process, which is killed and started again.

/** Runs scripts (Js.run) for the app's other processes, asked through a Messenger: one service per process. */
internal abstract class ScriptService : Service() {
    private val worker = Executors.newCachedThreadPool()
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper()) { m -> if (m.what == CALL) answer(m); true }) }

    override fun onCreate() {
        super.onCreate()
        Js.useAssets(applicationContext) // the modules scripts require()
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    /** The services a script asks Zephyr TV for by name (see Js.run), for the call from [dir] (an extension's folder). */
    open fun services(dir: String?): Map<String, suspend (org.json.JSONObject) -> Any?> = emptyMap()

    private fun answer(m: Message) {
        val reply = m.replyTo ?: return
        val id = m.arg1
        val data = Bundle(m.data)
        worker.execute {
            val out = Bundle()
            try {
                val script = data.getString(SCRIPT) ?: data.getString(SCRIPT_FILE)?.let { File(it).readText() } ?: ""
                var store: String? = null
                val result = Js.run(script, data.getString(CALL_JS).orEmpty(), data.getString(GLOBALS).orEmpty(), data.getLong(TIMEOUT),
                    stored = if (data.getBoolean(WANTS_STORE)) { s -> store = s } else null, services = services(data.getString(DIR)))
                putText(out, OUT, result)
                out.putString(STORE, store)
            } catch (e: Throwable) {
                out.putString(ERROR, e.toString())
            }
            runCatching { reply.send(Message.obtain(null, ANSWER, id, android.os.Process.myPid()).apply { this.data = out }) }
        }
    }

    /** [text] into [b] as [key], or, too large for a Binder call, in a file of the cache (KEY_FILE) the caller removes. */
    private fun putText(b: Bundle, key: String, text: String?) {
        if (text == null || text.length < INLINE) return b.putString(key, text)
        val f = File(File(cacheDir, "scripts").apply { mkdirs() }, UUID.randomUUID().toString())
        f.writeText(text)
        b.putString(key + FILE, f.path)
    }

    companion object {
        const val CALL = 1
        const val ANSWER = 2
        const val SCRIPT = "script"
        const val SCRIPT_FILE = "scriptFile"
        const val CALL_JS = "call"
        const val GLOBALS = "globals"
        const val TIMEOUT = "timeout"
        const val WANTS_STORE = "wantsStore"
        const val DIR = "dir"
        const val OUT = "out"
        const val STORE = "store"
        const val ERROR = "error"
        const val FILE = "File"
        const val INLINE = 200_000 // characters: past this, a script or an answer goes through a file (Binder's limit is 1 MB a call)
    }
}

/** Nuvio's plugins' process (":plugins"). */
internal class PluginService : ScriptService()

/** The extensions' process (":extensions"), with Zephyr TV's services for them (zephyr.* in the standard). */
internal class ExtensionService : ScriptService() {
    override fun services(dir: String?): Map<String, suspend (org.json.JSONObject) -> Any?> {
        val root = dir?.let { File(it) } ?: return emptyMap()
        // Its data folder: files/extension-data/<id>, next to files/extensions/<id> (its package).
        val data = Extensions.dataDir(root.parentFile!!.parentFile!!, root.name)
        fun headers(o: org.json.JSONObject?) = o?.keys()?.asSequence()?.associateWith { o.optString(it) }.orEmpty()
        return mapOf(
            "epg.now" to { args ->
                val urls=runCatching { org.json.JSONArray(File(data,"guides.json").readText()) }.getOrNull()
                var found: Epg.Show?=null
                if(urls!=null) for(i in 0 until urls.length()) {
                    val guide=Epg.saved(root.parentFile!!.parentFile!!,urls.optString(i),own=false) ?: continue
                    found=Epg.nowAndNext(guide,args.optString("name"),id=args.optString("id").ifBlank { null })?.first
                    if(found!=null)break
                }
                found?.let { org.json.JSONObject().put("title",it.title).put("start",it.start).put("stop",it.stop) }
            },
            "match.poster" to { args -> MatchPoster.create(data,args) },
            // zephyr.asset(path): a file of the extension's package, as text.
            "asset" to { args -> Extensions.safe(root, args.optString("path"))?.takeIf { it.isFile }?.readText() ?: throw IOException("no file ${args.optString("path")}") },
            // zephyr.m3u: playlists read here, kept in its data folder (see M3u).
            "m3u.load" to { args ->
                val text = args.optString("text").takeIf { it.isNotEmpty() }
                val url = args.optString("url").takeIf { it.startsWith("http") } ?: if (text != null) "" else throw IOException("no playlist address")
                M3u.load(data, args.optString("name"), url, headers(args.optJSONObject("headers")), args.optLong("maxAge", 86_400), text)
            },
            "m3u.channels" to { args ->
                M3u.channels(data, args.optString("name"), args.optString("group").ifEmpty { null }, args.optString("search").ifEmpty { null },
                    args.optInt("skip", 0), args.optInt("limit", 100), args.optString("kind").ifEmpty { null })
            },
            "m3u.channel" to { args -> M3u.channel(data, args.optString("name"), args.optString("id")) },
            "m3u.forget" to { args -> M3u.forget(data, args.optString("name")); null },
        )
    }
}

/**
 * The way to a [ScriptService] in its own process ([process], its name after the colon): calls wait for its answer, at
 * most their time and a margin; past that, the process is killed (a script looping forever) and started again by the
 * next call. Unbound when idle, so Android can let it go. Calls: off the UI thread.
 */
internal class ScriptProcess(context: Context, private val service: Class<out ScriptService>, private val process: String) {
    private val app = context.applicationContext
    private val lock = Any()
    @Volatile private var remote: Messenger? = null
    private var connection: ServiceConnection? = null
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Bundle>>()
    private val next = AtomicInteger()
    private val thread = HandlerThread("scripts-$process").apply { start() }
    private val handler = Handler(thread.looper)
    private val replies = Messenger(Handler(thread.looper) { m ->
        if (m.what == ScriptService.ANSWER) pending.remove(m.arg1)?.complete(Bundle(m.data))
        true
    })
    private val release = Runnable { if (pending.isEmpty()) disconnect() }

    /** What a call answered: [out] the value (JSON), [store] its localStorage when asked for. */
    class Answer(val out: String?, val store: String?)

    /**
     * Runs [script], then [call] (see Js.run) in the process. Throws when the script fails, the process can't be
     * reached, or it didn't answer in [timeoutMs] and a margin (it's then killed). Null when the script ran out of time.
     */
    fun run(script: String, call: String, globals: String, timeoutMs: Long, wantsStore: Boolean = false, dir: File? = null): Answer {
        check(Looper.myLooper() != Looper.getMainLooper()) { "scripts: not on the UI thread" }
        handler.removeCallbacks(release)
        val messenger = connect() ?: throw IOException("$process: can't be started")
        val id = next.incrementAndGet()
        val answer = CompletableFuture<Bundle>()
        pending[id] = answer
        var scriptFile: File? = null
        val data = Bundle().apply {
            if (script.length < ScriptService.INLINE) putString(ScriptService.SCRIPT, script)
            else {
                scriptFile = File(File(app.cacheDir, "scripts").apply { mkdirs() }, UUID.randomUUID().toString() + ".js").also { it.writeText(script) }
                putString(ScriptService.SCRIPT_FILE, scriptFile!!.path)
            }
            putString(ScriptService.CALL_JS, call)
            putString(ScriptService.GLOBALS, globals)
            putLong(ScriptService.TIMEOUT, timeoutMs)
            putBoolean(ScriptService.WANTS_STORE, wantsStore)
            putString(ScriptService.DIR, dir?.path)
        }
        try {
            messenger.send(Message.obtain(null, ScriptService.CALL, id, 0).apply { this.data = data; replyTo = replies })
            val got = try {
                answer.get(timeoutMs + MARGIN_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                restart()
                throw IOException("$process: no answer in ${timeoutMs / 1000} s, started again")
            }
            got.getString(ScriptService.ERROR)?.let { throw IOException(it) }
            val out = got.getString(ScriptService.OUT) ?: got.getString(ScriptService.OUT + ScriptService.FILE)?.let { path ->
                File(path).let { f -> f.readText().also { f.delete() } }
            }
            return Answer(out, got.getString(ScriptService.STORE))
        } finally {
            pending.remove(id)
            scriptFile?.delete()
            handler.postDelayed(release, IDLE_MS)
        }
    }

    private fun connect(): Messenger? {
        synchronized(lock) {
            remote?.let { return it }
            val ready = CountDownLatch(1)
            val c = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    remote = Messenger(binder)
                    ready.countDown()
                }
                // Its process died (a crash, or killed): what it was doing fails; the next call binds again.
                override fun onServiceDisconnected(name: ComponentName) = lost("$process: stopped")
                override fun onBindingDied(name: ComponentName) = lost("$process: stopped")
            }
            if (!app.bindService(Intent(app, service), c, Context.BIND_AUTO_CREATE)) {
                runCatching { app.unbindService(c) }
                return null
            }
            connection = c
            ready.await(CONNECT_S, TimeUnit.SECONDS)
            return remote
        }
    }

    private fun lost(why: String) {
        disconnect()
        val e = Bundle().apply { putString(ScriptService.ERROR, why) }
        pending.values.forEach { it.complete(e) }
    }

    private fun disconnect() {
        synchronized(lock) {
            connection?.let { runCatching { app.unbindService(it) } }
            connection = null
            remote = null
        }
    }

    /** Kills the process (a script that won't stop), so the next call starts it again. */
    private fun restart() {
        val name = app.packageName + ":" + process
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.firstOrNull { it.processName == name }?.let { android.os.Process.killProcess(it.pid) }
        lost("$process: started again")
        warn("$process process didn't answer: started again")
    }

    companion object {
        private const val MARGIN_MS = 5_000L // a script's own time limit, then this, before its process is killed
        private const val IDLE_MS = 120_000L // unused this long, it's let go
        private const val CONNECT_S = 10L

        @Volatile private var pluginsProcess: ScriptProcess? = null
        @Volatile private var extensionsProcess: ScriptProcess? = null

        /** The plugins' process, one for each of the app's processes (the home screen's, the player's). */
        fun plugins(context: Context) = pluginsProcess ?: synchronized(this) {
            pluginsProcess ?: ScriptProcess(context, PluginService::class.java, "plugins").also { pluginsProcess = it }
        }

        /** The extensions' process. */
        fun extensions(context: Context) = extensionsProcess ?: synchronized(this) {
            extensionsProcess ?: ScriptProcess(context, ExtensionService::class.java, "extensions").also { extensionsProcess = it }
        }
    }
}
