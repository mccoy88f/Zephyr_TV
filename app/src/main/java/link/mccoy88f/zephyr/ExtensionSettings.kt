package link.mccoy88f.zephyr

import android.content.SharedPreferences
import android.widget.Toast
import link.mccoy88f.zephyr.MainActivity.Companion.SUBMENU
import java.io.File
import org.json.JSONObject

// The extensions in Zephyr TV: which are installed and in what order, which are on, which are 18+, where each came
// from, the preinstalled ones, and their updates. What an extension is, and its package on disk, are in Extensions.

/** The extensions installed, in the user's order (those installed since, after). */
internal fun extensions(p: SharedPreferences, files: File): List<Extensions.Extension> {
    val all = Extensions.installed(files).associateBy { it.id }
    val order = p.getString(K.EXT_ORDER, "")!!.lines().filter { it in all }
    return (order + (all.keys - order.toSet()).sorted()).mapNotNull { all[it] }
}

/** Whether the extension [id] is on. */
internal fun extensionOn(p: SharedPreferences, id: String) = id !in p.getStringSet(K.EXT_OFF, emptySet())!!

/** Whether [e] is 18+: it says so, or the user marked it. */
internal fun extensionAdult(p: SharedPreferences, e: Extensions.Extension) = e.adult || e.id in p.getStringSet(K.EXT_ADULT, emptySet())!!

/**
 * Zephyr TV's own repository: there from the start, until the user takes it away. Its extensions not installed are
 * rows on the Extensions page, installed from their address with OK: Zephyr TV carries none of them.
 */
internal const val OFFICIAL_REPOSITORY = "https://raw.githubusercontent.com/mccoy88f/Zephyr-TV-Extensions/main/repository-zephyrtv.json"

/** The repositories' addresses, in the order added: Zephyr TV's own when the list was never changed. */
internal fun extensionRepos(p: SharedPreferences): List<String> =
    p.getString(K.EXT_REPOS, null)?.lines()?.filter { it.isNotBlank() } ?: listOf(OFFICIAL_REPOSITORY)

private fun saveExtensionRepos(p: SharedPreferences, list: List<String>) =
    p.edit().putString(K.EXT_REPOS, list.distinct().joinToString("\n")).apply()

/**
 * What each repository holds, as read last, on disk: its name, when, and its extensions as their
 * manifest-zephyrtv.json say (without their packages). Read again once a day (see refreshRepos).
 */
internal object Repos {
    private const val FILE = "ext_repositories.json"

    class Item(val url: String, val id: String, val name: String, val description: String, val version: String, val adult: Boolean)
    class Read(val name: String, val at: Long, val items: List<Item>)

    @Synchronized
    private fun all(files: File): JSONObject = runCatching { JSONObject(File(files, FILE).readText()) }.getOrDefault(JSONObject())

