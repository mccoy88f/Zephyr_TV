package link.mccoy88f.zephyr

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import link.mccoy88f.zephyr.MainActivity.App
import link.mccoy88f.zephyr.MainActivity.Companion.CW_APPS_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.GREYSCALE
import link.mccoy88f.zephyr.MainActivity.Companion.GRID_GAP
import link.mccoy88f.zephyr.MainActivity.Companion.LOGOS
import link.mccoy88f.zephyr.MainActivity.Companion.PER_APP
import link.mccoy88f.zephyr.MainActivity.Companion.TV_LISTINGS
import link.mccoy88f.zephyr.MainActivity.Companion.WRITE_EPG
import link.mccoy88f.zephyr.MainActivity.Prog

// The row under the apps: Continue Watching, an app's recommendations, their cards and posters.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

// ---------- continue watching (Android TV Watch Next) ----------

internal fun MainActivity.refreshWatchNext() {
    ui.post { if (tmdbLastFocusApp == null) watchTitle.text = getString(R.string.continue_watching) }
    io.execute {
        val list = try {
            queryWatchNext()
        } catch (e: Exception) {
            emptyList()
        }
        val playing = emptyList<Prog>() // persisted unfinished Nuvio/SmartTube sessions are in queryWatchNext
        ui.post {
            if (tmdbLastFocusApp != null) return@post // focus moved on to an app meanwhile: its rows, not these
            watchTitle.text = getString(R.string.continue_watching)
            watchApp = CW_PKG
            sectionCounts[CW_PKG] = if (playing.isEmpty() && list.isEmpty()) 0 else 1
            showWatchNext(list, playing)
        }
    }
}

/**
 * Two sources, same columns:
 * 1. The system Watch Next list (Netflix etc.), newest engagement first.
 * 2. Any app's own home-screen channel named "Continue Watching". Stremio publishes one (with
 *    progress and an autoPlay resume link) instead of using Watch Next, so signing in to Stremio
 *    on the TV is all it takes. Channel rows carry no timestamps, so they use the time the app was
 *    last opened from Zephyr TV, keeping the app's own order among themselves.
 * Newest first across apps, at most [PER_APP] each, so one app with ten shows can't bury the others.
 */
internal fun MainActivity.queryWatchNext(): List<Prog> {
    val hidden = prefs.getStringSet(K.HIDDEN_WATCH, emptySet())!!
    val all = watchHistorySources(android.os.Build.VERSION.SDK_INT,
        checkSelfPermission(TV_LISTINGS) == PackageManager.PERMISSION_GRANTED,
        system = {
            // Do not touch these Android 8 provider URIs on Android 7 / Fire OS 6.
            if (HomePolicy.supportsTvChannels(android.os.Build.VERSION.SDK_INT)) {
                val watchNext = readPrograms(TvContract.WatchNextPrograms.CONTENT_URI, hidden)
                val channels = continueChannels().flatMap { id ->
                    readPrograms(TvContract.buildPreviewProgramsUriForChannel(id), hidden)
                        .map { (_, p) -> prefs.getLong("t_${p.pkg}", 0) to p }
                }
                watchNext + channels
            } else emptyList()
        },
        local = { titleProgressCards(hidden) + externalResumeCards(hidden) })
    publishingPkgs = all.mapNotNull { it.second.pkg }.toSet()
    val off = prefs.getStringSet(K.CW_OFF, emptySet())!!
    return newestPerApp(all.filter { it.second.pkg !in off && (it.second.vod != null || ExternalResume.allowed(it.second.pkg)) && (it.second.vod != null || it.second.progress in 1..94) }
        .distinctBy { "${it.second.pkg}/${it.second.vod?.video ?: it.second.title.lowercase()}" }, 12) { it.pkg }
        .take(20)
}

/**
 * Apps that can feed Continue Watching: anything allowed to write TV listings, plus anything
 * actually publishing now. Built from installed apps each time, so new apps appear on their own.
 */
