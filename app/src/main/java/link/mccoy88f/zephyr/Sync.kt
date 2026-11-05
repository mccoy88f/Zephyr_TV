package link.mccoy88f.zephyr

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Merging what changed here and on the account since the last sync ([base], as both had it then): a change on one
 * side is kept, a removal on one side too (a title unmarked as watched doesn't come back); changed on both sides,
 * [prefer] decides. Values are compared by content, whatever the order of their fields.
 */
internal object SyncMerge {
    fun merge(base: Map<String, JSONObject>, local: Map<String, JSONObject>, remote: Map<String, JSONObject>,
              prefer: (local: JSONObject, remote: JSONObject) -> JSONObject): Map<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        for (k in local.keys + remote.keys) {
            val l = local[k]
            val r = remote[k]
            val b = base[k]
            val v = when {
                l != null && r != null -> when {
                    same(l, r) -> l
                    b != null && same(l, b) -> r // changed there only
                    b != null && same(r, b) -> l // changed here only
                    else -> prefer(l, r)
                }
                l != null -> if (b != null && same(l, b)) null else l // removed there; or new (or changed) here
                r != null -> if (b != null && same(r, b)) null else r // removed here; or new (or changed) there
                else -> null
            }
            if (v != null) out[k] = v
        }
        return out
    }

    /** The newer of two entries by their "at" (when watched, or got to); [local] when the same. */
    fun newer(local: JSONObject, remote: JSONObject) = if (remote.optLong("at") > local.optLong("at")) remote else local

    fun same(a: JSONObject, b: JSONObject) = canon(a) == canon(b)

    fun sameMaps(a: Map<String, JSONObject>, b: Map<String, JSONObject>) = a.keys == b.keys && a.all { (k, v) -> same(v, b.getValue(k)) }

    /** [v] as text with its objects' fields in order: the same content, the same text. */
    fun canon(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { "${JSONObject.quote(it)}:${canon(v.opt(it))}" }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.opt(it)) }
        is String -> JSONObject.quote(v)
        null, JSONObject.NULL -> "null"
        is Number -> if (v.toDouble() == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    /** The settings that are lists, one item a line: joined, not replaced, when two configurations are merged. */
    val LINE_LISTS = setOf(K.ADDONS, K.CATALOG_SOURCES, K.CATALOGS)

    /**
     * Two configurations merged (Merge, at the first sync): where both have a setting, the account's; but lists are
     * joined (sets, and the settings of [LINE_LISTS]: the account's items first, then this TV's others) and JSON objects
     * too (the account's entries over this TV's), so no add-on or catalog of either is lost.
     */
    fun mergeSettings(local: Map<String, JSONObject>, remote: Map<String, JSONObject>): Map<String, JSONObject> {
        val out = LinkedHashMap(local)
        for ((k, r) in remote) {
            val l = local[k]
            out[k] = when {
                l == null -> r
                l.has("set") && r.has("set") -> {
                    val all = LinkedHashSet<String>()
                    for (a in listOf(r.getJSONArray("set"), l.getJSONArray("set"))) for (i in 0 until a.length()) all += a.getString(i)
                    JSONObject().put("set", JSONArray(all.sorted()))
                }
                k in LINE_LISTS && l.has("s") && r.has("s") ->
                    JSONObject().put("s", (r.getString("s").lines() + l.getString("s").lines()).filter { it.isNotBlank() }.distinct().joinToString("\n"))
                l.has("s") && r.has("s") && r.getString("s").trimStart().startsWith("{") && l.getString("s").trimStart().startsWith("{") -> {
                    val joined = runCatching {
                        val o = JSONObject(l.getString("s"))
                        val ro = JSONObject(r.getString("s"))
                        for (rk in ro.keys()) o.put(rk, ro.get(rk))
                        JSONObject().put("s", o.toString())
                    }.getOrNull()
                    joined ?: r
                }
                else -> r
            }
        }
        return out
    }

    fun toMap(o: JSONObject?): Map<String, JSONObject> =
        o?.keys()?.asSequence()?.mapNotNull { k -> o.optJSONObject(k)?.let { k to it } }?.toMap().orEmpty()

    fun toJson(m: Map<String, JSONObject>) = JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }
}