    @Synchronized
    fun saved(files: File, url: String): Read? = all(files).optJSONObject(url)?.let { o ->
        val a = o.optJSONArray("items") ?: org.json.JSONArray()
        Read(o.optString("name"), o.optLong("at"), (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { i ->
            Item(i.optString("url"), i.optString("id"), i.optString("name"), i.optString("description"), i.optString("version"), i.optBoolean("adult"))
        })
    }

    @Synchronized
    fun save(files: File, url: String, read: Read?) {
        val o = all(files)
        if (read == null) o.remove(url) else o.put(url, JSONObject().put("name", read.name).put("at", read.at).put("items", org.json.JSONArray(read.items.map { i ->
            JSONObject().put("url", i.url).put("id", i.id).put("name", i.name).put("description", i.description).put("version", i.version).put("adult", i.adult)
        })))
        val part = File(files, "$FILE.part")
        part.writeText(o.toString())
        part.renameTo(File(files, FILE))
    }

    /** The repository at [url] read, with each of its extensions' manifest-zephyrtv.json (those that answer). Network. */
    fun read(url: String, m: JSONObject = Http.json(url)): Read {
        val r = Extensions.repository(url, m)
        val items = parallel(r.extensions, 4, 30) { address ->
            Extensions.published(address).let { p -> Item(address, p.id, p.name, p.description, p.version, p.adult) }
        }
        return Read(r.name, System.currentTimeMillis(), items)
    }
}

private val reposReading = java.util.concurrent.atomic.AtomicBoolean()

/**
 * In the background: each repository read again when its last reading is a day old ([force]: all of them now), or
 * missing. [done] on the UI thread, with whether any reading changed what's listed.
 */
internal fun MainActivity.refreshRepos(force: Boolean = false, done: ((Boolean) -> Unit)? = null) {
    if (!reposReading.compareAndSet(false, true)) return
    netIo.execute {
        var changed = false
        try {
            for (url in extensionRepos(prefs)) {
                val old = Repos.saved(filesDir, url)
                if (!force && old != null && System.currentTimeMillis() - old.at < EXT_CHECK_MS) continue
                runCatching { Repos.read(url) }.onSuccess { r ->
                    if (old == null || old.name != r.name || old.items.map { it.id to it.version } != r.items.map { it.id to it.version }) changed = true
                    Repos.save(filesDir, url, r)
                }.onFailure { warn("Repository not read: $url ($it)") }
            }
        } finally {
            reposReading.set(false)
            ui.post { done?.invoke(changed) }
        }
    }
}

/** Where the extension [id] was added from (its manifest-zephyrtv.json), or null (from a .zip, or preinstalled). */
internal fun extensionSource(p: SharedPreferences, id: String): String? =
    runCatching { JSONObject(p.getString(K.EXT_SOURCES, null) ?: "{}").optString(id).takeIf { it.isNotBlank() } }.getOrNull()

/** Keeps a newly installed [e] in the lists: last in the order, [source] its address (when added from one), [adult]. */
internal fun rememberExtension(p: SharedPreferences, e: Extensions.Extension, source: String?, adult: Boolean) {
    val order = p.getString(K.EXT_ORDER, "")!!.lines().filter { it.isNotBlank() && it != e.id } + e.id
    val sources = runCatching { JSONObject(p.getString(K.EXT_SOURCES, null) ?: "{}") }.getOrDefault(JSONObject())
    if (source != null) sources.put(e.id, source)
    val marked = p.getStringSet(K.EXT_ADULT, emptySet())!!.toMutableSet().apply { if (adult && !e.adult) add(e.id) else remove(e.id) }
    p.edit().putString(K.EXT_ORDER, order.joinToString("\n")).putString(K.EXT_SOURCES, sources.toString()).putStringSet(K.EXT_ADULT, marked).apply()
}

/** Forgets the extension [id] (removed): its place in the lists. Its files: Extensions.remove. */
internal fun forgetExtension(p: SharedPreferences, id: String) {
    val sources = runCatching { JSONObject(p.getString(K.EXT_SOURCES, null) ?: "{}") }.getOrDefault(JSONObject()).apply { remove(id) }
    p.edit().putString(K.EXT_ORDER, p.getString(K.EXT_ORDER, "")!!.lines().filter { it.isNotBlank() && it != id }.joinToString("\n"))
        .putStringSet(K.EXT_OFF, p.getStringSet(K.EXT_OFF, emptySet())!! - id)
        .putStringSet(K.EXT_ADULT, p.getStringSet(K.EXT_ADULT, emptySet())!! - id)
        .putStringSet(K.EXT_COLLECT, p.getStringSet(K.EXT_COLLECT, emptySet())!! - id)
        .putString(K.EXT_OPTIONS, runCatching { JSONObject(p.getString(K.EXT_OPTIONS, null) ?: "{}").apply { remove(id) } }.getOrDefault(JSONObject()).toString())
        .putString(K.EXT_SOURCES, sources.toString()).apply()
}

/** The address an installed [e] is updated from: where it was added from, else its own updateUrl. */
internal fun extensionUpdateUrl(p: SharedPreferences, e: Extensions.Extension) = extensionSource(p, e.id) ?: e.updateUrl

/** A day: an extension's manifest-zephyrtv.json is read again once its last check is this old. */
private const val EXT_CHECK_MS = 24 * 3600_000L

private val extensionsChecking = java.util.concurrent.atomic.AtomicBoolean()

/**
 * In the background: the extensions in the order but missing (a backup put back on another TV), installed again from
 * their address; and each extension's address read once a day, a newer version installed. [done] on the UI thread,
 * with how many were installed or updated, and the names of those that couldn't be checked or updated.
 */
internal fun MainActivity.refreshExtensions(force: Boolean = false, done: ((Int, List<String>) -> Unit)? = null) {
    refreshRepos() // the repositories too, once a day
    if (!extensionsChecking.compareAndSet(false, true)) return
    netIo.execute {
        var changed = 0
        val failed = ArrayList<String>()
        try {
            val installed = Extensions.installed(filesDir).associateBy { it.id }
            // Missing, with an address to have it from: installed again.
            for (id in prefs.getString(K.EXT_ORDER, "")!!.lines().filter { it.isNotBlank() && it !in installed }) {
                val url = extensionSource(prefs, id) ?: continue
                runCatching { Extensions.download(filesDir, Extensions.published(url)) }
                    .onSuccess { changed++ }.onFailure { warn("Extension not installed again: $id ($it)") }
            }
            for (e in installed.values) {
                val url = extensionUpdateUrl(prefs, e) ?: continue
                val stamp = File(Extensions.dataDir(filesDir, e.id), ".checked")
                if (!force && System.currentTimeMillis() - stamp.lastModified() < EXT_CHECK_MS) continue
                // Checked, whatever the answer: one that fails is tried again tomorrow, not at each return home.
                stamp.writeText("")
                runCatching {
                    val p = Extensions.published(url)
                    if (p.id == e.id && Extensions.newer(p.version, e.version)) { Extensions.download(filesDir, p); changed++ }
                }.onFailure { failed += e.name; warn("Extension update failed: ${e.name} ($it)") }
            }
        } finally {
            extensionsChecking.set(false)
            ui.post {
                if (changed > 0) {
                    Extensions.installed(filesDir).forEach { Stremio.forget(filesDir, ExtensionCalls.url(it.id)) }
                    addonsChanged()
                }
                done?.invoke(changed, failed)
            }
        }
    }
}

// ---------- settings ----------

/** What Zephyr TV says when an extension can't be installed. */
private fun MainActivity.refusal(e: Throwable): String = when ((e as? Extensions.Refused)?.why) {
    Extensions.Why.NEWER_STANDARD -> getString(R.string.ext_err_newer)
    Extensions.Why.NEEDS_FEATURES -> getString(R.string.ext_err_needs, e.message?.substringAfter(": ").orEmpty())
    Extensions.Why.TOO_LARGE -> getString(R.string.ext_err_large)
    Extensions.Why.DAMAGED -> getString(R.string.ext_err_damaged)
    Extensions.Why.DIFFERENT -> getString(R.string.ext_err_different)
    Extensions.Why.UNREACHABLE -> getString(R.string.ext_err_unreachable)
    else -> getString(R.string.ext_err_not)
}

/** What an extension with these [types] and [resources] provides, in words: "Films, Series, TV guide". */
private fun MainActivity.provides(types: List<String>, resources: List<String>): String {
    val kinds = types.mapNotNull { t ->
        when (t) {
            "movie" -> R.string.vod_movies
            "series" -> R.string.vod_series
            "tv" -> R.string.setting_addons_tv
            "radio" -> R.string.ext_kind_radio
            "video", "channel" -> R.string.ext_kind_videos
            "course" -> R.string.ext_kind_courses
            "podcast" -> R.string.ext_kind_podcasts
            "audiobook" -> R.string.ext_kind_audiobooks
            "event" -> R.string.ext_kind_events
            else -> null
        }
    }.distinct().map { getString(it) }
    val more = listOfNotNull(
        getString(R.string.ext_kind_streams).takeIf { "stream" in resources && "catalog" !in resources },
        getString(R.string.ext_kind_guide).takeIf { "guide" in resources },
    )
    return (kinds + more).joinToString(", ")
}

/** A page's text on top: [text], dimmed. */
private fun MainActivity.intro(text: String) = label(text, 13f).apply {
    alpha = 0.7f
    maxLines = 10
    setLineSpacing(0f, 1.15f)
    setPadding(dp(14), 0, dp(14), dp(12))
}

/**
 * Extensions: what they are (and that they run code from elsewhere), each one, those of the repositories not
 * installed (Install: from their address), each repository, Add an extension, Check for updates.
 */
internal fun MainActivity.extensionsPage(focus: Int = 0) {
    val all = extensions(prefs, filesDir)
    val repos = extensionRepos(prefs).map { it to Repos.saved(filesDir, it) }
    // A repository never read yet (Zephyr TV's own, the first time): read now, and the page shown again once it's in.
    if (repos.any { it.second == null }) refreshRepos { if (pageShowing(getString(R.string.ext_title))) extensionsPage(focusedRow() ?: focus) }
    val suggested = repos.flatMap { it.second?.items.orEmpty() }.distinctBy { it.id }.filter { i -> all.none { it.id == i.id } }
        .filter { !it.adult || adultUnlocked() }
    showPage(getString(R.string.ext_title), all.map { e ->
        page(e.name, if (extensionOn(prefs, e.id)) SUBMENU else getString(R.string.state_off)) { extensionPage(e.id) }
    } + suggested.map { i -> page(i.name, getString(R.string.ext_install)) { extensionFromAddress(i.url) } } +
        repos.map { (url, r) -> page(getString(R.string.ext_repo_row, r?.name ?: url.substringAfter("://").substringBefore('/')), r?.items?.size?.toString() ?: "…") { repoPage(url) } } + listOf(
        page(getString(R.string.ext_add), SUBMENU) { extensionFromPhone() },
        page(getString(R.string.ext_check)) {
            Toast.makeText(this, getString(R.string.ext_reading), Toast.LENGTH_SHORT).show()
            refreshExtensions(force = true) { n, failed ->
                // One that couldn't be checked says so: "no updates" would be wrong for it.
                Toast.makeText(this, when {
                    failed.isNotEmpty() -> getString(R.string.ext_update_failed, failed.joinToString(", "))
                    n == 0 -> getString(R.string.ext_none_updated)
                    else -> getString(R.string.ext_some_updated, n)
                }, Toast.LENGTH_LONG).show()
                if (pageShowing(getString(R.string.ext_title))) extensionsPage(all.size + suggested.size + repos.size + 1)
            }
            refreshRepos(force = true) { changed -> if (changed && pageShowing(getString(R.string.ext_title))) extensionsPage(focusedRow() ?: 0) }
        },
    ), focus, top = intro(getString(R.string.ext_intro))) { addonsAndPlugins(3) }
}

/** An extension's page: what it is, On, Options, Where it shows, 18+, up or down the list, Update now, Remove. */
private fun MainActivity.extensionPage(id: String, focus: Int = 0) {
    val e = Extensions.installed(filesDir, id) ?: return extensionsPage()
    val ids = extensions(prefs, filesDir).map { it.id }
    val at = ids.indexOf(id)
    val on = extensionOn(prefs, id)
    val about = listOfNotNull(
        e.description.takeIf { it.isNotBlank() },
        listOfNotNull("v${e.version}", e.author.takeIf { it.isNotBlank() }).joinToString(" · "),
        provides(e.types, e.resources).takeIf { it.isNotEmpty() }?.let { getString(R.string.ext_provides, it) },
    ).joinToString("\n")
    fun move(by: Int) {
        val list = ids.toMutableList()
        list.add(at + by, list.removeAt(at))
        prefs.edit().putString(K.EXT_ORDER, list.joinToString("\n")).apply()
        addonsChanged()
    }
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    rows += page(getString(R.string.addon_on), toggle(on)) {
        prefs.edit().putStringSet(K.EXT_OFF, prefs.getStringSet(K.EXT_OFF, emptySet())!!.let { if (on) it + id else it - id }).apply()
        extensionChanged(id)
        extensionPage(id, 0)
    }
    if (e.settings) {
        val row = rows.size
        rows += page(getString(R.string.ext_options), SUBMENU) { extensionOptions(id) { extensionPage(id, row) } }
    }
    // One that says it's 18+ is, whatever the switch: the switch is for those that don't say.
    if (!e.adult) {
        val marked = id in prefs.getStringSet(K.EXT_ADULT, emptySet())!!
        val row = rows.size
        rows += page(getString(R.string.addon_adult), toggle(marked)) {
            prefs.edit().putStringSet(K.EXT_ADULT, prefs.getStringSet(K.EXT_ADULT, emptySet())!!.let { if (marked) it - id else it + id }).apply()
            addonsChanged()
            extensionPage(id, row)
        }
    }
    // Where it shows: in its own app, or in the collectors its manifest allows.
    if (e.collectors.isNotEmpty()) {
        val collect = id in prefs.getStringSet(K.EXT_COLLECT, emptySet())!!
        val row = rows.size
        rows += page(getString(R.string.ext_place), getString(if (collect) R.string.ext_place_collectors else R.string.ext_place_own)) {
            prefs.edit().putStringSet(K.EXT_COLLECT, prefs.getStringSet(K.EXT_COLLECT, emptySet())!!.let { if (collect) it - id else it + id }).apply()
            addonsChanged()
            extensionPage(id, row)
        }
    }
    val moves = rows.size
    if (at > 0) rows += page(getString(R.string.addon_move_up)) { move(-1); extensionPage(id, moves) }
    if (at in 0 until ids.size - 1) rows += page(getString(R.string.addon_move_down)) { move(+1); extensionPage(id, moves) }
    extensionUpdateUrl(prefs, e)?.let { url ->
        val row = rows.size
        rows += page(getString(R.string.addon_update)) {
            Toast.makeText(this, getString(R.string.ext_reading), Toast.LENGTH_SHORT).show()
            netIo.execute {
                val result = runCatching {
                    val p = Extensions.published(url)
                    if (p.id == e.id && Extensions.newer(p.version, e.version)) Extensions.download(filesDir, p) else null
                }
                ui.post {
                    val text = result.fold({ got -> if (got == null) getString(R.string.ext_up_to_date, e.name) else getString(R.string.ext_updated, got.name, got.version) },
                        { refusal(it) })
                    Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                    if (result.getOrNull() != null) extensionChanged(id)
                    if (pageShowing(e.name)) extensionPage(id, row)
                }
            }
        }
    }
    rows += page(getString(R.string.addon_remove)) {
        Extensions.remove(filesDir, id)
        forgetExtension(prefs, id)
        extensionChanged(id)
        extensionsPage(at.coerceAtMost(ids.size - 2).coerceAtLeast(0))
    }
    showPage(e.name, rows, focus, top = intro(about)) { extensionsPage(at.coerceAtLeast(0)) }
}

/**
 * Before installing: what the extension is ([name], [author], [description], [version], what it provides), that it
 * runs code by someone else, 18+ ([adult]), Install. [install] does it (off the UI thread), [back] leaves.
 */
private fun MainActivity.extensionPreview(name: String, author: String, description: String, version: String, kinds: String,
                                          adult: Boolean, back: () -> Unit, install: (adult: Boolean) -> Extensions.Extension) {
    val about = listOfNotNull(
        description.takeIf { it.isNotBlank() },
        listOfNotNull("v$version", author.takeIf { it.isNotBlank() }).joinToString(" · "),
        kinds.takeIf { it.isNotEmpty() }?.let { getString(R.string.ext_provides, it) },
        "",
        if (author.isNotBlank()) getString(R.string.ext_warning, author) else getString(R.string.ext_warning_anon),
    ).joinToString("\n")
    showPage(name, listOf(
        page(getString(R.string.addon_adult), toggle(adult)) { extensionPreview(name, author, description, version, kinds, !adult, back, install) },
        page(getString(R.string.ext_install)) {
            Toast.makeText(this, getString(R.string.ext_reading), Toast.LENGTH_SHORT).show()
            netIo.execute {
                val result = runCatching { install(adult) }
                ui.post {
                    result.onSuccess { e ->
                        Toast.makeText(this, getString(R.string.ext_installed, e.name, e.version), Toast.LENGTH_LONG).show()
                        extensionChanged(e.id)
                        // One that does nothing until set up: its options at once.
                        if (e.configurationRequired && e.settings) extensionOptions(e.id) { extensionPage(e.id) } else extensionPage(e.id)
                    }.onFailure { Toast.makeText(this, refusal(it), Toast.LENGTH_LONG).show() }
                }
            }
        },
    ), 1, top = intro(about), back = back)
}

/** The extension published at [input] (a manifest-zephyrtv.json, or its folder): read, then shown before installing. */
internal fun MainActivity.extensionFromAddress(input: String) {
    val url = Extensions.publishedUrl(input) ?: return Toast.makeText(this, getString(R.string.ext_err_not), Toast.LENGTH_LONG).show()
    Toast.makeText(this, getString(R.string.ext_reading), Toast.LENGTH_SHORT).show()
    netIo.execute {
        val m = runCatching { Http.json(url) }.getOrElse {
            ui.post { Toast.makeText(this, refusal(Extensions.Refused(Extensions.Why.UNREACHABLE, it.toString())), Toast.LENGTH_LONG).show() }
            return@execute
        }
        // A repository: added to the list, and its page shown, its extensions to install from there.
        if (Extensions.isRepository(m)) {
            val read = runCatching { Repos.read(url, m) }
            ui.post {
                read.onSuccess { r ->
                    Repos.save(filesDir, url, r)
                    saveExtensionRepos(prefs, extensionRepos(prefs) + url)
                    Toast.makeText(this, getString(R.string.ext_repo_added, r.name), Toast.LENGTH_LONG).show()
                    repoPage(url)
                }.onFailure { Toast.makeText(this, refusal(it), Toast.LENGTH_LONG).show() }
            }
            return@execute
        }
        val result = runCatching { Extensions.published(url, m) }
        ui.post {
            result.onSuccess { p ->
                // What it provides is in its package, not read yet: shown once installed.
                extensionPreview(p.name, p.author, p.description, p.version, "", p.adult, { extensionsPage() }) { adult ->
                    Extensions.download(filesDir, p).also { rememberExtension(prefs, it, url, adult) }
                }
            }.onFailure { Toast.makeText(this, refusal(it), Toast.LENGTH_LONG).show() }
        }
    }
}

/**
 * A repository's page: its extensions (installed ones open their page, the others install from their address),
 * Read again, Remove (those installed from it stay).
 */
private fun MainActivity.repoPage(url: String, focus: Int = 0) {
    val r = Repos.saved(filesDir, url)
    val title = getString(R.string.ext_repo_row, r?.name ?: url.substringAfter("://").substringBefore('/'))
    val installed = extensions(prefs, filesDir).associateBy { it.id }
    val items = r?.items.orEmpty().filter { !it.adult || adultUnlocked() }
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    if (r != null && items.isEmpty()) rows += page(getString(R.string.ext_repo_empty)) { }
    items.forEach { i ->
        val have = installed[i.id]
        rows += page(i.name, if (have != null) getString(R.string.ext_repo_have) else getString(R.string.ext_install)) {
            if (have != null) extensionPage(i.id) else extensionFromAddress(i.url)
        }
    }
    val again = rows.size
    rows += page(getString(R.string.ext_repo_refresh)) {
        Toast.makeText(this, getString(R.string.ext_reading), Toast.LENGTH_SHORT).show()
        netIo.execute {
            val read = runCatching { Repos.read(url) }
            ui.post {
                read.onSuccess { Repos.save(filesDir, url, it) }.onFailure { Toast.makeText(this, refusal(it), Toast.LENGTH_LONG).show() }
                if (pageShowing(title)) repoPage(url, again)
            }
        }
    }
    rows += page(getString(R.string.ext_repo_remove)) {
        saveExtensionRepos(prefs, extensionRepos(prefs) - url)
        Repos.save(filesDir, url, null)
        extensionsPage()
    }
    showPage(title, rows, focus, top = intro(listOf(getString(R.string.ext_repo_intro), url).joinToString("\n"))) { extensionsPage() }
}

/** The extension in the package [zip] (sent from a phone): shown before installing, then installed from it. */
private fun MainActivity.extensionFromZip(zip: File) {
    val m = Extensions.manifestOf(zip) ?: return Toast.makeText(this, getString(R.string.ext_err_not), Toast.LENGTH_LONG).show()
    val types = m.optJSONArray("types")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    val resources = m.optJSONArray("resources")?.let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? String) ?: (a.opt(it) as? JSONObject)?.optString("name") } }.orEmpty()
    extensionPreview(m.optString("name").ifBlank { zip.name }, m.optString("author"), m.optString("description"), m.optString("version"),
        provides(types, resources), m.optJSONObject("behaviorHints")?.optBoolean("adult") == true, { zip.delete(); extensionsPage() }) { adult ->
        Extensions.install(filesDir, zip).also { rememberExtension(prefs, it, null, adult); zip.delete() }
    }
}