internal fun MainActivity.watchSources(): List<App> = apps.filter { a ->
    a.pkg in publishingPkgs || try {
        packageManager.getPackageInfo(a.pkg, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(WRITE_EPG) == true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}.sortedBy { it.label.lowercase() }

internal fun MainActivity.pickWatchSources() {
    val sources = watchSources()
    if (sources.isEmpty()) {
        Toast.makeText(this, getString(R.string.toast_no_cw_apps), Toast.LENGTH_SHORT).show()
        return
    }
    toggles(getString(R.string.setting_cw_apps), sources.map { it.label to it.pkg }, K.CW_OFF, getString(R.string.state_on), getString(R.string.state_off)) {
        watchKey = null
        refreshWatchNext()
        renderSettings(CW_APPS_ROW)
    }
}

internal fun MainActivity.continueChannels(): List<Long> {
    val ids = ArrayList<Long>()
    contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { c ->
        val name = c.getColumnIndex("display_name")
        val pkg = c.getColumnIndex("package_name")
        val id = c.getColumnIndex("_id")
        while (c.moveToNext()) {
            if (c.getString(name).equals("Continue Watching", ignoreCase = true) && c.getString(pkg) != packageName) {
                ids += c.getLong(id)
            }
        }
    }
    return ids
}

/** Pairs of (last engagement time, item); time is 0 for channel rows, which don't have it. */
internal fun MainActivity.readPrograms(uri: Uri, hidden: Set<String>): List<Pair<Long, Prog>> {
    val out = ArrayList<Pair<Long, Prog>>()
    contentResolver.query(uri, null, null, null, null)?.use { c ->
        fun str(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
        fun num(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) 0L else c.getLong(it) }
        while (c.moveToNext()) {
            if (str("browsable") == "0") continue
            val title = str("title") ?: continue
            if (hideKey(str("package_name"), title) in hidden) continue
            val season = str("season_display_number")
            val episode = str("episode_display_number")
            val duration = num("duration_millis")
            val position = num("last_playback_position_millis")
            val sub = listOfNotNull(
                if (season != null && episode != null) "S$season E$episode" else null,
                // Under a second in means "not really started": no "time left", no bar.
                if (duration > 0 && position in 1000 until duration) timeLeft(duration - position) else null,
            ).joinToString(" - ")
            val progress = if (duration > 0 && position >= 1000) (position * 100 / duration).toInt().coerceIn(0, 100) else 0
            val prog = Prog(title, sub, progress, str("thumbnail_uri") ?: str("poster_art_uri"), str("intent_uri"), str("package_name"), num("weight"))
            out += num("last_engagement_time_utc_millis") to prog
        }
    }
    return out
}

internal fun MainActivity.hideKey(pkg: String?, title: String) = "$pkg/$title"

/**
 * Hold OK on a card, Remove from this section. We can't delete another app's rows, so hiding is a local filter;
 * the rows on screen (Continue Watching, or the app's sections) are read again without it.
 */
internal fun MainActivity.hideWatch(p: Prog) {
    val hidden = prefs.getStringSet(K.HIDDEN_WATCH, emptySet())!! + hideKey(p.pkg, p.title)
    prefs.edit().putStringSet(K.HIDDEN_WATCH, hidden).apply()
    Toast.makeText(this, getString(R.string.toast_hidden_from_cw), Toast.LENGTH_SHORT).show()
    watchKey = null
    val app = watchApp
    if (app == null || app == CW_PKG || app == SEARCH_PKG) refreshWatchNext() else queryAppChannels(app)
}

/** [pkg]'s rows built again with what just came in, if it's still the app in focus. On the main thread. */
internal fun MainActivity.rowsAgain(pkg: String?) {
    if (pkg == null || tmdbLastFocusApp != pkg) return
    watchApp = null
    queryAppChannels(pkg)
}

internal fun MainActivity.queryAppChannels(pkg: String?) {
    if(android.os.Build.VERSION.SDK_INT<26 && ExternalResume.allowed(pkg)) {
        io.execute {
            val hidden=prefs.getStringSet(K.HIDDEN_WATCH,emptySet())!!
            val cards=(if(pkg?.startsWith("com.nuvio")==true) titleProgressCards(hidden).map { it.second } else emptyList()) +
                externalResumeCards(hidden).map { it.second }.filter { it.pkg==pkg } +
                externalHistoryCards(pkg!!) + if(pkg.startsWith("com.nuvio"))nativeHistoryCards() else emptyList()
            val sections=ArrayList<Section>()
            if(cards.isNotEmpty())sections+=Section("local-history",getString(R.string.continue_watching),getString(R.string.continue_watching),cards.distinctBy { it.title.lowercase() })
            if(pkg?.startsWith("org.smarttube")==true) {
                val links=listOf(getString(R.string.firetv_smarttube_subscriptions) to "FEsubscriptions",getString(R.string.firetv_smarttube_history) to "FEmy_youtube")
                sections+=Section("smarttube-links","SmartTube","SmartTube",links.map { (title,path) ->
                    val i=Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com/tv#/zylon-surface?c=$path")).setClassName(pkg,"com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity")
                    Prog(title,getString(R.string.firetv_smarttube_open),0,null,i.toUri(Intent.URI_INTENT_SCHEME),pkg)
                })
            }
            ui.post { if(tmdbLastFocusApp==pkg) {watchApp=pkg;if(sections.isEmpty())hideWatchRow() else showSections(sections)} }
        }
        return
    }

    // An add-on's tile, or the TV app with the add-ons' channels in it, has rows without the TV listings too.
    if (pkg == null || (!readsListings() && !pkg.startsWith(STREMIO) && addonsUnder(pkg).isEmpty())) {
        ui.post { watchTitle.text = getString(R.string.continue_watching) }
        refreshWatchNext()
        return
    }
    // An add-on's tile on the add-ons' own thread: thousands of channels mustn't hold up the other apps' rows.
    (if (pkg.startsWith(STREMIO)) addonIo else io).execute {
        val hidden = prefs.getStringSet(K.HIDDEN_WATCH, emptySet())!!
        // The TV app shows its channel list, a section per tuner (none, on a TV without one), then the add-ons'
        // channels if they show there; an add-on's tile, its channels; any other app, a section per row it
        // publishes for the home screen. Each can be turned off (see pickSections).
        val tv = !pkg.startsWith(STREMIO) && isTvApp(pkg)
        val own = when {
            pkg.startsWith(STREMIO) || !readsListings() -> emptyList()
            tv -> tunerSections(pkg, hidden)
            else -> appSections(pkg, hidden)
        }
        val all = own + addonSections(pkg, hidden) + titleSections(pkg, hidden)
        val off = prefs.getStringSet(K.SECTIONS_OFF, emptySet())!!
        val shown = all.filter { sectionKey(pkg, it) !in off }.map { renamed(pkg, it) }
        ui.post {
            sectionCache = pkg to all
            sectionCounts[pkg] = shown.size
            rowsKnown(pkg, shown.isNotEmpty())
            if (tmdbLastFocusApp != pkg) return@post // focus moved on meanwhile: don't show this app's rows under another
            watchApp = pkg
            if (shown.isEmpty()) { // an app with nothing to recommend shows nothing under the row
                hideWatchRow()
            } else {
                showSections(shown)
                if (tv) fillGuide(pkg)
            }
        }
    }
}

/** One row under the apps: [name] alone in the visible-sections list, [title] ("YouTube: Trending") over the row. */
internal class Section(val key: String, val name: String, val title: String, val items: List<Prog>, val stamp: String? = null) // stamp: stands for the items when they're many (see showSections)

internal fun sectionKey(pkg: String, s: Section) = "$pkg/${s.key}"

internal fun MainActivity.appLabel(pkg: String) = apps.firstOrNull { it.pkg == pkg }?.label ?: pkg

/**
 * An app's rows for the home screen ("Recommended", "Trending", "Watch again"...), each a section in the app's
 * own order, empty ones left out. Runs on the io thread.
 */
internal fun MainActivity.appSections(pkg: String, hidden: Set<String>): List<Section> {
    if (!HomePolicy.supportsTvChannels(android.os.Build.VERSION.SDK_INT)) return emptyList()
    val rows = ArrayList<Pair<Long, String>>() // (channel id, its name)
    contentResolver.query(TvContract.Channels.CONTENT_URI, arrayOf("_id", "display_name", "package_name"), null, null, null)?.use { c ->
        while (c.moveToNext()) if (c.getString(2) == pkg) rows += c.getLong(0) to (c.getString(1) ?: "")
    }
    val label = appLabel(pkg)
    return rows.mapNotNull { (id, name) ->
        val items = readPrograms(TvContract.buildPreviewProgramsUriForChannel(id), hidden).map { it.second }
            .distinctBy { it.intent ?: it.title }.let(::inChannelOrder).take(SECTION_CARDS)
        val shown = name.ifBlank { getString(R.string.section_recommended) }
        if (items.isEmpty()) null else Section(name, shown, getString(R.string.app_section, label, shown), items)
    }
}

/** The names the user gave sections, by sectionKey. */
internal fun MainActivity.sectionNames(): Map<String, String> = runCatching {
    val o = org.json.JSONObject(prefs.getString(K.SECTION_NAMES, null) ?: return emptyMap())
    o.keys().asSequence().associateWith { o.getString(it) }
}.getOrDefault(emptyMap())

/** [s] with the name the user gave it, over the row ("Films: <name>") and in the lists of sections; else [s] itself. */
internal fun MainActivity.renamed(pkg: String, s: Section, names: Map<String, String> = sectionNames()): Section {
    if (!pkg.startsWith(STREMIO)) return s // only Zephyr TV's own apps' rows can be renamed (see appMenu)
    val name = names[sectionKey(pkg, s)] ?: return s
    val title = if (s.title.endsWith(s.name)) s.title.dropLast(s.name.length) + name else name
    return Section(s.key, name, title, s.items, s.stamp)
}

/** Hold OK on an app, Visible sections: its rows under the apps, each on or off. Back applies the choice. */
internal fun MainActivity.pickSections(pkg: String, sections: List<Section>) {
    showPanel()
    toggles(getString(R.string.app_section, appLabel(pkg), getString(R.string.menu_sections)), sections.map { renamed(pkg, it).name to sectionKey(pkg, it) },
        K.SECTIONS_OFF, getString(R.string.state_shown), getString(R.string.state_hidden)) {
        closeLauncherSettings()
        watchKey = null
        queryAppChannels(pkg)
    }
}

/**
 * Hold OK on an app, Rename sections: its rows under the apps, OK on one to give it a name of one's own (or its
 * own back). Back shows the rows with their new names. [focus]: the row to focus, after a rename.
 */
internal fun MainActivity.renameSections(pkg: String, sections: List<Section>, focus: Int = 0) {
    showPanel()
    val names = sectionNames()
    showPage(getString(R.string.app_section, appLabel(pkg), getString(R.string.menu_rename_sections)), sections.mapIndexed { i, s ->
        page(renamed(pkg, s, names).name) { renameSection(pkg, sections, i) }
    }, focus) {
        closeLauncherSettings()
        watchKey = null
        queryAppChannels(pkg)
    }
}

/** One section's name, typed with the remote: Save keeps it (blank: its own), Original name gives its own back. */
private fun MainActivity.renameSection(pkg: String, sections: List<Section>, i: Int) {
    val s = sections[i]
    val key = sectionKey(pkg, s)
    fun save(name: String?) {
        val o = org.json.JSONObject(sectionNames())
        if (name.isNullOrBlank() || name == s.name) o.remove(key) else o.put(key, name)
        prefs.edit().apply { if (o.length() == 0) remove(K.SECTION_NAMES) else putString(K.SECTION_NAMES, o.toString()) }.apply()
        renameSections(pkg, sections, i)
    }
    textPage(renamed(pkg, s).name, renamed(pkg, s).name, s.name, getString(R.string.section_rename_intro, s.name), Typing.SENTENCE,
        back = { renameSections(pkg, sections, i) }) { typed ->
        listOf(
            page(getString(R.string.dialog_save)) { save(typed()) },
            page(getString(R.string.section_original_name)) { save(null) },
        )
    }
}

/**
 * A TV app's recommendations are usually its channels, but the database returns them in no useful order (the
 * order they were last written). Sorted by channel number: the channel each card opens (its intent points at
 * content://android.media.tv/channel/<id>), else the channel with the card's name, else a number the title
 * starts with ("1 Rai 1"). Cards without a number follow, by the weight the app gave them. Runs on the io thread.
 */
internal fun MainActivity.inChannelOrder(programs: List<Prog>): List<Prog> {
    val byId = HashMap<Long, String>()
    val byName = HashMap<String, String>()
    contentResolver.query(TvContract.Channels.CONTENT_URI, arrayOf("_id", "display_number", "display_name", "type"), null, null, null)?.use { c ->
        while (c.moveToNext()) {
            if (c.getString(3) == TvContract.Channels.TYPE_PREVIEW) continue // home-screen rows, not tuner channels
            val number = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
            byId[c.getLong(0)] = number
            c.getString(2)?.let { byName[it.trim().lowercase()] = number }
        }
    }
    fun number(p: Prog): String? = channelOf(p)?.let(byId::get) ?: byName[p.title.trim().lowercase()]
        ?: Regex("""^\s*(\d+(?:[.-]\d+)?)\b""").find(p.title)?.groupValues?.get(1)
    return programs.sortedWith(compareBy<Prog>({ channelKey(number(it)) }).thenByDescending { it.weight })
}

/** The tuner channel a card opens (its intent points at content://android.media.tv/channel/<id>), if any. */
internal fun channelOf(p: Prog): Long? =
    p.intent?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME).data }.getOrNull() }
        ?.takeIf { it.authority == TvContract.AUTHORITY && it.pathSegments.firstOrNull() == "channel" }
        ?.lastPathSegment?.toLongOrNull()

