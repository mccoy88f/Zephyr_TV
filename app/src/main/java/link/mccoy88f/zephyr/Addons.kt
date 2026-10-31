package link.mccoy88f.zephyr

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.tv.TvContract
import android.widget.Toast
import link.mccoy88f.zephyr.MainActivity.Prog
import link.mccoy88f.zephyr.MainActivity.Companion.CINEMETA_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.ADDON_ADD_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.ADDON_ROWS
import link.mccoy88f.zephyr.MainActivity.Companion.SUBMENU

// Stremio add-ons' TV channels on the home screen: where they show (the TV app, one IPTV app, or an app per add-on),
// their rows, and adding and removing add-ons. Functions of MainActivity, like the other topics; the add-ons
// themselves are read by Stremio.

/** App.pkg prefix of the add-ons' tiles: "stremio:iptv" for the IPTV app, "stremio:<add-on id>" for an add-on's own. */
internal const val STREMIO = "stremio:"
internal const val IPTV_PKG = STREMIO + "iptv"
/** The Continue Playing card of the channel last played: hiding it there keeps the channel in its add-on's rows. */
private const val LAST_PKG = STREMIO + "last"

/**
 * The add-ons' own thread, for building their rows: an IPTV add-on has thousands of channels, and the apps' rows
 * (on MainActivity.io) mustn't wait behind them.
 */
internal val addonIo: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()

/** The rows last built under each tile, and what they were built from: built again only when that changes. */
private class Built(val from: String, val sections: List<Section>)
@Volatile private var built: Map<String, Built> = emptyMap()

/** What's on is worked out again this often (the progress under the cards moves on). */
private const val GUIDE_MS = 5 * 60_000L

/** A card that's an add-on's channel: the add-on (its manifest's address) and the channel in it. */
internal class AddonChannel(val addon: String, val item: Stremio.Item)

/**
 * The add-ons' pages of the channels whose cards show, and when each came: what's on with its times, as the player
 * shows it (the catalog says only the title). Asked on a thread of their own, a few at a time.
 */
private val channelPages = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Stremio.Meta>>()
private val pageIo: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(3)

/**
 * What's on [c] as the player shows it, for its card: the add-on's page for the channel, its program and how far
 * in it is. A page from the last few minutes is used while its program is on; else it's asked in the background.
 * [show] hears it on the main thread, only when the page has a program on now.
 */
internal fun MainActivity.addonOnNow(c: AddonChannel, show: (String, Int) -> Unit) {
    val key = "${c.addon}|${c.item.id}"
    fun use(meta: Stremio.Meta): Boolean {
        val progress = Stremio.progress(meta.description) ?: return false
        show(meta.now ?: return false, progress)
        return true
    }
    channelPages[key]?.let { (at, meta) ->
        if (System.currentTimeMillis() - at < GUIDE_MS && (meta.description == null || use(meta))) return
    }
    pageIo.execute {
        val meta = runCatching { Stremio.meta(c.addon.removeSuffix("/manifest.json"), c.item.type, c.item.id) }.getOrNull() ?: return@execute
        channelPages[key] = System.currentTimeMillis() to meta
        ui.post { use(meta) }
    }
}

/** The add-ons' manifest addresses, in the order they were added. */
internal fun MainActivity.addonUrls(): List<String> = prefs.getString(K.ADDONS, "")!!.lines().filter { it.isNotBlank() }

private fun MainActivity.saveAddonUrls(urls: List<String>) = prefs.edit().putString(K.ADDONS, urls.distinct().joinToString("\n")).apply()

/** Whether the add-on at [url] is on: an add-on switched off stays, with its settings, but isn't shown nor asked. */
internal fun MainActivity.addonOn(url: String) = url !in prefs.getStringSet(K.ADDONS_OFF, emptySet())!!

/** Manifest addresses of add-ons marked as adult by the user (Contenuti V.M. - Adulti). */
private fun MainActivity.markedAdultUrls(): Set<String> =
    prefs.getString(K.ADULT_ADDONS, "")!!.lines().filter { it.isNotBlank() }.toSet() +
        prefs.getStringSet(K.EXT_ADULT, emptySet())!!.map { ExtensionCalls.url(it) }

/** Whether the add-on says it's adult itself, in its manifest (behaviorHints.adult): then it always is. */
internal fun MainActivity.saysAdult(url: String) = Stremio.saved(filesDir, url)?.addon?.adult == true

/** Manifest addresses of the adult add-ons: those marked by the user, and those that say so themselves. */
internal fun MainActivity.adultAddonUrls(): Set<String> = markedAdultUrls() + (addonUrls() + extensionAddonUrls()).filter { saysAdult(it) }

internal fun MainActivity.isAdultAddon(url: String?): Boolean =
    url != null && (url in markedAdultUrls() || saysAdult(url))

internal fun MainActivity.isAdultSource(source: String?): Boolean {
    if (source == null) return false
    val urls = adultAddonUrls()
    if (urls.contains(source)) return true
    val addons = urls.mapNotNull { Stremio.saved(filesDir, it)?.addon }
    return addons.any { it.base == source || it.name == source || it.id == source }
}

internal fun MainActivity.setAdultAddon(url: String, adult: Boolean) {
    val current = markedAdultUrls().toMutableSet()
    if (adult) current.add(url) else current.remove(url)
    if (current.isEmpty()) prefs.edit().remove(K.ADULT_ADDONS).apply()
    else prefs.edit().putString(K.ADULT_ADDONS, current.joinToString("\n")).apply()
}

