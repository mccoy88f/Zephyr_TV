package link.mccoy88f.zephyr

import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import link.mccoy88f.zephyr.SettingsRows.TV_APP_ROW

// The TV app: the tile that shows the TV's channels (a row per tuner) and, when the add-ons' channels go "in the
// TV app", theirs too; and the app that opens a TV channel. Zephyr TV finds it by itself; Settings → TV and EPG →
// TV app lets the user pick another one, any app. An app that can't open a channel still shows the rows: the
// channels then open with the one found by itself (see channelOpener).

/** The TV's own TV apps, best first, for a TV where more than one opens channels (a Philips, Live Channels too). */
private val TV_APP_PREFERENCE = listOf(
    "org.droidtv.playtv", // Philips: Watch TV
    "org.droidtv.channels", // Philips: the channel list
    "com.sony.dtv.tvx", // Sony
    "com.google.android.tv", // Google's Live Channels
)

/** The apps that open channels, as last asked, and when (see channelApps). */
@Volatile private var channelAppsCache: Pair<Long, List<String>>? = null

/**
 * Apps that open a channel link (content://android.media.tv/channel/<id>). Asked again after a minute, or when
 * apps are installed or removed (see forgetChannelApps): the rows ask for it often.
 */
internal fun MainActivity.channelApps(): List<String> {
    val now = android.os.SystemClock.elapsedRealtime()
    channelAppsCache?.let { (at, list) -> if (now - at < 60_000) return list }
    val uri = TvContract.buildChannelUri(1)
    val list = listOf(Intent(Intent.ACTION_VIEW, uri), Intent(Intent.ACTION_VIEW).setType(TvContract.Channels.CONTENT_ITEM_TYPE))
        .flatMap { packageManager.queryIntentActivities(it, 0).map { r -> r.activityInfo.packageName } }
        .filter { it != packageName }
        .distinct()
    channelAppsCache = now to list
    return list
}

/** Apps were installed or removed: the apps that open channels are asked again. */
internal fun forgetChannelApps() {
    channelAppsCache = null
}

/** Whether [pkg] can open a channel link. */
internal fun MainActivity.isTvApp(pkg: String) = pkg in channelApps()

/**
 * The TV app found by itself: the one Android opens channel links with by default, else the TV's own (see
 * TV_APP_PREFERENCE), else the first app that opens them. Null on a device without one (a stick, a phone).
 */
internal fun MainActivity.autoTvApp(): String? {
    val all = channelApps()
    if (all.isEmpty()) return null
    val default = packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(1)), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
    return default?.takeIf { it in all } ?: autoTvAppOf(all, TV_APP_PREFERENCE)
}

/** The first of [preference] among [all] (the apps that open channels), else the first of [all]. */
internal fun autoTvAppOf(all: List<String>, preference: List<String>): String? =
    preference.firstOrNull { it in all } ?: all.firstOrNull()

/** The app the user picked as the TV app, while it's installed; null: found by itself. */
internal fun MainActivity.chosenTvApp(): String? =
    prefs.getString(K.TV_APP, null)?.takeIf { pkg -> runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess }

/** The TV app: the one picked, else the one found by itself. */
internal fun MainActivity.tvApp(): String? = chosenTvApp() ?: autoTvApp()

/** The app that opens a TV channel: the TV app when it can, else the one found by itself. */
internal fun MainActivity.channelOpener(): String? = tvApp()?.takeIf { isTvApp(it) } ?: autoTvApp()

/** The apps a user can pick as the TV app: real apps, not Zephyr TV's own tiles (theirs have a ':'). */
private fun MainActivity.pickableApps() = apps.filter { ':' !in it.pkg && it.pkg != packageName }

/** Settings → TV and EPG → TV app: Automatic, the apps that open channels, then Other app… for any app. */
internal fun MainActivity.pickTvApp() {
    val chosen = chosenTvApp()
    val auto = autoTvApp()
    val openers = channelApps().filter { pkg -> pickableApps().any { it.pkg == pkg } }
    val rows = listOf(page(getString(R.string.tv_app_auto, auto?.let { appLabel(it) } ?: getString(R.string.tv_app_none)),
        if (chosen == null) MainActivity.CHOSEN else "") { useTvApp(null) }) +
        openers.map { pkg -> page(appLabel(pkg), if (pkg == chosen) MainActivity.CHOSEN else "") { useTvApp(pkg) } } +
        page(getString(R.string.tv_app_other), chosen?.takeIf { it !in openers }?.let { appLabel(it) } ?: MainActivity.SUBMENU) { pickAnyTvApp() }
    showPage(getString(R.string.setting_tv_app), rows, back = { renderSettings(TV_APP_ROW) })
}

/** Any app as the TV app. One that can't open channels shows the rows; the channels open with the one found by itself. */
private fun MainActivity.pickAnyTvApp() {
    val chosen = chosenTvApp()
    showPage(getString(R.string.tv_app_other), pickableApps().sortedBy { it.label.lowercase() }.map { a ->
        page(a.label, if (a.pkg == chosen) MainActivity.CHOSEN else if (isTvApp(a.pkg)) "" else getString(R.string.tv_app_rows_only)) { useTvApp(a.pkg) }
    }, back = { pickTvApp() })
}

/** [pkg] as the TV app (null: found by itself). The rows under the tiles are made again. */
private fun MainActivity.useTvApp(pkg: String?) {
    prefs.edit().apply { if (pkg == null) remove(K.TV_APP) else putString(K.TV_APP, pkg) }.apply()
    watchKey = null
    sectionCache = null
    renderSettings(TV_APP_ROW)
}