/**
 * The TV app: the one Android opens a channel link (content://android.media.tv/channel/<id>) with. Found the
 * same way on any TV, whatever it's called (Sony's com.sony.dtv.tvx, Google's Live Channels, ...).
 */
internal fun MainActivity.isTvApp(pkg: String): Boolean {
    val uri = TvContract.buildChannelUri(1)
    return listOf(Intent(Intent.ACTION_VIEW, uri), Intent(Intent.ACTION_VIEW).setType(TvContract.Channels.CONTENT_ITEM_TYPE)).any { intent ->
        packageManager.queryIntentActivities(intent, 0).any { it.activityInfo.packageName == pkg }
    }
}

/**
 * The TV app's sections: the TV's own channel list, a section per tuner (aerial, satellite...), each in number
 * order from the first. Not the cards the app publishes, which are only the handful of channels it picked
 * (Sony's: about 20, often without 1-3) and look different on every TV; these all share one design (see
 * [watchCard]). Empty without a tuner. Runs on the io thread.
 */
internal class TunerChannel(val id: Long, val input: String, val number: String, val name: String)

/**
 * The TV's tuner channels, as many as it lists to apps (not its home-screen rows, nor those it hides). Some come
 * twice (same number and name, from two broadcasts of it): once is enough. Runs on the io thread.
 */
internal fun MainActivity.tunerChannels(): List<TunerChannel> {
    val all = ArrayList<TunerChannel>()
    contentResolver.query(TvContract.Channels.CONTENT_URI, arrayOf("_id", "input_id", "display_number", "display_name", "browsable", "type"), null, null, null)?.use { c ->
        while (c.moveToNext()) {
            if (c.getString(5) == TvContract.Channels.TYPE_PREVIEW || c.getInt(4) == 0) continue // home-screen rows, hidden channels
            all += TunerChannel(c.getLong(0), c.getString(1) ?: "", c.getString(2)?.takeIf { it.isNotBlank() } ?: continue, c.getString(3) ?: continue)
        }
    }
    return all.distinctBy { it.number to it.name }
}

/** A channel's logo: the TV's own, else iptv-org's from [logos] (see ChannelLogos). */
internal fun MainActivity.channelLogo(ch: TunerChannel, logos: Map<String, String>) = logo(ch.id) ?: ChannelLogos.match(logos, ch.name)

internal fun MainActivity.tunerSections(pkg: String, hidden: Set<String>): List<Section> {
    val all = tunerChannels()
    val label = appLabel(pkg)
    val tvInputs = getSystemService(TvInputManager::class.java)
    // Only names and numbers here, so the channels show at once: each card finds its logo when it's built (see
    // channelArt), and what's on comes after (fillGuide). The logo table and the guide are fetched when due.
    if (channelLogosOn() && ChannelLogos.due(filesDir, channelCountry())) updateChannelLogos(pkg)
    epgUrl()?.let { if (Epg.due(filesDir, it)) updateEpg(pkg) }
    return all.filter { hideKey(pkg, it.name) !in hidden }.groupBy { it.input }.values
        .map { it.sortedBy { ch -> channelKey(ch.number) } }.sortedBy { channelKey(it.first().number) }
        .map { channels ->
            val input = channels.first().input
            val name = runCatching { tvInputs?.getTvInputInfo(input)?.loadLabel(this)?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: getString(R.string.section_channels)
            Section(input, name, getString(R.string.app_section, label, name), channels.map {
                Prog(it.name, it.number.trimStart('0').ifEmpty { it.number }, 0, null,
                    Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(it.id)).toUri(Intent.URI_INTENT_SCHEME), pkg, badge = it.number, channelId = it.id)
            })
        }
}

internal class OnNow(val title: String, val progress: Int)

/** The iptv-org logo table for this TV's channels, or none when that's turned off (read from disk once). */
internal fun MainActivity.channelLogoTable() =
    if (channelLogosOn()) ChannelLogos.saved(filesDir, channelCountry()).orEmpty() else emptyMap()