/**
 * Keeping this TV in step with the Zephyr TV account it's linked to (see ZtvAccount): its users and their avatars,
 * the account's settings, and for each user what they watched and where they got to. Each kept as the site last
 * had it (in files/sync), to merge with (see SyncMerge). What stays with each TV: its PIN and locks, its apps' order
 * and names, its TV inputs, its screensaver folder, and for now the extensions and Nuvio plugins (packages each TV
 * installs). Network: off the UI thread.
 */
internal object Sync {
    /** What changed here: the settings (the home screen is built again), the users, what they watched. */
    class Result(val settings: Boolean, val users: Boolean, val data: Boolean)

    /**
     * A TV's first sync with an account that has things already, as chosen when linking it: the account's
     * configuration here, this TV's removed ([CLOUD]); this TV's on the account, the account's removed ([DEVICE]); or
     * both ([MERGE]: the account's main user leads, every other user here added, lists joined).
     */
    enum class First { CLOUD, DEVICE, MERGE }

    /** Whether the account (as [state] has it) has things already: users, or settings. Then the first sync asks. */
    fun hasData(state: JSONObject) =
        (state.optJSONArray("users")?.length() ?: 0) > 0 || (state.optJSONObject("docs")?.optInt("settings") ?: 0) > 0

    private val LOCAL_ONLY = setOf(
        K.USERS, K.USER, K.SORT, K.ORDER, K.ROW_HIDDEN, K.ROW_ALIGN, K.NAMES, K.HIDDEN_WATCH,
        K.LOCK_ON, K.ADULT_ON, K.ADULT_FOREVER, K.ADULT_APP, K.ADULT_PLACE,
        K.SS_FOLDER, K.TV_APP, K.INPUTS_MODE, K.INPUTS_PINNED, K.INPUTS_IN_APPS,
        K.EXT_ORDER, K.EXT_OFF, K.EXT_ADULT, K.EXT_SOURCES, K.EXT_REPOS, K.EXT_COLLECT, K.EXT_OWN, K.EXT_APP, K.EXT_OPTIONS,
        K.NUVIO_REPOS_OFF, K.NUVIO_REPOS, K.NUVIO_ON, K.NUVIO_OFF, K.NUVIO_ADULT, K.NUVIO_OPTIONS, K.NUVIO_ORDER,
    )

    /**
     * The settings the account keeps: a backup's, but those of this TV only; and which users have Trakt on some TV
     * (their Trakt name only, never its keys: each TV connects on its own, see offerTrakt).
     */
    val KEYS: Set<String> = Backup.KEYS - LOCAL_ONLY + K.TRAKT_ACCOUNTS

    /** The settings that build the home screen: all but who has Trakt (that one changing builds nothing again). */
    private fun shown(a: MainActivity) = a.prefs.all.filterKeys { it in KEYS && it != K.TRAKT_ACCOUNTS }.mapValues { it.value.toString() }

    /**
     * This TV's users with Trakt connected, added to those the account knows (K.TRAKT_ACCOUNTS): the other TVs then
     * offer to connect it there too. A user's entry goes when Trakt is disconnected on a TV (see traktGone); a TV that
     * still has it puts it back at its next sync.
     */
    private fun noteTrakt(a: MainActivity) {
        val known = runCatching { JSONObject(a.prefs.getString(K.TRAKT_ACCOUNTS, null) ?: "{}") }.getOrDefault(JSONObject())
        var changed = false
        for (u in a.users()) {
            val sid = u.sid ?: continue
            val name = Trakt.account(a.vodDir(u.id))?.user ?: continue
            if (!known.has(sid) || known.optString(sid) != name) { known.put(sid, name); changed = true }
        }
        if (changed) a.prefs.edit().putString(K.TRAKT_ACCOUNTS, known.toString()).commit()
    }