/** An extension's address typed with the remote. */
private fun MainActivity.typeExtension() {
    textPage(getString(R.string.ext_add), "", getString(R.string.ext_hint), getString(R.string.ext_add_intro), back = { extensionFromPhone() }) { typed ->
        listOf(page(getString(R.string.ext_install)) { extensionFromAddress(typed()) })
    }
}

/** Add an extension: its address pasted on a phone (QR code), its .zip sent from a phone, or the address typed. */
private fun MainActivity.extensionFromPhone() {
    val status = label(getString(R.string.addon_phone_waiting), 14f)
    val page = PhonePages.text(phoneTexts(R.string.ext_phone_title, R.string.ext_add_intro), getString(R.string.ext_hint))
    showPhoneLink(getString(R.string.ext_add), PhoneTask(page, 4096) { file ->
        val url = Extensions.publishedUrl(file.readText().trim()) ?: return@PhoneTask 400 to getString(R.string.ext_err_not)
        ui.post { extensionFromAddress(url) }
        200 to getString(R.string.ext_received)
    }, status, listOf(
        page(getString(R.string.ext_zip)) { extensionZipFromPhone() },
        page(getString(R.string.addon_type)) { typeExtension() },
    )) { extensionsPage(extensions(prefs, filesDir).size) }
}