/**
 * What's on, filled in under the TV app's channel cards once they show: the TV's own guide, else the one set in
 * Zephyr TV settings. The cards built so far change in place (focus stays), and so do those still to come.
 */
internal fun MainActivity.fillGuide(pkg: String) = io.execute {
    val guide = epgUrl()?.let { Epg.saved(filesDir, it) }
    val onNow = programsOnNow()
    if (guide == null && onNow.isEmpty()) return@execute
    val byId = tunerChannels().mapNotNull { ch ->
        (onNow[ch.id] ?: guide?.let { g -> Epg.now(g, ch.name)?.let { (title, progress) -> OnNow(title, progress) } })?.let { ch.id to it }
    }.toMap()
    ui.post {
        if (watchApp != pkg) return@post
        for (row in cardRows()) {
            val cards = row.getTag(R.id.watchScroll) as? RowCards ?: continue
            cards.items = cards.items.map { p ->
                val now = p.channelId?.let(byId::get) ?: return@map p
                // Under the card: the number, then what's on, its bar how far in it is.
                Prog(p.title, "${p.sub} · ${now.title}", now.progress, p.image, p.intent, p.pkg, p.weight, p.badge, channelId = p.channelId)
            }
            for (i in 0 until row.childCount) {
                val col = row.getChildAt(i) as? ViewGroup ?: continue
                val p = cards.items.getOrNull(i)?.takeIf { it.progress > 0 } ?: continue
                col.getChildAt(0)?.setTag(R.id.watchRow, p)
                (col.getChildAt(1) as? ProgressBar)?.apply { progress = p.progress; visibility = View.VISIBLE }
                (col.getChildAt(3) as? TextView)?.text = p.sub
            }
        }
    }
}

/**
 * What each tuner channel is showing now, by channel id: the guide the TV gets with the broadcast (DVB EIT) and keeps
 * in its TV listings. Nothing for channels it has no guide for. Runs on the io thread.
 */
internal fun MainActivity.programsOnNow(): Map<Long, OnNow> {
    val now = System.currentTimeMillis()
    val out = HashMap<Long, OnNow>()
    runCatching {
        // A time range in the address, the one filter the TV listings take from an app (a selection is refused).
        val uri = TvContract.Programs.CONTENT_URI.buildUpon()
            .appendQueryParameter("start_time", now.toString()).appendQueryParameter("end_time", now.toString()).build()
        contentResolver.query(uri, arrayOf("channel_id", "title", "start_time_utc_millis", "end_time_utc_millis"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getLong(2) > now || c.getLong(3) <= now) continue
                val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val start = c.getLong(2)
                val end = c.getLong(3)
                val progress = if (end > start) ((now - start) * 100 / (end - start)).toInt().coerceIn(1, 100) else 0
                out[c.getLong(0)] = OnNow(title, progress)
            }
        }
    }.onFailure { warn("TV guide not readable ($it)") }
    return out
}

internal fun MainActivity.channelLogosOn() = prefs.getBoolean(K.CHANNEL_LOGOS, true)

/** The address of the XMLTV guide set in Zephyr TV settings, or null for none. */
internal fun MainActivity.epgUrl() = prefs.getString(K.EPG_URL, null)?.takeIf { it.isNotBlank() }

/**
 * Fetches the guide in the background; once it's in, the TV app's sections are built again if it's still the app
 * in focus. [done], when given, hears how many channels it has programs for (null: it failed).
 */
internal fun MainActivity.updateEpg(pkg: String?, done: ((Int?) -> Unit)? = null) {
    val url = epgUrl() ?: return
    longIo.execute { // a big guide can take minutes: not on the threads pages wait for
        val n = Epg.update(filesDir, url)
        ui.post {
            done?.invoke(n)
            if (n != null) rowsAgain(pkg)
        }
    }
}

/** The TV's country, for its channels' logos: its own setting, else the one the TMDB language names. */
internal fun MainActivity.channelCountry() = TmdbClient.region(tmdbLang())

/**
 * Fetches the channel logo table in the background; once it's in, the TV app's sections are built again if it's
 * still the app in focus. [done], when given, hears how many channel names the table has (null: it failed).
 */
internal fun MainActivity.updateChannelLogos(pkg: String?, done: ((Int?) -> Unit)? = null) = longIo.execute { // 14 MB to read
    val n = ChannelLogos.update(filesDir, channelCountry())
    // A new table each month (or by hand): the logos themselves are fetched again with it, in case they changed.
    if (n != null) Images.clear(Images.Kind.LOGO)
    ui.post {
        done?.invoke(n)
        if (n != null) rowsAgain(pkg)
    }
}

/** The channel's logo, if the TV has one (many tuners store none). */
private fun MainActivity.logo(id: Long): String? {
    val uri = TvContract.buildChannelLogoUri(id)
    return runCatching { contentResolver.openAssetFileDescriptor(uri, "r")?.use { uri.toString() } }.getOrNull()
}

/** Cards per app section; with several sections, more would mean scrolling a long way past ones not wanted. */
private const val SECTION_CARDS = 20

/**
 * Hides the row under the apps for an app with no recommendations. The old cards stay (invisible) so the row
 * keeps its height and the apps row doesn't jump as focus moves along it; the next list rebuilds them.
 */
internal fun MainActivity.hideWatchRow() {
    watchKey = null
    moreSections.removeAllViews()
    if (watchScroll.visibility == View.GONE) return
    watchTitle.visibility = View.INVISIBLE
    watchScroll.visibility = View.INVISIBLE
}

/**
 * While [pkg]'s rows load: as many rows of empty, pulsing cards as it showed last time (one the first time), so
 * the previous app's cards don't linger under it. An app known to have nothing hides the row at once instead.
 */
internal fun MainActivity.showLoading(pkg: String) {
    if (pkg == watchApp) return // its rows are already there (e.g. focus back up from them)
    watchApp = null
    val rows = (sectionCounts[pkg] ?: 1).coerceAtMost(3) // the screen shows three at most
    if (rows == 0) return hideWatchRow()
    val key = "loading:$pkg/$rows"
    if (watchKey == key) return
    watchKey = key
    watchRow.removeAllViews()
    moreSections.removeAllViews()
    watchTitle.visibility = View.VISIBLE
    watchScroll.visibility = View.VISIBLE
    watchTitle.text = if (pkg == CW_PKG) getString(R.string.continue_watching) else appLabel(pkg)
    repeat(rows) { r ->
        val row = if (r == 0) watchRow else sectionRow(" ")
        repeat(4) { row.addView(placeholderCard(it > 0, it * 120L)) }
    }
}

