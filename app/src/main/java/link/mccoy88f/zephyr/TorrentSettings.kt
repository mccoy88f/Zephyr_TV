package link.mccoy88f.zephyr

import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import link.mccoy88f.zephyr.MainActivity.Companion.REPO
import link.mccoy88f.zephyr.SettingsRows.TORRENT_ROW

// Settings → Content → Torrent engine: Zephyr Torrent installed, updated and removed (see Torrents), and its
// settings: how much its cache may hold, whether it keeps it, whether finished downloads are shared on. The engine
// comes from Zephyr TV's own release on GitHub (ZephyrTorrent.apk, next to ZephyrTV.apk), through the same install
// session as Zephyr TV's updates. Functions of MainActivity, kept here by topic.

/** Being installed (the download and Android's install). */
@Volatile private var installingEngine = false

/** The cache limits to choose from, in MB. */
private val CACHE_SIZES = listOf(1024, 2048, 4096, 8192)

/** The row's value: not installed, or its version (with "update" when an older one than this release's). */
internal fun MainActivity.torrentValue(): String {
    val v = Torrents.installed(this) ?: return getString(if (installingEngine) R.string.torrent_installing else R.string.torrent_not_installed)
    return if (v.first < Torrents.bundledVersion(this)) getString(R.string.torrent_update_value, v.second) else v.second
}

/**
 * The engine's page: installed, its version, its settings (asked of the engine first, off the main thread: its process
 * may have to start), Update and Uninstall; not installed, what torrents mean for the user, then Install.
 */
internal fun MainActivity.torrentPage(focus: Int = 0) {
    val title = getString(R.string.setting_torrent_engine)
    val installed = Torrents.installed(this)
    // What a torrent shows of the TV, and whose the choice is: above the rows, whether it's installed or not.
    val intro = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(4), dp(14), dp(8))
        addView(label(getString(R.string.torrent_intro), 13f).apply {
            maxLines = 10
            setLineSpacing(0f, 1.15f)
        })
    }
    if (installed == null) {
        val rows = listOf(
            if (installingEngine) page(getString(R.string.torrent_installing)) { }
            else page(getString(R.string.torrent_install), getString(R.string.torrent_install_size)) { installEngine() },
        )
        return showPage(title, rows, top = intro, back = { renderSettings(TORRENT_ROW) })
    }
    netIo.execute {
        val s = Torrents.settings(this)
        val nodes = Torrents.call(this, "health")?.getLong("nodes")
        ui.post {
            if (settingsScrim.visibility != android.view.View.VISIBLE) return@post // the settings closed meanwhile
            val cache = s?.getInt("cache_mb") ?: 2048
            val keep = s?.getBoolean("keep_cache") ?: false
            val seed = s?.getBoolean("seed") ?: false
            // The page again with the engine's answer, where it was (see showPage); no answer, said so.
            fun set(b: Bundle.() -> Unit, at: Int) = netIo.execute {
                val ok = Torrents.settings(this, Bundle().apply(b)) != null
                ui.post {
                    if (!ok) android.widget.Toast.makeText(this, getString(R.string.torrent_disconnected) +
                        (Torrents.lastError?.let { " · $it" } ?: ""), android.widget.Toast.LENGTH_LONG).show()
                    if (pageShowing(title)) torrentPage(at)
                }
            }
            val older = installed.first < Torrents.bundledVersion(this)
            val rows = listOfNotNull(
                page(getString(R.string.torrent_state), engineState(nodes)) { },
                page(getString(R.string.torrent_version), installed.second) { },
                if (!older) null else if (installingEngine) page(getString(R.string.torrent_installing)) { }
                    else page(getString(R.string.torrent_update)) { installEngine() },
                // How much the streams' cache may hold: a film bigger than this is only downloaded around where it plays.
                page(getString(R.string.torrent_cache), sizeName(cache.toLong() shl 20)) {
                    set({ putInt("cache_mb", CACHE_SIZES[(CACHE_SIZES.indexOf(cache) + 1).mod(CACHE_SIZES.size)]) }, if (older) 3 else 2)
                },
                page(getString(R.string.torrent_keep_cache), toggle(keep)) { set({ putBoolean("keep_cache", !keep) }, if (older) 4 else 3) },
                page(getString(R.string.torrent_seed), toggle(seed)) { set({ putBoolean("seed", !seed) }, if (older) 5 else 4) },
                page(getString(R.string.torrent_uninstall)) {
                    runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", Torrents.enginePackage(this), null))) }
                },
            )
            showPage(title, rows, focus = focus.coerceAtLeast(0), top = intro, back = { renderSettings(TORRENT_ROW) })
            val row = settingsList.getChildAt(1) as? LinearLayout ?: return@post
            stateDot(row, nodes)
            watchEngine(title, row)
        }
    }
}