/** An extension's .zip sent from a phone. */
private fun MainActivity.extensionZipFromPhone() {
    val status = label(getString(R.string.addon_phone_waiting), 14f)
    val page = PhonePages.file(phoneTexts(R.string.ext_phone_title, R.string.ext_zip_intro), getString(R.string.ext_choose_zip), ".zip,application/zip")
    showPhoneLink(getString(R.string.ext_zip), PhoneTask(page, 10L * 1024 * 1024) { file ->
        val zip = File(cacheDir, "extension-${System.currentTimeMillis()}.zip")
        file.copyTo(zip, overwrite = true)
        if (Extensions.manifestOf(zip) == null) { zip.delete(); return@PhoneTask 415 to getString(R.string.ext_err_not) }
        ui.post { extensionFromZip(zip) }
        200 to getString(R.string.ext_received)
    }, status) { extensionFromPhone() }
}

/** The extensions on, as add-ons' addresses (see ExtensionCalls), in their order: the rest of Zephyr TV asks them so. */
internal fun MainActivity.extensionAddonUrls(): List<String> =
    extensions(prefs, filesDir).filter { extensionOn(prefs, it.id) }.map { ExtensionCalls.url(it.id) }

/** Only extensions explicitly requesting it: refresh catalogs once per launcher Activity creation. */
internal fun MainActivity.refreshStartupCatalogs() {
    extensions(prefs, filesDir).filter {
        LivePlaybackPolicy.refreshAtStartup(extensionOn(prefs, it.id),
            it.manifest.optJSONObject("behaviorHints")?.optBoolean("refreshOnStartup") == true)
    }.forEach { e ->
        updateAddon(ExtensionCalls.url(e.id), null) { listing ->
            if (listing != null) rowsAgain(tmdbLastFocusApp)
        }
    }
}