/** Whether there's a TV app here: the one Android opens a channel link with (none on most boxes and sticks). */
internal fun MainActivity.hasTvApp(): Boolean = listOf(
    Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(1)),
    Intent(Intent.ACTION_VIEW).setType(TvContract.Channels.CONTENT_ITEM_TYPE),
).any { packageManager.queryIntentActivities(it, 0).any { r -> r.activityInfo.packageName != packageName } }

/** Where the add-ons' TV channels show: in the TV app, in one IPTV app, an app each; kept by [key] in the preferences. */
internal enum class TvPlace(val key: String) { TV("tv"), IPTV("iptv"), APPS("apps") }

/** Where the add-ons' films and series show: the Films and Series tiles, an app each, nowhere; kept by [key]. */
internal enum class VodPlace(val key: String) { TILES("tiles"), APPS("apps"), OFF("off") }

/** Where the add-ons' TV channels show, as chosen: in the TV app (if there's one, else the IPTV app), the IPTV app, an app each. */
internal fun MainActivity.addonsTv(): TvPlace {
    val mode = prefs.getString(K.ADDONS_TV, null).let { k -> TvPlace.entries.firstOrNull { it.key == k } } ?: TvPlace.TV
    return if (mode == TvPlace.TV && !hasTvApp()) TvPlace.IPTV else mode
}

/**
 * The tiles the add-ons add to the apps row: for TV channels one IPTV app, or one per add-on with channels, or none
 * (in the TV app); for films and series the Films and Series tiles, or one per add-on with catalogs of them (see
 * Titles). An add-on with both has one tile, with both. Runs on the io thread.
 */
internal fun MainActivity.addonApps(): List<MainActivity.App> {
    // Those apart (adult ones, extensions with an app of their own) under their own tiles; an extension's rows
    // given to the collectors (see extensionRoutes) with everyone's.
    val urls = shownAddonUrls().filter { !apart(it) || extensionRoutes(it, "tv") }
    fun icon(res: Int) = getDrawable(res)!!
    // Its logo once it's on disk (downloaded with its channels, see updateAddon), else the [res] mark.
    fun tile(addon: Stremio.Addon, res: Int): MainActivity.App {
        // An extension's: a file of its package, read as it is.
        val logo = addon.logo?.takeIf { it.startsWith("file://") }?.let { BitmapFactory.decodeFile(it.removePrefix("file://")) }
            ?: addon.logo?.takeIf { Images.has(it, Images.Kind.LOGO) }?.let { uri ->
                runCatching { BitmapFactory.decodeFile(Images.file(uri, Images.Kind.LOGO).path) }.getOrNull()
            }
        return MainActivity.App(STREMIO + addon.id, addon.name, Intent(), logo?.let { BitmapDrawable(resources, it) } ?: icon(res))
    }
    val tv = if (urls.isEmpty()) emptyList() else when (addonsTv()) {
        TvPlace.IPTV -> listOf(MainActivity.App(IPTV_PKG, getString(R.string.iptv_app), Intent(), icon(R.drawable.ic_live_tv), picture = icon(R.drawable.tile_tv)))
        TvPlace.APPS -> urls.mapNotNull { url ->
            // Not read yet (just restored from a backup): its tile comes once it is.
            val addon = Stremio.saved(filesDir, url)?.addon ?: return@mapNotNull null.also { if (Stremio.due(filesDir, url)) updateAddon(url, null) }
            if (addon.catalogs.isEmpty()) null else tile(addon, R.drawable.ic_live_tv) // no channels, no TV tile
        }
        TvPlace.TV -> emptyList()
    }
    // An adult add-on with an app of its own: one tile, its channels, films and series in it.
    // An extension's only once it has something to show (one not set up yet has no catalogs).
    val adultOwn = shownAddonUrls().filter { ownApp(it) }.mapNotNull { url ->
        // Not read yet (just installed, or restored from a backup): its tile comes once it is.
        (Stremio.saved(filesDir, url)?.addon ?: null.also { if (Stremio.due(filesDir, url)) updateAddon(url, null) })?.takeIf { adultOwnApp(url) || it.catalogs.isNotEmpty() || it.vod.isNotEmpty() }
            ?.let { tile(it, if (adultOwnApp(url)) R.drawable.ic_adult else R.drawable.ic_apps) }
    }
    return (tv + vodApps() + vodTileAddons().map { tile(it, R.drawable.ic_movie) } + adultTile() + adultOwn + collectorApps()).distinctBy { it.pkg }
}

/** The add-ons whose channels show under the tile [pkg]: all of them under the TV app or the IPTV app, one under its own. */
internal fun MainActivity.addonsUnder(pkg: String): List<String> {
    if (pkg == ADULT_PKG) return if (adultApp()) shownAddonUrls().filter { isAdultAddon(it) } else emptyList()
    ownAppUrl(pkg)?.let { return listOf(it) } // an add-on's own app: its channels, whatever the others do
    // The Radio collector: the extensions' radio stations given to it (see extensionRoutes).
    if (COLLECTOR_PKGS[pkg] == "radio") return shownAddonUrls().filter { extensionRoutes(it, "radio") && !inAdultApp(it) }
    val urls = shownAddonUrls().filter { !apart(it) || extensionRoutes(it, "tv") }
    val mode = addonsTv()
    return when {
        pkg == IPTV_PKG -> if (mode == TvPlace.IPTV) urls else emptyList()
        pkg.startsWith(STREMIO) -> if (mode == TvPlace.APPS) urls.filter { Stremio.saved(filesDir, it)?.addon?.let { a -> STREMIO + a.id } == pkg } else emptyList()
        mode == TvPlace.TV && isTvApp(pkg) -> urls
        else -> emptyList()
    }
}