/** An empty card the size of [watchCard]'s, pulsing (starting [delay] ms in, so a row ripples) until it's removed. */
internal fun MainActivity.placeholderCard(gapBefore: Boolean, delay: Long): View {
    val gap = dp(GRID_GAP)
    val n = cardsAcross()
    val w = (resources.displayMetrics.widthPixels - dp(96) - gap * (n - 1)) / n
    fun shape(color: Int, corner: Int) = GradientDrawable().apply { cornerRadius = dp(corner).toFloat(); setColor(color) }
    val bar = if (dark) 0x33FFFFFF else 0x26000000
    // A text line's height (an invisible label), with a bar across part of it.
    fun line(size: Float, top: Int, width: Float) = FrameLayout(this).apply {
        addView(label(" ", size).apply { visibility = View.INVISIBLE; setPadding(0, dp(top), 0, 0) })
        addView(View(context).apply { background = shape(bar, 4) },
            FrameLayout.LayoutParams((w * width).toInt(), dp(size.toInt() * 2 / 3), Gravity.BOTTOM).apply { bottomMargin = dp(4) })
    }
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (gapBefore) marginStart = gap
        }
        addView(View(context).apply { background = shape(if (dark) 0xFF18181A.toInt() else 0xFFDDE1E5.toInt(), 8) },
            LinearLayout.LayoutParams(w, w * 9 / 16))
        addView(View(context), LinearLayout.LayoutParams(w, dp(3)).apply { topMargin = dp(6) }) // the progress bar's room
        addView(line(14f, 6, 0.7f))
        addView(line(12f, 2, 0.4f))
    }
    val pulse = ObjectAnimator.ofFloat(col, View.ALPHA, 1f, 0.4f).apply {
        duration = 700
        startDelay = delay
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
    }
    col.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = pulse.start()
        override fun onViewDetachedFromWindow(v: View) = pulse.cancel()
    })
    return col
}

internal fun MainActivity.sourceIcon(pkg: String?): Drawable? {
    if (pkg == null) return null
    // An add-on's tile (or the IPTV app's): its icon, as the apps row shows it.
    if (pkg.startsWith(STREMIO)) return apps.firstOrNull { it.pkg == pkg }?.icon?.constantState?.newDrawable(resources)?.mutate()?.apply {
        if (this is BitmapDrawable) colorFilter = GREYSCALE else setTint(fg)
    }
    LOGOS[pkg]?.takeIf { !it.second }?.let { return getDrawable(it.first)!!.mutate().apply { setTint(fg) } }
    return try {
        packageManager.getApplicationIcon(pkg).mutate().apply { colorFilter = GREYSCALE }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

internal fun MainActivity.timeLeft(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) getString(R.string.time_left_hr_min, min / 60, min % 60) else getString(R.string.time_left_min, min)
}

/** The Play queue tile's row: what's playing now (see nowPlaying) first, then Continue Watching, in one row. */
internal fun MainActivity.showWatchNext(list: List<Prog>, playing: List<Prog> = emptyList()) = showSections(listOfNotNull(
    (playing + list).takeIf { it.isNotEmpty() }?.let { getString(R.string.continue_watching).let { t -> Section("", t, t, it) } },
))

/**
 * The rows under the apps, one per section. The first is the row in the layout; any more are built under it (see
 * BottomSection), and the page slides up to them as focus goes down (keepInView).
 */
internal fun MainActivity.showSections(sections: List<Section>) {
    // What's shown, to tell whether it changed. An add-on's thousands of channels go by their stamp instead.
    val key = sections.joinToString("||") { s -> s.title + "#" + (s.stamp ?: s.items.joinToString("|") { "${it.title}/${it.sub}/${it.progress}/${it.image}" }) }
    if (key == watchKey) { // unchanged: keep views (and focus) as they are, just fill in missing posters
        sections.firstOrNull()?.let { watchTitle.text = it.title } // a caller may have set its own meanwhile
        retryPosters()
        return
    }
    watchKey = key
    val focused = currentFocus?.takeIf { it.getTag(R.id.watchRow) is Prog }
    val focusedRow = cardRows().indexOf(focused?.parent?.parent)
    val focusedAt = (focused?.parent?.parent as? ViewGroup)?.indexOfChild(focused.parent as View) ?: -1
    val scrolled = (focused?.parent?.parent?.parent as? HorizontalScrollView)?.scrollX ?: 0
    // The row that had focus, as far as the new list goes; it and the rows next to it get their cards at once.
    val at = if (focusedRow >= 0) focusedRow.coerceAtMost(sections.size - 1) else -1
    watchRow.removeAllViews()
    moreSections.removeAllViews()
    val visible = if (sections.isEmpty()) View.GONE else View.VISIBLE
    watchTitle.visibility = visible
    watchScroll.visibility = visible
    sections.forEachIndexed { i, s ->
        if (i == 0) watchTitle.text = s.title
        val row = if (i == 0) watchRow else sectionRow(s.title)
        row.setTag(R.id.watchScroll, RowCards(s.items))
        // The first page of the first rows; the rest as focus nears the end of a row, or the rows above them (see
        // watchCard). An IPTV add-on can have a hundred rows: built all at once, they'd hold up the screen.
        // A list that changes under focus (names in the TV's language, catalogs read again): the focused row up to
        // its card, and the rows either side, now. Built later, focus would find no card there and jump to the apps.
        if (at >= 0 && i in at - 1..at + ROWS_AHEAD) {
            val cards = row.getTag(R.id.watchScroll) as RowCards
            do moreCards(row, now = true) while (i == at && cards.built <= focusedAt && cards.built < cards.items.size)
        } else if (i < ROWS_AHEAD + 1) moreCards(row)
    }
    if (at >= 0) {
        val row = cardRows()[at]
        ((row.getChildAt(focusedAt.coerceAtMost(row.childCount - 1)) as? LinearLayout)?.getChildAt(0)
            ?: appRow.getChildAt(0))?.requestFocus()
        // Where the row was scrolled to, so the cards stay where they were under the focus.
        (row.parent as? HorizontalScrollView)?.let { sv -> sv.post { sv.scrollTo(scrolled, 0) } }
    }
}

/** A row's cards: all of them, and how many are built (or on their way). */
internal class RowCards(var items: List<Prog>, var built: Int = 0)

/** Cards built at a time in a row: a page more each time focus nears its end, so long rows (all the channels) open at once. */
private const val PAGE = 10

/** Rows built below the one in focus: those further down get their cards as focus comes near. */
private const val ROWS_AHEAD = 2

/**
 * Builds the next page of [row]'s cards (see RowCards). ~16 ms per card and only four show at once: four per frame,
 * not a page in one; [now], the whole page at once.
 */
internal fun MainActivity.moreCards(row: ViewGroup, now: Boolean = false) {
    val cards = row.getTag(R.id.watchScroll) as? RowCards ?: return
    val until = minOf(cards.built + PAGE, cards.items.size)
    if (until <= cards.built) return
    val key = watchKey
    fun add(from: Int) {
        if (watchKey != key || row.getTag(R.id.watchScroll) !== cards) return // a newer list replaced this one
        val to = if (now) until else minOf(from + 4, until)
        cards.items.subList(from, to).forEach { row.addView(watchCard(it, row.childCount > 0)) }
        if (to < until) ui.post { add(to) }
    }
    val from = cards.built
    cards.built = until
    add(from)
}

/** A section after the first: its title and an empty row, styled like the first one's in the layout. */
internal fun MainActivity.sectionRow(title: String): ViewGroup {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
    }
    moreSections.addView(TextView(this).apply {
        text = title
        textSize = 14f
        alpha = watchTitle.alpha
        typeface = watchTitle.typeface
        setTextColor(watchTitle.textColors)
        setShadowLayer(watchTitle.shadowRadius, watchTitle.shadowDx, watchTitle.shadowDy, watchTitle.shadowColor)
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (watchTitle.layoutParams as ViewGroup.MarginLayoutParams).topMargin
    })
    moreSections.addView(SnapScrollView(this, null).apply {
        clipToPadding = false
        isHorizontalScrollBarEnabled = false
        setPadding(watchScroll.paddingLeft, watchScroll.paddingTop, watchScroll.paddingRight, watchScroll.paddingBottom)
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }, LinearLayout.LayoutParams(watchScroll.layoutParams as LinearLayout.LayoutParams))
    return row
}