/** After an extension was installed, updated, removed or switched: what Zephyr TV kept of it as an add-on, read anew. */
internal fun MainActivity.extensionChanged(id: String) {
    Stremio.forget(filesDir, ExtensionCalls.url(id))
    ExtensionCalls.guidesFile(this, id).delete() // its guide() asked again: its settings may name other guides
    addonsChanged()
}

// ---------- where their content shows ----------

/** The collectors of the generic content (docs/extensions.md, Collectors), by their tile; Films, Series, TV are Zephyr TV's own. */
internal val COLLECTOR_PKGS = mapOf(
    STREMIO + "c_radio" to "radio", STREMIO + "c_videos" to "videos", STREMIO + "c_courses" to "courses",
    STREMIO + "c_podcasts" to "podcasts", STREMIO + "c_audiobooks" to "audiobooks", STREMIO + "c_events" to "events",
)

/** The collector a content [type] goes to ("movie" → "movies", "channel" → "videos"); an extension's own type is a film's. */
internal fun collectorOf(type: String) = when (type) {
    "movie", "movies" -> "movies"
    "series" -> "series"
    "tv" -> "tv"
    "radio" -> "radio"
    "video", "videos", "channel" -> "videos"
    "course", "courses" -> "courses"
    "podcast", "podcasts" -> "podcasts"
    "audiobook", "audiobooks" -> "audiobooks"
    "event", "events" -> "events"
    else -> "movies"
}