/** The engine's state as the status row says it: off (no answer), joining the network (no DHT nodes yet), on. */
private fun MainActivity.engineState(nodes: Long?): String = when {
    nodes == null -> getString(R.string.torrent_disconnected) + (Torrents.lastError?.let { " · " + it.take(40) } ?: "")
    nodes <= 0 -> getString(R.string.torrent_joining)
    else -> getString(R.string.torrent_connected, nodes.toInt())
}

/** The status row's dot: red off, yellow joining, green on. */
private fun MainActivity.stateDot(row: LinearLayout, nodes: Long?) {
    val color = when {
        nodes == null -> 0xFFE53935.toInt()
        nodes <= 0 -> 0xFFFDD835.toInt()
        else -> 0xFF43A047.toInt()
    }
    val dot = row.findViewWithTag<android.view.View>("engine_dot") ?: android.view.View(this).apply {
        tag = "engine_dot"
        row.addView(this, 1, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(10); marginEnd = dp(4) })
    }
    dot.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color) }
}

/** The status row asked again every two seconds while the engine's page is up: joining turns to on as it finds nodes. */
private fun MainActivity.watchEngine(title: String, row: LinearLayout) {
    ui.postDelayed({
        if (!pageShowing(title) || !row.isAttachedToWindow) return@postDelayed
        netIo.execute {
            val nodes = Torrents.call(this, "health")?.getLong("nodes")
            ui.post {
                if (!row.isAttachedToWindow) return@post
                (row.getChildAt(row.childCount - 1) as? android.widget.TextView)?.text = engineState(nodes)
                stateDot(row, nodes)
                watchEngine(title, row)
            }
        }
    }, 2000)
}

/**
 * The engine downloaded from this version's release and installed; Android's own box asks first, unless Zephyr TV
 * may install apps on its own (Android 12 and later, when it installed them before).
 */
private fun MainActivity.installEngine() {
    if (installingEngine) return
    if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
        toast(R.string.toast_update_permission)
        return openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
    }
    installingEngine = true
    torrentPage()
    longIo.execute {
        val ok = runCatching {
            Updater.install(this, REPO, myVersion().substringBefore('+'), apk = "ZephyrTorrent.apk", pkg = Torrents.enginePackage(this), what = "torrent")
        }.onFailure { warn("torrent engine download failed ($it)") }.isSuccess
        ui.post {
            if (!ok) {
                installingEngine = false
                toast(R.string.torrent_install_failed)
            }
            if (pageShowing(getString(R.string.setting_torrent_engine))) torrentPage()
        }
    }
}

/** The engine's install came back (see installStatus): torrent streams offered from now, if it went. */
internal fun MainActivity.torrentInstalled(status: Int, why: String?) {
    installingEngine = false
    if (status != PackageInstaller.STATUS_SUCCESS) {
        warn("torrent engine install failed: $status $why")
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED) toast(R.string.torrent_install_failed)
    } else {
        Torrents.refresh(this) // the streams kept already have their torrents, left out until now (see cachedStreams)
        toast(R.string.torrent_installed)
    }
    if (pageShowing(getString(R.string.setting_torrent_engine))) torrentPage()
}
