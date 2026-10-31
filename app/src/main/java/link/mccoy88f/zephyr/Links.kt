package link.mccoy88f.zephyr

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.widget.Toast

// Links into Zephyr TV, and out of it to be shared (WhatsApp, Telegram…). One format, ztv:, for all it can take:
//   ztv://title/<type>/<id>?n=<name>[&s=<season>&e=<episode>]   a film's or series' page (an episode's, said in the text)
//   ztv://ext?u=<address of manifest-zephyrtv.json>              an extension, shown before it's installed
//   ztv://addon?u=<address of manifest.json>                     a Stremio add-on, added once confirmed
//   ztv://nuvio?u=<address of a Nuvio repository>                a Nuvio plugin repository, added once confirmed
// Shared as an https address (chats only make those clickable): SHARE_PAGE#<the same after ztv://>, a page that opens
// Zephyr TV, or says where to get it; Zephyr TV takes that address too. Also: stremio:// links (an add-on's
// installation, a title's page), Nuvio's nuvio:// links (a title's page, an add-on: see NuvioTV's DeepLinkParser), an
// add-on's manifest.json address, and text shared from other apps with any of them.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

/** The page shared links go to (docs/open/index.html, on the repository's GitHub Pages). */
internal const val SHARE_PAGE = "https://mccoy88f.github.io/Zephyr_TV/open/"

private const val ACTION_SEARCH = "link.mccoy88f.zephyr.action.SEARCH"
private const val ACTION_PLAY_QUEUE = "link.mccoy88f.zephyr.action.PLAY_QUEUE"

/** What [intent] asks for, when it's a link, shared text or a shortcut: done, and true; false for anything else. */
internal fun MainActivity.handleLink(intent: Intent?): Boolean {
    intent ?: return false
    when (intent.action) {
        ACTION_SEARCH -> return true.also { ui.post { openSearch() } }
        ACTION_PLAY_QUEUE -> return true.also { ui.post { appRow.getChildAt(0)?.requestFocus() } }
        Intent.ACTION_SEND -> {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val link = Regex("""(ztv|stremio|nuvio|https?)://\S+""").find(text)?.value?.trimEnd('.', ',', ')', ']')
                ?: return true.also { Toast.makeText(this, getString(R.string.link_unknown), Toast.LENGTH_LONG).show() }
            return openLink(Uri.parse(link))
        }
        Intent.ACTION_VIEW -> return intent.data?.let { openLink(it) } ?: false
    }
    return false
}

/** [uri], from any of the forms above: what it is, opened. False when it's none of them. */
internal fun MainActivity.openLink(uri: Uri): Boolean {
    val s = uri.toString()
    val ztv = when {
        uri.scheme == "ztv" -> uri
        s.startsWith(SHARE_PAGE) -> uri.fragment?.takeIf { it.isNotBlank() }?.let { Uri.parse("ztv://$it") }
        else -> null
    }
    if (ztv != null) return openZtv(ztv)
    if (uri.scheme == "stremio") {
        // stremio://host/path/manifest.json: the add-on at https://host/path/manifest.json.
        if (s.contains("manifest.json")) return true.also { confirmAddon("https://" + s.removePrefix("stremio://")) }
        // stremio:///detail/<type>/<id>[/<video>]: its page.
        val parts = uri.pathSegments
        val at = parts.indexOf("detail")
        if (at >= 0 && parts.size > at + 2) return true.also { openShared(parts[at + 1], parts[at + 2], "", null, null) }
        return false
    }
    if (uri.scheme == "nuvio") return openNuvio(uri)
    if (uri.scheme == "http" || uri.scheme == "https") {
        val path = uri.path.orEmpty()
        return when {
            path.endsWith("manifest-zephyrtv.json") || path.endsWith("repository-zephyrtv.json") -> true.also { addExtension(s) }
            path.endsWith("manifest.json") -> true.also { confirmAddon(s) }
            else -> false
        }
    }
    return false
}

private fun MainActivity.openZtv(u: Uri): Boolean {
    val address = u.getQueryParameter("u")
    when (u.host) {
        "title" -> {
            val p = u.pathSegments
            if (p.size < 2) return false
            openShared(p[0], p[1], u.getQueryParameter("n").orEmpty(), u.getQueryParameter("s")?.toIntOrNull(), u.getQueryParameter("e")?.toIntOrNull())
        }
        "ext" -> addExtension(address ?: return false)
        "addon" -> confirmAddon(address ?: return false)
        "nuvio" -> confirmRepo(address ?: return false)
        else -> return false
    }
    return true
}

/**
 * Nuvio's links, as its apps read them: an add-on (nuvio://host/…/manifest.json, as stremio://), or a title's page
 * (nuvio://meta?type=&id=, detail|open|watch/<type>/<id>, movie|series/<id>, imdb|tmdb/<type>/<id>). Its parties
 * (nuvio://party/<code>) are Nuvio's own: said so.
 */