/** What a row of [type] is called: Films, Series, Radio, Videos… */
internal fun MainActivity.kindName(type: String): String = getString(when (collectorOf(type)) {
    "series" -> R.string.vod_series
    "tv" -> R.string.setting_addons_tv
    "radio" -> R.string.ext_kind_radio
    "videos" -> R.string.ext_kind_videos
    "courses" -> R.string.ext_kind_courses
    "podcasts" -> R.string.ext_kind_podcasts
    "audiobooks" -> R.string.ext_kind_audiobooks
    "events" -> R.string.ext_kind_events
    else -> R.string.vod_movies
})

/** Where the extensions' radio stations show: the Radio tile, each extension's own app, or nowhere; kept by [key]. */
internal enum class RadioPlace(val key: String) { VIRTUAL("virtual"), APPS("apps"), OFF("off") }

/** Where the extensions' radio stations show (Virtual apps and integrations → Radio): the Radio tile by default. */
internal fun MainActivity.radioPlace(): RadioPlace =
    prefs.getString(K.RADIO_PLACE, null).let { k -> RadioPlace.entries.firstOrNull { it.key == k } } ?: RadioPlace.VIRTUAL

/** Whether the extension [id] puts its content in the collectors (Where it shows), not in its own app. */
private fun MainActivity.inCollectors(id: String) = id in prefs.getStringSet(K.EXT_COLLECT, emptySet())!!