/** The card rows under the apps, top to bottom: the first section's, then any more. */
internal fun MainActivity.firstCard(): View? = (watchRow.getChildAt(0) as? ViewGroup)?.getChildAt(0)

internal fun MainActivity.cardRows(): List<ViewGroup> = listOf<ViewGroup>(watchRow) +
    (0 until moreSections.childCount).mapNotNull { ((moreSections.getChildAt(it) as? ViewGroup)?.getChildAt(0) as? ViewGroup) }

/** In [row], the card nearest across the screen to [from] (a card in the row above or below). */
internal fun MainActivity.nearestCard(row: ViewGroup, from: View): View? {
    fun mid(v: View) = IntArray(2).also { v.getLocationOnScreen(it) }[0] + v.width / 2
    val x = mid(from)
    return (0 until row.childCount).map { row.getChildAt(it) }.minByOrNull { Math.abs(mid(it) - x) }?.let { (it as? ViewGroup)?.getChildAt(0) }
}

/**
 * Four posters across, each two apps-row columns wide. Progress sits on the artwork's bottom edge;
 * title and details (source, episode, time left) go underneath.
 */
internal fun MainActivity.watchCard(p: Prog, gapBefore: Boolean): View {
    val gap = dp(GRID_GAP)
    val fixture=MatchCardLayout.isFixture(p.image)
    val n = MatchCardLayout.columns(p.image,cardsAcross())
    val w = (resources.displayMetrics.widthPixels - dp(96) - gap * (n - 1)) / n
    // Standard 16:9 aspect ratio prevents cutting off channel numbers and provider badges
    val cardHeight = w * 9 / 16
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (gapBefore) marginStart = gap
        }
    }
    val img = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(if (dark) 0xFF18181A.toInt() else 0xFFDDE1E5.toInt())
    }
    // Long titles scroll while their poster is focused instead of staying cut off.
    val title = label(p.title, if(fixture)20f else 14f).apply {
        setPadding(0, dp(6), 0, 0)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
    }
    val frame = FrameLayout(this)
    var details: TextView? = null // the line under the title, set below; scrolls too (a channel's program)
    // A channel's streams looked for once its card stays picked a moment (not each card passed over): OK then plays.
    val warm = p.addon?.let { c -> Runnable { if (frame.isFocused) warmChannel(c) } }
    style(frame, dp(8), card = true, lift = 1.1f) { focused ->
        title.isSelected = focused
        details?.isSelected = focused
        warm?.let { ui.removeCallbacks(it); if (focused) ui.postDelayed(it, WARM_MS) }
        if (focused) {
            lastCard = frame
            showRowLabel(frame, false)
            // Three from the end of what's built: the next page, so moving on never waits.
            (col.parent as? ViewGroup)?.let { row ->
                if (row.indexOfChild(col) >= row.childCount - 3) moreCards(row)
                // And the rows below, before focus goes down to them.
                val rows = cardRows()
                val at = rows.indexOf(row)
                if (at >= 0) rows.drop(at + 1).take(ROWS_AHEAD).forEach { r -> if ((r.getTag(R.id.watchScroll) as? RowCards)?.built == 0) moreCards(r) }
            }
        }
    }
    frame.contentDescription = p.title
    frame.addView(img, MATCH_PARENT, MATCH_PARENT)
    // A TV channel: the same card on every TV, whatever the TV app's own look. Its logo fills it, large in the
    // middle on a blur of itself (its name and number are under the card); with no logo, or until it's in, the
    // number and name instead.
    // The logos to try in turn: the card's own (an add-on's), the TV's, then Zephyr TV's own by name.
    if(fixture) fillArt(frame,w,cardHeight,p.image,null,null,p.title)
    else if (p.badge != null) channelArt(frame, w, cardHeight, p.badge, p.title) {
        listOfNotNull(p.image, p.channelId?.let { id -> logo(id) }, ChannelLogos.match(channelLogoTable(), p.title)).distinct()
    }
    else if(p.vod==null) fillArt(frame,w,cardHeight,p.image,null,null,p.title)
    if (p.vod != null) fillArt(frame, w, cardHeight, p.image, p.logo, p.poster, p.title, p.vod.fallback)
    if(!fixture && p.addon!=null && p.sub.isNotBlank()) frame.addView(TextView(this).apply {
        text=p.sub;textSize=13f;setTextColor(Color.WHITE);maxLines=2
        ellipsize=TextUtils.TruncateAt.END;setPadding(dp(10),dp(6),dp(10),dp(6))
        setBackgroundColor(0xDD0B1424.toInt())
    },FrameLayout.LayoutParams(MATCH_PARENT,FrameLayout.LayoutParams.WRAP_CONTENT,Gravity.BOTTOM))
    // A film still only in cinemas (see Releases): a small rounded tag in the bottom right corner.
    if (p.cinema) frame.addView(TextView(this).apply {
        text = getString(R.string.badge_cinema)
        textSize = 10f
        setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(6), dp(2), dp(6), dp(2))
        background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xCC000000.toInt()) }
    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
        setMargins(0, 0, dp(6), dp(6))
    })
    frame.setOnClickListener { p.channelPage?.let { showChannel(it) } ?: p.vod?.let { openTitle(it) } ?: p.addon?.let {
        // The row's channels, for CH+ and CH- in the player, when it's a row of them (not Continue Playing).
        val row = ((col.parent as? ViewGroup)?.getTag(R.id.watchScroll) as? RowCards)?.items
            ?.takeIf { items -> items.all { it.addon != null } }?.mapNotNull { it.addon }
        playAddonChannel(p, row)
    } ?: open(p) }
    frame.setTag(R.id.watchRow, p) // holding OK hides it (see onHold)
    col.addView(frame, LinearLayout.LayoutParams(w, cardHeight))
    // Thin bar under the poster, card width. Kept (invisible) when there's no progress so titles line up.
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = p.progress
        progressDrawable = progressBarDrawable()
        visibility = if (p.progress > 0) View.VISIBLE else View.INVISIBLE
    }
    col.addView(bar, LinearLayout.LayoutParams(w, dp(3)).apply { topMargin = dp(6) })
    if(!fixture) col.addView(title)
    val icon = sourceIcon(p.icon ?: p.pkg)
    if (!fixture && (p.sub.isNotEmpty() || icon != null || p.addon != null)) {
        col.addView(label(p.sub, 12f).apply {
            details = this
            alpha = 0.8f
            setPadding(0, dp(2), 0, 0)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            icon?.let {
                it.setBounds(0, 0, dp(12), dp(12))
                setCompoundDrawablesRelative(it, null, null, null)
                compoundDrawablePadding = dp(6)
            }
        })
    }
    if (p.badge == null && p.vod == null) loadPoster(img, p.image)
    // An add-on's channel: what's on as the player shows it (its times, so its bar), once the add-on tells.
    p.addon?.let { c ->
        addonOnNow(c) { now, progress ->
            details?.text = now
            bar.progress = progress
            bar.visibility = View.VISIBLE
        }
    }
    return col
}