private fun MainActivity.openNuvio(uri: Uri): Boolean {
    val host = uri.host.orEmpty().lowercase()
    val path = uri.pathSegments
    fun type(t: String?) = when (t?.lowercase()) { "movie", "movies", "film" -> "movie"; "series", "show", "shows", "tv" -> "series"; else -> null }
    fun title(t: String?, id: String?): Boolean {
        val ty = type(t) ?: return false
        if (id.isNullOrBlank()) return false
        openShared(ty, id, "", null, null)
        return true
    }
    return when {
        host.contains('.') || host == "localhost" || host.any { it.isDigit() } ->
            true.also { confirmAddon("https://" + uri.toString().substringAfter("://")) }
        host == "meta" -> title(uri.getQueryParameter("type") ?: uri.getQueryParameter("mediaType"),
            uri.getQueryParameter("id") ?: uri.getQueryParameter("imdb") ?: uri.getQueryParameter("tmdb")?.let { "tmdb:" + it.removePrefix("tmdb:") })
        host in setOf("detail", "details", "open", "watch") -> title(path.getOrNull(0), path.getOrNull(1))
        host in setOf("movie", "movies", "series", "show", "shows", "tv") -> title(host, path.getOrNull(0))
        host == "imdb" -> if (type(path.getOrNull(0)) != null) title(path.getOrNull(0), path.getOrNull(1)) else title(uri.getQueryParameter("type"), path.getOrNull(0))
        host == "tmdb" -> {
            val (t, id) = if (type(path.getOrNull(0)) != null) path.getOrNull(0) to path.getOrNull(1) else uri.getQueryParameter("type") to path.getOrNull(0)
            title(t, id?.let { "tmdb:" + it.removePrefix("tmdb:") })
        }
        host == "party" -> true.also { Toast.makeText(this, getString(R.string.link_party), Toast.LENGTH_LONG).show() }
        else -> false
    }
}

/** A shared title's page: a film's, or a series' (its episode said, as the link has it). */
private fun MainActivity.openShared(type: String, id: String, name: String, season: Int?, episode: Int?) {
    ui.post {
        if (season != null && episode != null) Toast.makeText(this, getString(R.string.link_episode, season, episode), Toast.LENGTH_LONG).show()
        openTitle(TitleRef(if (type == "tv") "series" else type, id, name.ifBlank { id }, null, null))
    }
}

private fun MainActivity.addExtension(address: String) = ui.post {
    openLauncherSettings()
    extensionFromAddress(address) // it says what the extension is, and who made it, before installing it
}

/** A link's add-on, added only once the user says so: a link can come from anyone. */
private fun MainActivity.confirmAddon(address: String) = ui.post {
    val url = Stremio.manifestUrl(address) ?: return@post Toast.makeText(this, getString(R.string.phone_epg_bad), Toast.LENGTH_LONG).show()
    confirmLink(getString(R.string.link_addon_title), url) { addAddon(url) }
}

private fun MainActivity.confirmRepo(address: String) = ui.post {
    confirmLink(getString(R.string.link_nuvio_title), address) { addRepo(address, adult = false) }
}

private fun MainActivity.confirmLink(title: String, url: String, add: () -> Unit) {
    if (settingsScrim.visibility != android.view.View.VISIBLE) showPanel()
    val about = label(getString(R.string.link_confirm, url), 13f).apply {
        alpha = 0.8f
        maxLines = 8
        setLineSpacing(0f, 1.15f)
        setPadding(dp(14), 0, dp(14), dp(12))
    }
    showPage(title, listOf(
        page(getString(R.string.link_add)) { add(); closeLauncherSettings() },
        page(getString(R.string.dialog_cancel)) { closeLauncherSettings() },
    ), top = about) { closeLauncherSettings() }
}

/**
 * A film's or series' page shared (an episode's too: [season], [episode]): the title and its link, through the
 * phone's own share sheet (WhatsApp, Telegram…).
 */
internal fun MainActivity.shareTitle(type: String, id: String, name: String, season: Int? = null, episode: Int? = null) {
    val query = "?n=" + Uri.encode(name) + if (season != null && episode != null) "&s=$season&e=$episode" else ""
    val link = SHARE_PAGE + "#title/$type/" + Uri.encode(id) + query
    val what = if (season != null && episode != null) getString(R.string.player_episode, name, season, episode) else name
    val text = getString(R.string.share_text, what, link)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, what)
    runCatching { startActivity(Intent.createChooser(send, getString(R.string.share))) }
}

/** On a phone or tablet: Search and the Play queue held on Zephyr TV's icon. */
internal fun MainActivity.shortcuts() {
    if (android.os.Build.VERSION.SDK_INT < 25 || !handheld()) return
    runCatching {
        fun shortcut(id: String, label: Int, icon: Int, action: String) = ShortcutInfo.Builder(this, id)
            .setShortLabel(getString(label))
            .setIcon(Icon.createWithResource(this, icon))
            .setIntent(Intent(this, MainActivity::class.java).setAction(action))
            .build()
        getSystemService(ShortcutManager::class.java)?.dynamicShortcuts = listOf(
            shortcut("search", R.string.search_title, R.drawable.ic_search, ACTION_SEARCH),
            shortcut("play-queue", R.string.play_queue, R.drawable.ic_continue_watching, ACTION_PLAY_QUEUE),
        )
    }.onFailure { warn("shortcuts: $it") }
}