/**
 * Whether the extension at the add-on address [url] gives its rows of [type] to their collector: it's set to show in
 * the collectors, and its manifest allows that collector. Radio stations go by Radio's own setting: to the Radio tile
 * from every extension, or none. Not for an add-on.
 */
internal fun MainActivity.extensionRoutes(url: String, type: String): Boolean {
    val id = ExtensionCalls.idOf(url) ?: return false
    if (collectorOf(type) == "radio") return radioPlace() == RadioPlace.VIRTUAL
    if (!inCollectors(id)) return false
    return collectorOf(type) in (Extensions.installed(filesDir, id)?.collectors ?: return false)
}

/**
 * Whether the extension at [url] has an app of its own: it has content of its own (catalogs and streams: not a
 * catalog of titles others play, nor streams or a guide alone), isn't in Adult content, and has rows that show in it
 * (not given to the collectors, not radio stations switched off). Its catalogs as last read (manifest() makes them
 * from its settings), else its manifest's.
 */
internal fun MainActivity.extensionOwnApp(url: String): Boolean {
    val id = ExtensionCalls.idOf(url) ?: return false
    val e = Extensions.installed(filesDir, id) ?: return false
    if ("catalog" !in e.resources || "stream" !in e.resources || inAdultApp(url)) return false
    val read = Stremio.saved(filesDir, url)?.addon?.let { a -> (a.catalogs + a.vod).map { it.type } }
    val types = read ?: Extensions.list(e, "catalogs").let { c -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.optString("type") } }
    if (types.isEmpty()) return true // not read yet, or not set up: its app, once it has something
    return types.any { t -> !extensionRoutes(url, t) && !(collectorOf(t) == "radio" && radioPlace() == RadioPlace.OFF) }
}

/** The tile of the add-on at [url]'s own app. */
internal fun MainActivity.ownAppPkg(url: String): String? = Stremio.saved(filesDir, url)?.addon?.let { STREMIO + it.id }