    internal val running = AtomicBoolean(false)
    @Volatile internal var last = 0L
    @Volatile internal var recreatePending = false
    internal var pending: Runnable? = null
    internal var tick: Runnable? = null

    /** One sync, all of it. Throws offline, or ZtvAccount.Unlinked. */
    fun run(a: MainActivity, token: String, first: First? = null): Result {
        val state = ZtvAccount.state(token)
        state.optString("email").takeIf { it.isNotBlank() }?.let { a.prefs.edit().putString(K.ZTV_EMAIL, it).apply() }
        val revs = state.optJSONObject("docs") ?: JSONObject()
        val users = syncUsers(a, token, state.optJSONArray("users") ?: JSONArray(), first)
        noteTrakt(a)
        val shownBefore = shown(a)
        val synced = syncDoc(token, "settings", revs, File(a.syncDir(), "settings.json"), first) { merge ->
            val local = SyncMerge.toMap(JSONObject().apply {
                for ((k, v) in a.prefs.all) if (k in KEYS) Backup.encode(v)?.let { put(k, it) }
            })
            val merged = merge(local) ?: return@syncDoc local
            val edit = a.prefs.edit()
            KEYS.forEach { if (it !in merged) edit.remove(it) }
            merged.forEach { (k, v) -> if (k in KEYS) Backup.put(edit, k, v) }
            edit.apply()
            merged
        }
        val settings = synced && shown(a) != shownBefore
        var data = false
        for (u in a.users()) {
            val sid = u.sid ?: continue
            val dir = a.vodDir(u.id)
            data = syncDoc(token, "watched:$sid", revs, File(a.syncDir(), "watched_$sid.json"), first) { VodWatched.sync(dir, it) } or data
            data = syncDoc(token, "progress:$sid", revs, File(a.syncDir(), "progress_$sid.json"), first) { VodProgress.sync(dir, it) } or data
        }
        a.prefs.edit().putLong(K.SYNC_AT, System.currentTimeMillis()).apply()
        return Result(settings, users, data)
    }

    /**
     * Document [key] in step: the account's (read again only when its revision moved on), this TV's (through [local],
     * which hands it to the merge and writes the merge's answer here when it differs), and the last agreed one in
     * [baseFile]. The merge written to the account when it differs from the account's; changed there meanwhile, again.
     * The first time (nothing agreed yet) the account's settings win where both have one. [first]: as chosen when
     * linking (see First): the account's, this TV's, or merged. Whether it changed here.
     */
    private fun syncDoc(token: String, key: String, revs: JSONObject, baseFile: File, first: First?,
                        local: (((Map<String, JSONObject>) -> Map<String, JSONObject>?) -> Map<String, JSONObject>)): Boolean {
        val saved = runCatching { JSONObject(baseFile.readText()) }.getOrNull()
        var rev = saved?.optInt("rev") ?: 0
        var base = SyncMerge.toMap(saved?.optJSONObject("data"))
        var remote = base
        if (first != null || revs.optInt(key, 0) != rev) {
            val (r, d) = ZtvAccount.doc(token, key)
            rev = r
            remote = SyncMerge.toMap(d)
            if (d == null) base = emptyMap() // none there (or unreadable): everything here goes up, nothing is taken as removed
        }
        if (first != null) base = emptyMap()
        val prefer: (JSONObject, JSONObject) -> JSONObject = when {
            key != "settings" -> SyncMerge::newer
            base.isEmpty() -> { _, r -> r }
            else -> { l, _ -> l }
        }
        val resolve: (Map<String, JSONObject>, Map<String, JSONObject>) -> Map<String, JSONObject> = when (first) {
            First.CLOUD -> { _, r -> r }
            First.DEVICE -> { mine, _ -> mine }
            First.MERGE -> if (key == "settings") SyncMerge::mergeSettings else { mine, r -> SyncMerge.merge(emptyMap(), mine, r, SyncMerge::newer) }
            null -> { mine, r -> SyncMerge.merge(base, mine, r, prefer) }
        }
        var changed = false
        repeat(3) {
            var merged: Map<String, JSONObject> = emptyMap()
            local { mine ->
                merged = resolve(mine, remote)
                if (SyncMerge.sameMaps(merged, mine)) null else merged.also { changed = true }
            }
            if (SyncMerge.sameMaps(merged, remote)) return changed.also { saveBase(baseFile, rev, merged) }
            val (newRev, now) = ZtvAccount.put(token, key, rev, SyncMerge.toJson(merged)) ?: return changed
            if (now == null) return changed.also { saveBase(baseFile, newRev, merged) }
            rev = newRev // changed there meanwhile: merged again with it
            remote = SyncMerge.toMap(now)
        }
        return changed
    }