/**
 * The rows of the add-ons under the tile [pkg] (see addonsUnder): per add-on, each TV catalog whole and then by
 * category, as the add-on lists them. Under an add-on's own tile a row is called by its category alone; under
 * the TV or IPTV app, where add-ons share the rows, by its catalog too ("<add-on> · Sport"). An add-on's
 * channels are read again when due, and the rows built again once they're in. Runs on the io thread.
 */
internal fun MainActivity.addonSections(pkg: String, hidden: Set<String>): List<Section> {
    val own = pkg.startsWith(STREMIO) && pkg != IPTV_PKG && pkg != ADULT_PKG // shared rows: named by catalog too
    val urls = addonsUnder(pkg)
    if (urls.isEmpty()) return emptyList()
    // What's on: the add-on's own, else the guide set in Zephyr TV settings (by the channel's name).
    val guide = epgUrl()?.let { url ->
        if (Epg.due(filesDir, url)) updateEpg(pkg)
        Epg.saved(filesDir, url)
    }
    urls.forEach { if (Stremio.due(filesDir, it)) updateAddon(it, pkg) }
    val listings = urls.mapNotNull { url -> Stremio.saved(filesDir, url)?.let { url to it } }
    val extGuides = listings.associate { (url, _) -> url to extensionGuides(url, pkg) }
    // The same add-ons, guide and hidden channels as last time, in the same few minutes: the same rows (the same
    // cards, so showSections keeps them on screen as they are).
    val from = listOf(pkg, addonsTv(), listings.joinToString { (u, l) -> "$u@${l.at}" }, System.identityHashCode(guide),
        extGuides.values.flatten().map { System.identityHashCode(it) }, hidden.hashCode(), System.currentTimeMillis() / GUIDE_MS).joinToString("|")
    built[pkg]?.takeIf { it.from == from }?.let { return it.sections }
    // Which of an add-on's rows show here, by their catalog's type: in the Radio collector its radio stations given to
    // it; in an extension's own app those not given to a collector; elsewhere TV channels (an extension's given to them).
    val radio = COLLECTOR_PKGS[pkg] == "radio"
    fun shows(url: String, type: String) = when {
        radio -> type == "radio"
        own && pkg == ownAppPkg(url) -> !extensionRoutes(url, type) && !(type == "radio" && radioPlace() == RadioPlace.OFF)
        else -> type != "radio" && (ExtensionCalls.idOf(url) == null || !apart(url) || extensionRoutes(url, type))
    }
    return listings
        .flatMap { (url, listing) ->
        val catalogs = listing.addon.catalogs.size
        listing.rows.filter { shows(url, it.catalog.type) }.mapNotNull { row ->
            val name = when {
                row.genre == null -> row.catalog.name
                own && catalogs == 1 -> row.genre
                else -> "${row.catalog.name} · ${row.genre}"
            }
            // Hidden as hideWatch keys it: the card has no app.
            val own = extGuides[url].orEmpty()
            val items = row.items.filter { hideKey(null, it.name) !in hidden }.map { item ->
                // What's on: the extension's own guides first (by its epgId, then its name), then Zephyr TV's.
                val now = if (item.now != null) null else own.firstNotNullOfOrNull { Epg.now(it, item.name, id = item.epgId) }
                    ?: guide?.let { Epg.now(it, item.name, id = item.epgId) }
                Prog(item.name, item.now ?: now?.first ?: "", now?.second ?: 0, item.logo, null, null, badge = "", addon = AddonChannel(url, item))
            }
            if (items.isEmpty()) null
            else Section("stremio:${listing.addon.id}:${row.catalog.id}:${row.genre.orEmpty()}", name, name, items, stamp = "$from/${items.size}")
        }
    }.also { built = built + (pkg to Built(from, it)) }
}

/**
 * Reads the add-on at [url] again in the background; once it's in, the rows under [pkg] are built again if it's
 * still the tile in focus, and the apps row if the add-on has a tile there. [done] hears the add-on (null: it failed).
 */
internal fun MainActivity.updateAddon(url: String, pkg: String?, done: ((Stremio.Listing?) -> Unit)? = null) = netIo.execute {
    val first = Stremio.saved(filesDir, url) == null
    val listing = Stremio.update(filesDir, url)
    // Its logo, for its tile and its icon on its settings row and Watch on rows: downloaded here, not while they show.
    val tiles = addonsTv() == TvPlace.APPS || ownApp(url) // a tile of its own: on the apps row once read
    val logo = listing?.addon?.logo?.takeIf { !Images.has(it, Images.Kind.LOGO) }
        ?.let { runCatching { Images.file(it, Images.Kind.LOGO) }.isSuccess } == true
    ui.post {
        done?.invoke(listing)
        if (tiles && listing != null && (logo || first)) refreshApps()
        if (listing != null) rowsAgain(pkg)
    }
}