/** The collectors' tiles that have rows: Radio, Videos, Courses, Podcasts, Audiobooks, Events. Runs on the io thread. */
internal fun MainActivity.collectorApps(): List<MainActivity.App> {
    val given = shownAddonUrls().filter { ExtensionCalls.idOf(it) != null && !inAdultApp(it) }.flatMap { url ->
        Stremio.saved(filesDir, url)?.addon?.let { a -> a.vod + a.catalogs }.orEmpty().filter { extensionRoutes(url, it.type) }.map { collectorOf(it.type) }
    }.toSet()
    return COLLECTOR_PKGS.filterValues { it in given }.map { (pkg, kind) ->
        val icon = when (kind) { "radio", "podcasts", "audiobooks" -> R.drawable.ic_audio; "events" -> R.drawable.ic_live_tv; else -> R.drawable.ic_movie }
        MainActivity.App(pkg, kindName(when (kind) { "videos" -> "video"; "courses" -> "course"; "podcasts" -> "podcast"; "audiobooks" -> "audiobook"; "events" -> "event"; else -> kind }),
            android.content.Intent(), getDrawable(icon)!!)
    }
}

// ---------- options ----------

/** The values chosen for the extension [id], by key; none yet: null. */
private fun MainActivity.extensionValues(id: String): JSONObject? =
    runCatching { JSONObject(prefs.getString(K.EXT_OPTIONS, null) ?: "{}").optJSONObject(id) }.getOrNull()

/** Sets the extension [id]'s [key] to [value] (null: its default); the first one set saves the others' defaults ([fields]). */
private fun MainActivity.setExtensionValue(id: String, fields: List<Nuvio.Field>, key: String, value: Any?) {
    val all = runCatching { JSONObject(prefs.getString(K.EXT_OPTIONS, null) ?: "{}") }.getOrDefault(JSONObject())
    val mine = all.optJSONObject(id) ?: JSONObject().apply { fields.forEach { f -> f.default?.let { put(f.key, it) } } }
    if (value == null) mine.remove(key) else mine.put(key, value)
    prefs.edit().putString(K.EXT_OPTIONS, all.put(id, mine).toString()).apply()
}

/**
 * An extension's options, as its settings() lists them (read in its process, so the page waits for it): each change
 * kept, and what Zephyr TV kept of the extension (its catalogs, made from them) read anew.
 */
internal fun MainActivity.extensionOptions(id: String, focus: Int = 0, back: () -> Unit) {
    val e = Extensions.installed(filesDir, id) ?: return back()
    showPage(e.name, listOf(page(getString(R.string.player_loading)) {}), back = back)
    netIo.execute {
        val fields = ExtensionCalls.fields(this, e)
        ui.post { if (pageShowing(e.name)) extensionForm(e, fields, focus, back) }
    }
}

private fun MainActivity.extensionForm(e: Extensions.Extension, fields: List<Nuvio.Field>, focus: Int, back: () -> Unit) {
    if (fields.none { it.type in setOf("toggle", "select", "text", "list") }) return showPage(e.name, listOf(page(getString(R.string.nuvio_options_none)) { back() }), back = back)
    formPage(e.name, "", fields, focus, { f -> extensionValues(e.id)?.opt(f.key)?.takeIf { it != JSONObject.NULL } ?: f.default },
        { f, v -> setExtensionValue(e.id, fields, f.key, v); extensionChanged(e.id) },
        { i -> extensionForm(e, fields, i, back) }, back)
}

// ---------- guides ----------

private const val GUIDES_MS = 24 * 3600_000L // an extension's guide() asked again after a day
private val guidesAsked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

/**
 * The guides of the extension at the add-on address [url] (none for an add-on), as kept: its sources asked of its
 * guide() and the guides read again in the background once a day, the rows under [pkg] built again once they're in.
 */
internal fun MainActivity.extensionGuides(url: String, pkg: String): List<Epg.Guide> {
    val id = ExtensionCalls.idOf(url) ?: return emptyList()
    val e = Extensions.installed(filesDir, id)?.takeIf { "guide" in it.resources } ?: return emptyList()
    val kept = ExtensionCalls.guidesFile(this, id)
    val sources = runCatching { org.json.JSONArray(kept.readText()) }.getOrNull()?.let { a -> (0 until a.length()).map { a.optString(it) } }
    val stale = sources == null || System.currentTimeMillis() - kept.lastModified() > GUIDES_MS
    val due = sources.orEmpty().filter { Epg.due(filesDir, it, own = false) }
    if ((stale || due.isNotEmpty()) && guidesAsked.add(id)) longIo.execute {
        try {
            val now = if (stale) ExtensionCalls.refreshGuides(this, e) else sources.orEmpty()
            val read = now.filter { Epg.due(filesDir, it, own = false) }.count { Epg.update(filesDir, it, own = false) != null }
            if (stale || read > 0) ui.post { rowsAgain(pkg) }
        } finally {
            guidesAsked.remove(id)
        }
    }
    return sources.orEmpty().mapNotNull { Epg.saved(filesDir, it, own = false) }
}