    private fun saveBase(f: File, rev: Int, data: Map<String, JSONObject>) {
        f.parentFile?.mkdirs()
        val part = File(f.path + ".part")
        part.writeText(JSONObject().put("rev", rev).put("data", SyncMerge.toJson(data)).toString())
        part.renameTo(f)
    }

    /**
     * The users, here and on the account: all of this TV's, the main one (the first: sync is theirs) being the
     * account's first. Another user here new to the account: the account's user of the same name, or added there. Changed on one side since the last sync: the other side follows (both: the account's). Removed on
     * one side: removed on the other (here, with what they watched; the first user stays, and goes up again). On the
     * account and new here: added here. Whether it changed here.
     */
    private fun syncUsers(a: MainActivity, token: String, list: JSONArray, first: First?): Boolean {
        val server = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.associateBy { it.getString("id") }.toMutableMap()
        val snap = runCatching { JSONObject(a.prefs.getString(K.SYNC_USERS, null) ?: "{}") }.getOrDefault(JSONObject())
        val avatarOf = { o: JSONObject -> o.optString("avatar").takeIf { !o.isNull("avatar") && Avatars.parse(it) != null } }
        val original = a.users()
        var before = original
        val cloudMain = (0 until list.length()).firstNotNullOfOrNull { list.optJSONObject(it) }
        when (first) {
            // The account's users here: this TV's others go, with what they watched (the main one becomes the account's).
            First.CLOUD -> {
                before.filter { it.id != "" }.forEach { a.vodDir(it.id).deleteRecursively() }
                before = before.filter { it.id == "" }
            }
            // This TV's users on the account: the account's others go there; its main user becomes this TV's.
            First.DEVICE -> {
                for (id in server.keys.toList()) if (id != cloudMain?.getString("id")) {
                    ZtvAccount.removeUser(token, id)
                    server.remove(id)
                }
                val main = before.first()
                cloudMain?.let {
                    ZtvAccount.updateUser(token, it.getString("id"), main.name, main.avatar)
                    it.put("name", main.name).put("avatar", main.avatar ?: JSONObject.NULL)
                }
            }
            else -> {}
        }
        val claimed = before.mapNotNull { it.sid }.toMutableSet()
        val out = mutableListOf<User>()
        for (u in before) {
            var sid = u.sid
            if (sid == null) {
                // The main user (the first, sync's owner) is the account's first user; the others by name.
                // Chosen at the first sync (see First): every other user here goes up as a user of their own, same name or not.
                val match = if (u.id == "") cloudMain?.takeIf { it.getString("id") !in claimed && it.getString("id") in server }
                    else if (first != null) null
                    else server.values.firstOrNull { it.getString("id") !in claimed && it.getString("id") != cloudMain?.getString("id") && it.optString("name").equals(u.name, ignoreCase = true) }
                sid = match?.getString("id") ?: ZtvAccount.addUser(token, u.name, u.avatar)?.also {
                    server[it] = JSONObject().put("id", it).put("name", u.name).put("avatar", u.avatar ?: JSONObject.NULL)
                    snap.put(it, JSONObject().put("name", u.name).put("avatar", u.avatar ?: ""))
                }
                if (sid == null) { out += u; continue } // the account is full: this one stays here only
                claimed += sid
                // Matched by name: their avatar here if the account's user has none.
                if (match != null && avatarOf(match) == null && u.avatar != null) {
                    ZtvAccount.updateUser(token, sid, match.optString("name"), u.avatar)
                    match.put("avatar", u.avatar)
                }
            }
            val s = server[sid]
            if (s == null) {
                if (snap.has(sid) && u.id != "") { // removed on the account
                    a.vodDir(u.id).deleteRecursively()
                    snap.remove(sid)
                    File(a.syncDir(), "watched_$sid.json").delete()
                    File(a.syncDir(), "progress_$sid.json").delete()
                    continue
                }
                val again = ZtvAccount.addUser(token, u.name, u.avatar) // the first user, or never synced: up again
                out += User(u.id, u.name, u.avatar, again)
                again?.let { snap.put(it, JSONObject().put("name", u.name).put("avatar", u.avatar ?: "")) }
                continue
            }
            val b = snap.optJSONObject(sid)
            val remoteName = s.optString("name").ifBlank { u.name }
            val remoteAvatar = avatarOf(s)
            val localChanged = b != null && (b.optString("name") != u.name || b.optString("avatar") != (u.avatar ?: ""))
            val remoteChanged = b == null || b.optString("name") != remoteName || b.optString("avatar") != (remoteAvatar ?: "")
            val user = if (localChanged && !remoteChanged) {
                ZtvAccount.updateUser(token, sid, u.name, u.avatar)
                User(u.id, u.name, u.avatar, sid)
            } else User(u.id, remoteName, remoteAvatar, sid)
            out += user
            snap.put(sid, JSONObject().put("name", user.name).put("avatar", user.avatar ?: ""))
        }
        // Removed here since the last sync: removed on the account.
        val kept = out.mapNotNull { it.sid }.toSet()
        for (sid in snap.keys().asSequence().toList()) if (sid !in kept) {
            if (sid in server) ZtvAccount.removeUser(token, sid)
            server.remove(sid)
            snap.remove(sid)
        }
        // On the account, not here: added here.
        for ((sid, s) in server) if (sid !in kept && sid !in claimed) {
            val u = User(java.util.UUID.randomUUID().toString().take(8), s.optString("name").ifBlank { "?" }.take(40), avatarOf(s), sid)
            out += u
            snap.put(sid, JSONObject().put("name", u.name).put("avatar", u.avatar ?: ""))
        }
        a.prefs.edit().putString(K.SYNC_USERS, snap.toString()).apply()
        val changed = out.size != original.size || out.zip(original).any { (x, y) -> x.id != y.id || x.name != y.name || x.avatar != y.avatar || x.sid != y.sid }
        if (changed) a.saveUsers(out, synced = true)
        return changed
    }
}