/** Adds the add-on at [input] (typed or sent from a phone) once it answers, telling how it went. */
internal fun MainActivity.addAddon(input: String, adult: Boolean = false, done: () -> Unit = {}) {
    val url = Stremio.manifestUrl(input) ?: return Toast.makeText(this, getString(R.string.phone_epg_bad), Toast.LENGTH_LONG).show()
    Toast.makeText(this, getString(R.string.addon_checking), Toast.LENGTH_SHORT).show()
    updateAddon(url, null) { listing ->
        val a = listing?.addon
        when {
            listing == null || a == null -> Toast.makeText(this, getString(R.string.toast_addon_failed), Toast.LENGTH_LONG).show()
            // Neither channels nor films and series (catalogs or streams of them): nothing Zephyr TV can show.
            listing.rows.isEmpty() && !a.streamsVod && a.vod.isEmpty() -> {
                Stremio.forget(filesDir, url)
                Toast.makeText(this, getString(R.string.toast_addon_no_tv), Toast.LENGTH_LONG).show()
            }
            else -> {
                saveAddonUrls(addonUrls() + url)
                if (adult) setAdultAddon(url, true)
                addonsChanged()
                val channels = listing.rows.filter { it.genre == null }.sumOf { it.items.size }
                // Films and series too (its catalogs, or streams for them): said, not only the channels.
                val vod = a.vod.isNotEmpty() || a.streamsVod
                Toast.makeText(this, when {
                    channels > 0 && vod -> getString(R.string.toast_addon_added_both, a.name, channels)
                    channels > 0 -> getString(R.string.toast_addon_added, a.name, channels)
                    else -> getString(R.string.toast_addon_added_vod, a.name)
                }, Toast.LENGTH_LONG).show()
            }
        }
        done()
    }
}

internal fun MainActivity.removeAddon(url: String) {
    saveAddonUrls(addonUrls() - url)
    setAdultAddon(url, false)
    Stremio.forget(filesDir, url)
    addonsChanged()
    Toast.makeText(this, getString(R.string.toast_addon_removed), Toast.LENGTH_SHORT).show()
}

/** Add-ons, or where they show, changed: their tiles and the rows under the tile in focus come again. */
internal fun MainActivity.addonsChanged() {
    watchKey = null
    watchApp = null
    sectionCache = null
    refreshApps()
}

/**
 * The channels to zap through from [c]: those of the row it was picked in ([row], when there's one), else the
 * whole catalog it's in, else [c] alone.
 */
internal fun MainActivity.zapList(c: AddonChannel, row: List<AddonChannel>?): List<AddonChannel> {
    val rows = Stremio.saved(filesDir, c.addon)?.rows.orEmpty().filter { it.genre == null }
    val selected=row?.takeIf { list -> list.any { it.item.id==c.item.id } }
        ?: rows.firstOrNull { r -> r.items.any { it.id==c.item.id } }?.items?.map { AddonChannel(c.addon,it) } ?: listOf(c)
    return selected.flatMap { entry -> entry.item.channels.ifEmpty { listOf(entry.item) }.map { AddonChannel(entry.addon,it) } }
        .distinctBy { it.addon+"|"+it.item.id }

}

/**
 * OK on an add-on's channel: Zephyr TV's player plays it (see PlayerActivity), with the channels around it ([row]: the
 * cards of the row it's in) for CH+ and CH-. Each channel's logo: the add-on's, then Zephyr TV's own by name.
 */
internal fun MainActivity.playAddonChannel(p: Prog, row: List<AddonChannel>? = null) {
    val c = p.addon ?: return
    val list = zapList(c, row)
    val base = Stremio.saved(filesDir, c.addon)?.addon?.base ?: c.addon.removeSuffix("/manifest.json")
    val logos = channelLogoTable()
    fun strings(f: (AddonChannel) -> String?) = ArrayList(list.map { f(it).orEmpty() })
    try {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.ADDON, c.addon)
            .putExtra(PlayerActivity.BASE, base)
            .putExtra(PlayerActivity.ADULT, isAdultAddon(c.addon))
            .putExtra(PlayerActivity.INDEX, list.indexOfFirst { it.item.id == (c.item.channels.firstOrNull()?.id ?: c.item.id) }.coerceAtLeast(0))
            .putStringArrayListExtra(PlayerActivity.TYPES, strings { it.item.type })
            .putStringArrayListExtra(PlayerActivity.IDS, strings { it.item.id })
            .putStringArrayListExtra(PlayerActivity.NAMES, strings { it.item.name })
            .putStringArrayListExtra(PlayerActivity.NOWS, strings { it.item.now })
            .putStringArrayListExtra(PlayerActivity.LOGOS, strings { it.item.logo })
            .putStringArrayListExtra(PlayerActivity.FALLBACK_LOGOS, strings { ChannelLogos.match(logos, it.item.name) }))
    } catch (e: Exception) {
        warn("player didn't open ($e)")
    }
}

/**
 * [c]'s streams looked for ahead and kept for the player (see StreamCache), as it does: its add-on's, then the plugins'
 * for TV channels. A channel made of several (one name, HD and SD) has them all ready: the player goes straight to them.
 */
internal fun MainActivity.warmChannel(c: AddonChannel) {
    val base = Stremio.saved(filesDir, c.addon)?.addon?.base ?: c.addon.removeSuffix("/manifest.json")
    val target=c.item.channels.firstOrNull() ?: c.item
    val type = target.type
    val id = target.id
    val adult = adultUnlocked()
    StreamCache.later {
        val names = (listOf(base to "") + pluginSources(prefs, filesDir, type, id, adult)).toMap()
        StreamCache.warm(cacheDir, names.keys.toList(), type, id, { b ->
            if (b == base) Stremio.streams(base, type, id) else sourceStreams(prefs, filesDir, b, names[b].orEmpty(), type, id)
        })
    }
}

/** How long a channel's card stays picked before its streams are looked for (see warmChannel). */
internal const val WARM_MS = 600L

/** The channel last played (written by PlayerActivity, in its own process: a file, not the preferences). */
internal fun MainActivity.lastAddonChannel(): Pair<Long, AddonChannel>? = runCatching {
    val o = org.json.JSONObject(java.io.File(filesDir, PlayerActivity.LAST_FILE).readText())
    val addon = o.getString("addon").takeIf {
        hasHistorySource(it, addonUrls(), extensionAddonUrls()) && addonOn(it) && !isAdultAddon(it)
    } ?: return null
    o.getLong("at") to AddonChannel(addon, Stremio.Item(o.getString("type"), o.getString("id"), o.getString("name"), o.optString("logo").ifEmpty { null }, null))
}.getOrNull()