/**
 * What a title's or a channel's picture at the top of the panel lies on, until its art is in (and under a channel
 * logo's half-clear blur): Glass a smoked tint, so the pane's glass shows through as on the settings pages.
 */
internal fun MainActivity.artGround() = if (glass) 0x592C2C34 else if (dark) 0xFF18181A.toInt() else 0xFFDDE1E5.toInt()

/**
 * A TV channel's picture in [frame] ([w] by [h]): its [logo] (found on a background thread) large in the middle on a
 * blur of itself; with no logo, or until it's in, its [number] and [name] instead.
 */
internal fun MainActivity.channelArt(frame: FrameLayout, w: Int, h: Int, number: String, name: String, logo: () -> List<String>) {
    val text = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        if (number.isNotEmpty()) addView(label(number, 30f).apply { gravity = Gravity.CENTER }) // an add-on's channels have none
        addView(label(name, 13f).apply { gravity = Gravity.CENTER; alpha = 0.8f; setPadding(dp(12), dp(4), dp(12), 0) })
    }
    frame.addView(text, MATCH_PARENT, MATCH_PARENT)
    posterIo.execute {
        // Looked up here, off the UI thread, and tried in turn: the first that loads is the one.
        // Kept on disk (see Images) and in memory: a logo is downloaded once, not each time its card is.
        val (uri, bmp) = logo().firstNotNullOfOrNull { uri ->
            (posters.get(uri) ?: try {
                val file = Images.file(uri, Images.Kind.LOGO)
                decode(android.net.Uri.fromFile(file).toString(), alpha = true)?.also { posters.put(uri, it) }
            } catch (e: Exception) {
                warn("channel logo failed: $uri ($e)")
                null
            })?.let { uri to it }
        } ?: return@execute
        // A composed fixture is already a complete 16:9 card, not an individual channel crest.
        if (uri.startsWith("file:///") && "/match-posters/" in uri) {
            ui.post {
                text.visibility = View.GONE
                frame.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            return@execute
        }
        val blur = softBlur(bmp)
        ui.post {
            text.visibility = View.GONE
            frame.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.55f
                setImageBitmap(blur)
            }, frame.indexOfChild(text), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            frame.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(w / 6, h / 5, w / 6, h / 5)
                setImageBitmap(bmp)
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }
}

/**
 * A search result's picture, always filling [frame] ([w] by [h]), from the best art there is: the [backdrop]
 * (with the [logo] over it when the title isn't written on it); else the [poster] whole in the middle, on a blur
 * of itself; else a tile of colour (its hue from the [title]) with the logo, or the title, in the middle. Each
 * picture that won't load gives way to [fallback]'s (background, logo, poster), then to the next kind of art.
 */
internal fun MainActivity.fillArt(frame: FrameLayout, w: Int, h: Int, backdrop: String?, logo: String?, poster: String?, title: String,
                                  fallback: Triple<String?, String?, String?>? = null) {
    // Drawn in a frame of its own: art that won't load is drawn again in it, the rest of [frame] left as it is.
    val art = FrameLayout(this)
    frame.addView(art, MATCH_PARENT, MATCH_PARENT)
    drawArt(art, w, h, listOfNotNull(backdrop, fallback?.first).distinct(), listOfNotNull(logo, fallback?.second).distinct(),
        listOfNotNull(poster, fallback?.third).distinct(), title)
}

private fun MainActivity.drawArt(frame: FrameLayout, w: Int, h: Int, backdrops: List<String>, logos: List<String>, posters: List<String>, title: String) {
    // The first of [uris] that loads; [failed] when none does.
    fun load(v: ImageView, uris: List<String>, blur: Boolean = false, failed: () -> Unit = {}) = posterIo.execute {
        val bmp = uris.firstNotNullOfOrNull { uri -> runCatching { art(uri, alpha = uri in logos) }.getOrNull() }
            ?: return@execute run { ui.post(failed) }
        val shown = if (blur) softBlur(bmp) else bmp
        ui.post { v.setImageBitmap(shown) }
    }
    fun again(backdrops: List<String>, posters: List<String>) {
        frame.removeAllViews()
        drawArt(frame, w, h, backdrops, logos, posters, title)
    }
    when {
        backdrops.isNotEmpty() -> {
            frame.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; load(this, backdrops) { again(emptyList(), posters) } },
                MATCH_PARENT, MATCH_PARENT)
            if (logos.isNotEmpty()) addTitleLogo(frame, logos, w, h)
        }
        posters.isNotEmpty() -> {
            frame.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; load(this, posters, blur = true) }, MATCH_PARENT, MATCH_PARENT)
            frame.addView(View(this).apply { setBackgroundColor(0x40000000) }, MATCH_PARENT, MATCH_PARENT)
            frame.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(0, dp(6), 0, dp(6))
                load(this, posters) { again(emptyList(), emptyList()) }
            }, MATCH_PARENT, MATCH_PARENT)
        }
        else -> {
            val hue = (title.hashCode() and 0x7FFFFFFF) % 360f
            frame.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                    Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.42f)), Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.55f, 0.18f))))
            }, MATCH_PARENT, MATCH_PARENT)
            if (logos.isNotEmpty()) frame.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; load(this, logos) },
                FrameLayout.LayoutParams(w * 3 / 4, h / 2, Gravity.CENTER))
            else frame.addView(TextView(this).apply {
                text = title
                textSize = 18f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(16), 0, dp(16), 0)
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }
}

/**
 * A soft blur of [src] for a card's background, on any Android (RenderEffect needs 12): shrunk to 32 pixels across,
 * box-blurred three times each way (close to a Gaussian), and left small; drawn large, it's smoothed further.
 */
internal fun softBlur(src: Bitmap): Bitmap {
    val w = 32
    val h = (w * src.height / src.width.coerceAtLeast(1)).coerceIn(8, 64)
    val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
    val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
    val r = 3
    fun pass(horizontal: Boolean) {
        val out = IntArray(px.size)
        val (lines, len) = if (horizontal) h to w else w to h
        for (line in 0 until lines) for (i in 0 until len) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val j = (i + k).coerceIn(0, len - 1)
                val c = px[if (horizontal) line * w + j else j * w + line]
                a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            val n = 2 * r + 1
            out[if (horizontal) line * w + i else i * w + line] = (a / n shl 24) or (rr / n shl 16) or (g / n shl 8) or (b / n)
        }
        out.copyInto(px)
    }
    repeat(3) { pass(true); pass(false) }
    small.setPixels(px, 0, w, 0, 0, w, h)
    return small
}

/** A search result on plain art: the title's logo (the first of [uris] that loads) over it, bottom left, on a shade so a light logo still reads. */
internal fun MainActivity.addTitleLogo(frame: FrameLayout, uris: List<String>, w: Int, h: Int) {
    frame.addView(View(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x99000000.toInt(), 0))
    }, FrameLayout.LayoutParams(MATCH_PARENT, h / 2, Gravity.BOTTOM))
    val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
    frame.addView(logo, FrameLayout.LayoutParams(w / 2, h / 3, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), 0, 0, dp(10)) })
    posterIo.execute { uris.firstNotNullOfOrNull { runCatching { art(it, alpha = true) }.getOrNull() }?.let { ui.post { logo.setImageBitmap(it) } } }
}