/** Where sync keeps what the account last had (see Sync.syncDoc). */
internal fun MainActivity.syncDir() = File(filesDir, "sync")

/**
 * A sync now, unless one is running ([then]: null when it couldn't, offline). What changed here shown: the
 * settings, by building the home screen again (once the panel and the first start's cards are closed); the users and
 * what they watched, in place. Found unlinked on the site: unlinked here too.
 */
internal fun MainActivity.syncNow(first: Sync.First? = null, then: ((Sync.Result?) -> Unit)? = null) {
    val token = prefs.getString(K.ZTV_TOKEN, null) ?: return run { then?.invoke(null) }
    if (!Sync.running.compareAndSet(false, true)) return run { then?.invoke(null) }
    Sync.last = System.currentTimeMillis()
    netIo.execute {
        val r = runCatching { Sync.run(this, token, first) }
        Sync.running.set(false)
        ui.post {
            val e = r.exceptionOrNull()
            if (e is ZtvAccount.Unlinked) {
                ztvForget()
                toast(R.string.ztv_unlinked, long = false)
            } else if (e != null) warn("sync failed ($e)")
            val result = r.getOrNull()
            if (result != null) {
                offerTrakt() // users with Trakt on another TV: connect it here too
                if (result.settings) Sync.recreatePending = true
                if (result.users || result.data) usersChanged() // in place
                if (then == null) syncShow()
            }
            then?.invoke(result)
            syncSoon(SYNC_EVERY_MS) // and again in a while
        }
    }
}