/** The channel last played as a Continue Playing card: the add-on's name under it. Off the UI thread. */
internal fun MainActivity.lastChannelCard(hidden: Set<String>): Pair<Long, Prog>? {
    val (at, c) = lastAddonChannel() ?: return null
    if (isAdultAddon(c.addon)) return null
    if (hideKey(LAST_PKG, c.item.name) in hidden) return null
    val addon = Stremio.saved(filesDir, c.addon)?.addon
    // Marked with the tile it plays from, as the other cards are with their app.
    return at to Prog(c.item.name, addon?.name.orEmpty(), 0, c.item.logo, null, LAST_PKG, badge = "", addon = c, icon = addonTile(c)?.pkg)
}

/** The tile [c]'s channels show under, as addonsTv puts them: the add-on's own app, the IPTV app, or the TV app. */
internal fun MainActivity.addonTile(c: AddonChannel): MainActivity.App? {
    if (inAdultApp(c.addon)) return apps.firstOrNull { it.pkg == ADULT_PKG }
    if (ownApp(c.addon) && !extensionRoutes(c.addon, "tv")) return Stremio.saved(filesDir, c.addon)?.addon?.let { a -> apps.firstOrNull { it.pkg == STREMIO + a.id } }
    val pkg = when (addonsTv()) {
        TvPlace.APPS -> Stremio.saved(filesDir, c.addon)?.addon?.let { STREMIO + it.id }
        TvPlace.IPTV -> IPTV_PKG
        TvPlace.TV -> return apps.firstOrNull { !it.pkg.startsWith(STREMIO) && isTvApp(it.pkg) }
    }
    return apps.firstOrNull { it.pkg == pkg }
}

/** OK on an add-on's tile (or the IPTV app's): the channel last played among its add-ons, if any. */
internal fun MainActivity.playLastChannel(pkg: String): Boolean {
    val (_, c) = lastAddonChannel() ?: return false
    if (c.addon !in addonsUnder(pkg)) return false
    playAddonChannel(Prog(c.item.name, "", 0, c.item.logo, null, null, badge = "", addon = c))
    return true
}

/** The add-ons' channels whose name has [words] (normalized, see ChannelLogos) in it, for the search. Off the UI thread. */
internal fun MainActivity.findAddonChannels(words: String): List<ChannelDetail> {
    val logos = channelLogoTable()
    val guide = epgUrl()?.let { Epg.saved(filesDir, it) }
    return shownAddonUrls().flatMap { url ->
        Stremio.saved(filesDir, url)?.rows.orEmpty().filter { it.genre == null }.flatMap { r -> r.items.map { url to it } }
    }.filter { (_, item) -> ChannelLogos.normalize(item.name).contains(words) }.distinctBy { it.second.name }.map { (url, item) ->
        addonDetail(AddonChannel(url, item), logos, guide, null)
    }
}

/** An add-on's channel as its page shows it: the add-on's logo and guide first, Zephyr TV's own after ([meta]: its page from the add-on). */
internal fun addonDetail(c: AddonChannel, logos: Map<String, String>, guide: Epg.Guide?, meta: Stremio.Meta?): ChannelDetail {
    val epg = guide?.let { Epg.nowAndNext(it, c.item.name) }
    val own = (meta?.now ?: c.item.now)?.let { Epg.Show(0, 0, it, meta?.description.orEmpty()) }
    return ChannelDetail(c.item.name, "", c.item.logo ?: ChannelLogos.match(logos, c.item.name), "", null,
        own ?: epg?.first, if (own == null) epg?.second else null, addon = c)
}

/** Hold OK on an add-on's channel, Details: its page, with what the add-on tells about it. */
internal fun MainActivity.showAddonChannelDetails(c: AddonChannel) = netIo.execute {
    val base = Stremio.saved(filesDir, c.addon)?.addon?.base ?: return@execute
    val meta = runCatching { Stremio.meta(base, c.item.type, c.item.id) }.getOrNull()
    val d = addonDetail(c, channelLogoTable(), epgUrl()?.let { Epg.saved(filesDir, it) }, meta)
    ui.post { showChannel(d) }
}