internal fun MainActivity.progressBarDrawable(): Drawable {
    fun bar(color: Int) = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color) }
    return LayerDrawable(arrayOf(
        bar(if (dark) 0x40FFFFFF else 0x26000000),
        ClipDrawable(bar(fg), Gravity.START, ClipDrawable.HORIZONTAL),
    )).apply {
        setId(0, android.R.id.background)
        setId(1, android.R.id.progress)
    }
}

internal fun MainActivity.open(p: Prog) {
    prefs.edit().putLong("t_${p.pkg}", System.currentTimeMillis()).apply()
    try {
        if(ExternalResume.allowed(p.pkg) && p.progress>0) {
            val session=mediaSessions().firstOrNull { it.packageName==p.pkg &&
                it.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)==p.title }
            val saved=ExternalResume.all(filesDir).firstOrNull { it.pkg==p.pkg && it.title==p.title }
            if(saved?.uri==null && session?.sessionActivity!=null) { session.sessionActivity!!.send();return }
        }
        if(p.pkg?.startsWith("org.smarttube")==true) {
            val uri=runCatching { Intent.parseUri(p.intent.orEmpty(),Intent.URI_INTENT_SCHEME).data }.getOrNull()
            if(uri?.host=="www.youtube.com" && uri.path=="/watch" && Regex("[A-Za-z0-9_-]{11}").matches(uri.getQueryParameter("v").orEmpty()))
                ExternalResume.rememberLink(filesDir,p.title,uri.toString())
        }
        startActivity(programIntent(p) ?: throw IllegalArgumentException("unsafe or missing intent"))
    } catch (e: Exception) {
        p.pkg?.let { packageManager.getLeanbackLaunchIntentForPackage(it) }?.let { startActivity(it) }
    }
}

/**
 * A card's intent, written by another app into the TV listings, made safe to start as Zephyr TV. One naming its
 * target may only name the app that published it (never Zephyr TV itself, never a third app); one that doesn't is
 * left to Android, which won't resolve it to anything not exported (a TV input's channel link is opened by the
 * TV's own app, not always the publisher). Either way it loses URI permission grants and any selector that
 * would swap in a different target. Null when it names a wrong target or doesn't parse.
 */
internal fun MainActivity.programIntent(p: Prog): Intent? {
    val intent = runCatching { Intent.parseUri(p.intent ?: return null, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
    val target = intent.component?.packageName ?: intent.`package`
    if (target == packageName || (p.pkg != null && target != null && target != p.pkg)) return null
    intent.selector = null
    intent.flags = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
    return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

internal fun MainActivity.loadPoster(v: ImageView, uri: String?) {
    if (uri == null) return
    posters.get(uri)?.let { v.setImageBitmap(it); fitPoster(v, it); return }
    v.tag = uri
    if (!loading.add(uri)) return // already on its way
    posterIo.execute {
        val bmp = try {
            art(uri)
        } catch (e: Exception) {
            warn("poster failed: $uri ($e)") // retried by retryPosters()
            null
        } finally {
            loading.remove(uri)
        } ?: return@execute
        posters.put(uri, bmp)
        ui.post {
            if (v.tag == uri) {
                v.setImageBitmap(bmp)
                fitPoster(v, bmp)
            }
        }
    }
}

/**
 * Film and show art fills the card, cropped to its shape: also a landscape picture on a landscape card, like
 * YouTube's 4:3 thumbnails, whose black bars the crop takes off. Art of another shape (a poster or a square cover
 * on a wide card), or that sits on a plain background (a TV app's channel cards: logo and name on a flat colour),
 * is shown whole instead, inset from the edges, on a blur of itself or its own background colour.
 */
internal fun MainActivity.fitPoster(v: ImageView, bmp: Bitmap) {
    val ratio = bmp.width.toFloat() / bmp.height
    val card = (v.parent as? View)?.layoutParams?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
    // Plain background: the corners and the middle of each edge all the same colour (a film still rarely is).
    val r = bmp.width - 1
    val b = bmp.height - 1
    val edge = listOf(0 to 0, r / 2 to 0, r to 0, 0 to b / 2, r to b / 2, 0 to b, r / 2 to b, r to b).map { (x, y) -> bmp.getPixel(x, y) }
    fun near(c1: Int, c2: Int) = Math.abs(Color.red(c1) - Color.red(c2)) + Math.abs(Color.green(c1) - Color.green(c2)) + Math.abs(Color.blue(c1) - Color.blue(c2)) < 24
    val flat = edge.all { Color.alpha(it) == 255 && near(it, edge[0]) }
    val sameShape = Math.abs(ratio / card - 1f) < 0.08f || (card > 1.2f && ratio in 1.2f..2.4f)
    if (sameShape && !flat) {
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.setPadding(0, 0, 0, 0)
        return
    }
    v.scaleType = ImageView.ScaleType.FIT_CENTER
    v.setPadding(dp(12), dp(10), dp(12), dp(10))
    // A plain background goes on around it; a picture of another shape (Spotify's square covers) sits on a blur of
    // itself, so the card is filled either way.
    v.background = if (flat) android.graphics.drawable.ColorDrawable(edge[0])
        else android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.BitmapDrawable(resources, softBlur(bmp)).apply { isFilterBitmap = true },
            android.graphics.drawable.ColorDrawable(0x33000000),
        ))
}

/** Cards whose poster never arrived (offline, server timeout) try again; loaded ones are left alone. */
internal fun MainActivity.retryPosters() {
    for (row in cardRows()) for (i in 0 until row.childCount) {
        val img = ((row.getChildAt(i) as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ImageView ?: continue
        if (img.drawable == null) loadPoster(img, img.tag as? String)
    }
}

/** Downsample to card size in RGB_565 so a dozen posters stay around 1-2 MB. */
/** [uri]'s picture at about card size; [alpha] keeps its transparency (a logo), at twice the memory. */
/** Images that answered "not there" (404) this session: not asked for again at each row drawn (see art). */
internal val missingArt: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

/**
 * [uri]'s image, decoded: from memory (posters), else from disk (a web image is downloaded once, see Images), else
 * read where it is (a TV app's). Null when it isn't there, remembered for the session; throws on other failures (the
 * network), to be asked again. Network: off the UI thread.
 */
internal fun MainActivity.art(uri: String, alpha: Boolean = false): Bitmap? {
    val key = if (alpha) "$uri#alpha" else uri
    posters.get(key)?.let { return it }
    if (uri in missingArt) return null
    val bmp = try {
        if (uri.startsWith("http")) decode(android.net.Uri.fromFile(Images.file(uri)).toString(), alpha) else decode(uri, alpha)
    } catch (e: java.io.FileNotFoundException) { // what a 404 is, as HttpURLConnection says it
        missingArt += uri
        null
    } ?: return null
    posters.put(key, bmp)
    return bmp
}

internal fun MainActivity.decode(uri: String, alpha: Boolean = false): Bitmap? {
    val bytes = openStream(uri).use { it.readCapped(Http.MAX_IMAGE) }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val readableWidth=if(MatchCardLayout.isFixture(uri))960 else dp(220)
    while (bounds.outWidth / (sample * 2) >= readableWidth) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = if (alpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}