private const val SYNC_EVERY_MS = 15 * 60_000L

/** The home screen built again with the account's settings, if they came in, once nothing is open over it. */
internal fun MainActivity.syncShow() {
    if (!Sync.recreatePending || settingsScrim.visibility == android.view.View.VISIBLE || setupDialog != null) return
    Sync.recreatePending = false
    recreate()
}

/** A sync in [delayMs] (a change made here: in a few seconds), when linked; asked again meanwhile, still one. */
internal fun MainActivity.syncSoon(delayMs: Long = 5_000L) {
    if (ztvEmail() == null) return
    Sync.pending?.let { ui.removeCallbacks(it) }
    val r = Runnable { Sync.pending = null; syncNow() }
    Sync.pending = r
    ui.postDelayed(r, delayMs)
}

/** Back on the home screen (from an app, the player): a sync, at most once a minute. */
internal fun MainActivity.syncOnResume() {
    syncShow()
    if (System.currentTimeMillis() - Sync.last > 60_000L) syncSoon(2_000L)
}

/**
 * Just linked ([token], the account's [email]): the account asked first. With things there already (users, settings),
 * [ask] shows the choice (see Sync.First) and its answer leads: the token kept and the first sync made that way
 * ([done]), or (null: Cancel and leave) the TV unlinked again on the site ([cancelled]). With nothing there, the first
 * sync at once. The token is kept only once chosen: no sync meanwhile goes its own way.
 */
internal fun MainActivity.firstSync(token: String, email: String,
                                    ask: (cloudMain: String, users: String, choose: (Sync.First?) -> Unit) -> Unit,
                                    done: (Sync.Result?) -> Unit, cancelled: () -> Unit) {
    netIo.execute {
        val state = runCatching { ZtvAccount.state(token) }.onFailure { warn("first sync: account not read ($it)") }.getOrNull()
        ui.post {
            val go = { first: Sync.First? ->
                prefs.edit().putString(K.ZTV_TOKEN, token).putString(K.ZTV_EMAIL, email).apply()
                syncNow(first, done)
            }
            if (state == null) {
                netIo.execute { ZtvAccount.unlink(token) }
                toast(R.string.ztv_failed)
                return@post cancelled()
            }
            if (!Sync.hasData(state)) return@post go(null)
            val list = state.optJSONArray("users") ?: org.json.JSONArray()
            val names = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("name") }.filter { it.isNotBlank() }
            ask(names.firstOrNull() ?: users().first().name, names.joinToString(", ")) { choice ->
                if (choice == null) {
                    netIo.execute { ZtvAccount.unlink(token) }
                    cancelled()
                } else go(choice)
            }
        }
    }
}

/**
 * The first sync's choice, on [show] (the first start's card, or a Settings page: its title, text, and buttons):
 * the account's configuration, this TV's, merged, or Cancel and leave. The two that remove things are confirmed first.
 */
internal fun MainActivity.syncChoice(cloudMain: String, users: String,
                                     show: (title: String, body: String, buttons: List<Pair<String, () -> Unit>>) -> Unit,
                                     choose: (Sync.First?) -> Unit) {
    val title = getString(R.string.sync_choice_title)
    val body = if (users.isBlank()) getString(R.string.sync_choice_body_settings) else getString(R.string.sync_choice_body, users)
    fun confirm(text: String, first: Sync.First) = show(title, text, listOf(
        getString(R.string.sync_confirm_yes) to { choose(first) },
        getString(R.string.sync_confirm_back) to { syncChoice(cloudMain, users, show, choose) },
    ))
    show(title, body, listOf(
        getString(R.string.sync_choice_cloud) to { confirm(getString(R.string.sync_confirm_cloud, cloudMain), Sync.First.CLOUD) },
        getString(R.string.sync_choice_device) to { confirm(getString(R.string.sync_confirm_device), Sync.First.DEVICE) },
        getString(R.string.sync_choice_merge) to { choose(Sync.First.MERGE) },
        getString(R.string.sync_choice_cancel) to { choose(null) },
    ))
}