/** Whether Zephyr TV may read the TV listings (the TV app's channels and other apps' rows): add-on tiles don't need to. */
internal fun MainActivity.readsListings() = checkSelfPermission(MainActivity.TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

// ---------- settings: the Stremio add-ons section ----------

/** Where the add-ons' channels show, then Add an add-on, then each add-on (its page: update, remove). */
/**
 * Where the add-ons' and plugins' content shows (on Virtual apps and integrations, see addonsAndPlugins): TV channels, films
 * and series, the adult ones. Each changed, that page again with [back] (focus on the row).
 */
internal fun MainActivity.placementRows(back: (Int) -> Unit): List<Triple<String, String, () -> Unit>> {
    val mode = addonsTv()
    val modes = TvPlace.entries.filter { it != TvPlace.TV || hasTvApp() }
    return listOf(
        page(getString(R.string.setting_addons_tv), addonsTvName(mode)) {
            val next = modes[(modes.indexOf(mode) + 1) % modes.size]
            prefs.edit().putString(K.ADDONS_TV, next.key).apply()
            addonsChanged()
            // An app each: their logos, for the tiles, come with their channels.
            if (next == TvPlace.APPS) addonUrls().forEach { updateAddon(it, null) }
            if (next == TvPlace.IPTV) toFavourites(IPTV_PKG)
            back(0)
        },
        // Films, then series, each: its virtual app (Films, Series), an app per add-on, or none (their catalog: under Content).
        vodPlaceRow("movie", R.string.vod_movies, K.ADDONS_MOVIES, MOVIES_PKG) { back(1) },
        vodPlaceRow("series", R.string.vod_series, K.ADDONS_SERIES, SERIES_PKG) { back(2) },
        // The extensions' radio stations: the Radio virtual app, each extension's own app, or none.
        page(getString(R.string.ext_kind_radio), radioPlaceName(radioPlace())) {
            val modes = RadioPlace.entries
            val next = modes[(modes.indexOf(radioPlace()) + 1) % modes.size]
            prefs.edit().putString(K.RADIO_PLACE, next.key).apply()
            if (next == RadioPlace.VIRTUAL) toFavourites(*COLLECTOR_PKGS.filterValues { it == "radio" }.keys.toTypedArray())
            addonsChanged()
            back(3)
        },
        // The adult add-ons: in Adult content, an app each, or with the others (apart, hidden while the 18+ lock holds).
        page(getString(R.string.setting_adult_app), adultPlaceName(adultPlace())) {
            val modes = AdultPlace.entries
            val next = modes[(modes.indexOf(adultPlace()) + 1) % modes.size]
            prefs.edit().putString(K.ADULT_PLACE, next.key).apply()
            if (next == AdultPlace.VIRTUAL) toFavourites(ADULT_PKG)
            addonsChanged()
            back(4)
        },
    )
}

/** The Stremio add-ons' section: Add an add-on, then each add-on (on or off, its page). */
internal fun MainActivity.addonRows(): List<Triple<String, String, () -> Unit>> =
    listOf(page(getString(R.string.setting_addon_add), SUBMENU) { addonFromPhone() }) + addonUrls().mapIndexed { i, url ->
        page(Stremio.saved(filesDir, url)?.addon?.name ?: url, if (addonOn(url)) SUBMENU else getString(R.string.state_off)) { addonPage(url) }
    }

/**
 * The catalogs added as alternatives to Cinemeta, as manifest addresses, in the order added. One of them may be the
 * catalog source (see cinemetaUrl); an address set as Cinemeta's before there was a list counts as one too.
 */
internal fun MainActivity.catalogUrls(): List<String> = (prefs.getString(K.CATALOGS, "")!!.lines().filter { it.isNotBlank() } +
    listOfNotNull(prefs.getString(K.CINEMETA_URL, null)?.let { Stremio.manifestUrl(it) }))
    .filter { it != Stremio.CINEMETA && !it.startsWith(Stremio.NUVIO_BASE) }.distinct() // Nuvio has a row of its own

private fun MainActivity.saveCatalogUrls(urls: List<String>) = prefs.edit().putString(K.CATALOGS, urls.distinct().joinToString("\n")).apply()

/** A catalog's name, as its add-on gives it; else its address's host. */
internal fun MainActivity.catalogName(url: String) =
    if (url == Stremio.CINEMETA) "Cinemeta" else if (url.startsWith(Stremio.NUVIO_BASE)) getString(R.string.catalog_nuvio) else Stremio.saved(filesDir, url)?.addon?.name ?: android.net.Uri.parse(url).host.orEmpty()

/**
 * Content catalog: the catalog sources in order (the main one first: see catalogSources), each with its page (up,
 * down, remove); Add a catalog; then TMDB's rows (see tmdbContentRows). [focus]: the row to focus.
 */
internal fun MainActivity.configureCinemeta(focus: Int = 0) {
    val sources = catalogSources()
    val rows = sources.mapIndexed { i, url ->
        page(catalogName(url), if (i == 0) getString(R.string.catalog_primary) else "${i + 1}") { sourcePage(url) }
    } + page(getString(R.string.catalog_add), SUBMENU) { addSourcePage() } + tmdbContentRows()
    val intro = label(getString(R.string.cinemeta_intro), 13f).apply {
        alpha = 0.7f
        maxLines = 8
        setLineSpacing(0f, 1.15f)
        setPadding(dp(14), 0, dp(14), dp(12))
    }
    showPage(getString(R.string.setting_cinemeta), rows, focus, top = intro) { renderSettings(CINEMETA_ROW) }
}

/** Row [i] of the TMDB rows on the content catalog's page (see tmdbContentRows): after the sources and Add. */
internal fun MainActivity.tmdbRow(i: Int) = catalogSources().size + 1 + i

/** What Content catalog says under Content: the main source, and how many more. */
internal fun MainActivity.sourcesValue() = catalogSources().let { s -> catalogName(s.first()) + if (s.size > 1) " +${s.size - 1}" else "" }

/**
 * The catalog sources from now on, in this order: read at once when new. A new main one sets Complete with TMDB: off
 * for Nuvio (in the TV's language already, TMDB's, without a key), on for the others.
 */
private fun MainActivity.saveSources(urls: List<String>) {
    val before = catalogSources()
    val list = urls.distinct().ifEmpty { listOf(Stremio.CINEMETA) }
    prefs.edit().putString(K.CATALOG_SOURCES, list.joinToString("\n")).remove(K.CINEMETA_URL).apply()
    if (list.first() != before.first()) prefs.edit().putBoolean(K.TMDB_ENRICH, !list.first().startsWith(Stremio.NUVIO_BASE)).apply()
    list.filter { Stremio.saved(filesDir, it) == null }.forEach { updateAddon(it, null) { addonsChanged() } }
    addonsChanged()
}

/** The first start's choice (see catalogPopup): [url] the one catalog source (null: Cinemeta). */
internal fun MainActivity.setCatalog(url: String?) = saveSources(listOf(url ?: Stremio.CINEMETA))

/** A catalog source's page: up or down the list (the first is the main one), or out of it (one always stays). */
private fun MainActivity.sourcePage(url: String) {
    val sources = catalogSources()
    val at = sources.indexOf(url)
    fun move(by: Int) {
        val list = sources.toMutableList()
        list.add(at + by, list.removeAt(at))
        saveSources(list)
        configureCinemeta(at + by)
    }
    val addon = Stremio.saved(filesDir, url)?.addon
    val shelves = addon?.vod.orEmpty().filter { it.listed }
    showPage(catalogName(url), listOfNotNull(
        // Its rows, each shown or not, under Films, Series and its own tile alike.
        if (addon == null || shelves.isEmpty()) null else page(getString(R.string.catalog_rows), SUBMENU) {
            toggles(getString(R.string.catalog_rows), shelves.map { "${kindName(it.type)} · ${it.name}" to shelfKey(addon, it) },
                K.SHELVES_OFF, getString(R.string.state_shown), getString(R.string.state_hidden)) {
                addonsChanged()
                sourcePage(url)
            }
        },
        if (at > 0) page(getString(R.string.addon_move_up)) { move(-1) } else null,
        if (at < sources.size - 1) page(getString(R.string.addon_move_down)) { move(+1) } else null,
        if (sources.size > 1) page(getString(R.string.addon_remove)) { saveSources(sources - url); configureCinemeta() } else null,
    )) { configureCinemeta(at) }
}

/** Add a catalog: Cinemeta, Nuvio and the catalogs added before, those not in use; or a new address. Added last. */
private fun MainActivity.addSourcePage() {
    val sources = catalogSources()
    val known = (listOf(Stremio.CINEMETA, nuvioUrl()) + catalogUrls()).filter { it !in sources }
    showPage(getString(R.string.catalog_add), known.map { url ->
        page(catalogName(url)) { saveSources(sources + url); configureCinemeta(sources.size) }
    } + page(getString(R.string.catalog_new_address), SUBMENU) { addCatalog() }) { configureCinemeta(sources.size) }
}

/** A catalog's address typed with the remote: added once it answers with catalogs of films or series. */
private fun MainActivity.addCatalog() {
    textPage(getString(R.string.catalog_new_address), "", getString(R.string.addon_hint), getString(R.string.catalog_add_intro), back = { addSourcePage() }) { typed ->
        listOf(page(getString(R.string.dialog_save)) {
            val url = Stremio.manifestUrl(typed()) ?: return@page Toast.makeText(this, getString(R.string.phone_epg_bad), Toast.LENGTH_LONG).show()
            if (url == Stremio.CINEMETA) return@page configureCinemeta()
            Toast.makeText(this, getString(R.string.addon_checking), Toast.LENGTH_SHORT).show()
            updateAddon(url, null) { listing ->
                val a = listing?.addon
                when {
                    a == null -> Toast.makeText(this, getString(R.string.toast_addon_failed), Toast.LENGTH_LONG).show()
                    a.vod.isEmpty() -> {
                        if (url !in addonUrls()) Stremio.forget(filesDir, url)
                        Toast.makeText(this, getString(R.string.toast_catalog_no_vod), Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        saveCatalogUrls(catalogUrls() + url)
                        saveSources(catalogSources() + url)
                        configureCinemeta(catalogSources().indexOf(url).coerceAtLeast(0))
                    }
                }
            }
        })
    }
}

// The same words for each kind of content: the system's TV app, a virtual app, an app per add-on, with the others, off.
/** The row of where films ([type] "movie") or series show, saved in [key]; [tile] its virtual app. */
private fun MainActivity.vodPlaceRow(type: String, label: Int, key: String, tile: String, back: () -> Unit) =
    page(getString(label), addonsVodName(addonsVod(type))) {
        val modes = VodPlace.entries
        val next = modes[(modes.indexOf(addonsVod(type)) + 1) % modes.size]
        prefs.edit().putString(key, next.key).apply()
        if (next == VodPlace.TILES) toFavourites(tile)
        addonsChanged()
        back()
    }

internal fun MainActivity.radioPlaceName(mode: RadioPlace) = getString(when (mode) {
    RadioPlace.VIRTUAL -> R.string.place_virtual
    RadioPlace.APPS -> R.string.place_per_extension
    RadioPlace.OFF -> R.string.state_off
})

internal fun MainActivity.addonsVodName(mode: VodPlace) = getString(when (mode) {
    VodPlace.TILES -> R.string.place_virtual
    VodPlace.APPS -> R.string.place_per_addon
    VodPlace.OFF -> R.string.state_off
})

internal fun MainActivity.addonsTvName(mode: TvPlace) = getString(when (mode) {
    TvPlace.TV -> R.string.place_system
    TvPlace.IPTV -> R.string.place_virtual
    TvPlace.APPS -> R.string.place_per_addon
})

internal fun MainActivity.adultPlaceName(mode: AdultPlace) = getString(when (mode) {
    AdultPlace.VIRTUAL -> R.string.place_virtual
    AdultPlace.APPS -> R.string.place_per_addon
    AdultPlace.MIXED -> R.string.place_mixed
})

/** A virtual app just chosen goes among the favourites (the apps row), if it had been taken out; it can be again. */
internal fun MainActivity.toFavourites(vararg pkgs: String) {
    prefs.edit().putStringSet(K.ROW_HIDDEN, rowHiddenSet() - pkgs.toSet()).apply()
}

/** An add-on's manifest address typed with the remote (see addonFromPhone). Saving adds it once it answers. */
internal fun MainActivity.configureAddon(initialUrl: String = "", initialAdult: Boolean = false) {
    var adult = initialAdult
    textPage(getString(R.string.setting_addon_add), initialUrl, getString(R.string.addon_hint), getString(R.string.addon_intro), back = { addonFromPhone() }) { typed ->
        listOf(
            page(getString(R.string.addon_adult), toggle(adult)) {
                adult = !adult
                configureAddon(typed(), adult)
            },
            page(getString(R.string.dialog_save)) {
                addAddon(typed(), adult) { refreshSettings(ADDON_ADD_ROW) }
                renderSettings(ADDON_ADD_ROW)
            }
        )
    }
}

/**
 * Add an add-on: a QR code first, as the other addresses and keys, since a long address is easier pasted on a
 * phone; typing it with the remote is the row under it. Added like the Save row does.
 */
internal fun MainActivity.addonFromPhone() {
    val status = label(getString(R.string.addon_phone_waiting), 14f)
    val page = PhonePages.addon(phoneTexts(R.string.phone_addon_title, R.string.phone_addon_intro), getString(R.string.addon_hint), getString(R.string.addon_adult))
    showPhoneLink(getString(R.string.setting_addon_add), PhoneTask(page, 4096) { file ->
        val lines = file.readText().lines()
        val url = Stremio.manifestUrl(lines.firstOrNull().orEmpty()) ?: return@PhoneTask 400 to getString(R.string.phone_epg_bad)
        val adult = lines.getOrNull(1)?.trim() == "adult"
        ui.post {
            status.text = getString(R.string.addon_checking)
            addAddon(url, adult)
        }
        200 to getString(R.string.phone_addon_saved)
    }, status, listOf(page(getString(R.string.addon_type)) { configureAddon() })) { renderSettings(ADDON_ADD_ROW) }
}

/**
 * Moves the add-on at [url] [step] places in the list: where several show together (the TV app, the IPTV app),
 * their rows come in this order.
 */
internal fun MainActivity.moveAddon(url: String, step: Int) {
    val urls = addonUrls().toMutableList()
    val from = urls.indexOf(url)
    val to = from + step
    if (from < 0 || to !in urls.indices) return
    urls.removeAt(from)
    urls.add(to, url)
    saveAddonUrls(urls)
    addonsChanged()
}

/**
 * One add-on's page, from its row in the section: read it again now, move it up or down the list (the order its
 * rows come in), or remove it. [focus] is the row to focus by its name (after a move, the same move again).
 */
internal fun MainActivity.addonPage(url: String, focus: Int? = null) {
    val name = Stremio.saved(filesDir, url)?.addon?.name ?: url
    val urls = addonUrls()
    val at = urls.indexOf(url)
    val row = ADDON_ROWS + at
    val rows = listOfNotNull(
        // On or off: off, it stays here with its settings, but its channels, films and streams don't show.
        page(getString(R.string.addon_on), toggle(addonOn(url))) {
            val off = prefs.getStringSet(K.ADDONS_OFF, emptySet())!!.toMutableSet()
            if (!off.remove(url)) off += url
            prefs.edit().putStringSet(K.ADDONS_OFF, off).apply()
            addonsChanged()
            addonPage(url, R.string.addon_on)
        },
        page(getString(R.string.addon_update)) {
            Toast.makeText(this, getString(R.string.addon_checking), Toast.LENGTH_SHORT).show()
            updateAddon(url, null) { listing ->
                val a = listing?.addon
                val channels = listing?.rows?.filter { it.genre == null }?.sumOf { it.items.size } ?: 0
                val vod = a != null && (a.vod.isNotEmpty() || a.streamsVod)
                val text = when {
                    listing == null || a == null -> getString(R.string.toast_addon_failed)
                    channels > 0 && vod -> getString(R.string.toast_addon_updated_both, channels)
                    channels > 0 -> getString(R.string.toast_addon_updated, channels)
                    vod -> getString(R.string.toast_addon_updated_vod)
                    else -> getString(R.string.toast_addon_updated_simple)
                }
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                watchKey = null
            }
        },
        page(getString(R.string.addon_adult), toggle(isAdultAddon(url))) {
            if (saysAdult(url)) return@page Toast.makeText(this, getString(R.string.addon_says_adult), Toast.LENGTH_LONG).show()
            setAdultAddon(url, !isAdultAddon(url))
            addonsChanged()
            addonPage(url, R.string.addon_adult)
        },
        if (at > 0) page(getString(R.string.addon_move_up)) { moveAddon(url, -1); addonPage(url, R.string.addon_move_up) } else null,
        if (at < urls.size - 1) page(getString(R.string.addon_move_down)) { moveAddon(url, +1); addonPage(url, R.string.addon_move_down) } else null,
        page(getString(R.string.addon_remove)) {
            removeAddon(url)
            renderSettings(ADDON_ADD_ROW)
        },
    )
    // After a move, the same move again (or the other one, at the top or bottom of the list).
    val shown = rows.map { it.first }
    val index = focus?.let { f -> shown.indexOf(getString(f)).takeIf { it >= 0 }
        ?: shown.indexOfFirst { it == getString(R.string.addon_move_up) || it == getString(R.string.addon_move_down) } } ?: 0
    showPage(name, rows, index.coerceAtLeast(0), top = label(url, 12f).apply { alpha = 0.6f; setPadding(dp(4), dp(8), dp(4), dp(8)) }, back = { renderSettings(row) })
}
