package link.mccoy88f.zephyr

import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.text.SpannableString
import android.text.style.BulletSpan
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import link.mccoy88f.zephyr.MainActivity.Companion.REPO
import link.mccoy88f.zephyr.MainActivity.Companion.UPDATE_ROW

// Updates from GitHub releases as the settings show them: checking, installing, what's new, the badge.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

internal fun MainActivity.myVersion() = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

/** Whether betas are offered too (Updates → Receive betas): versions with new things that aren't fully tried yet. */
internal fun MainActivity.betaUpdates() = prefs.getBoolean(K.UPD_BETA, false)

/** The newer version a check found, if any (null again once it's installed). */
internal fun MainActivity.availableUpdate() = prefs.getString(K.UPD_VER, null)?.takeIf { isUpdateAvailable(it, myVersion(), betaUpdates()) }

/** Whether [remoteVer] should be offered given [currentVer] and whether [receiveBetas] is enabled. */
internal fun isUpdateAvailable(remoteVer: String, currentVer: String, receiveBetas: Boolean): Boolean {
    if (!receiveBetas && '-' in remoteVer) return false
    if (newerVersion(remoteVer, currentVer)) return true
    if (!receiveBetas && '-' in currentVer && remoteVer != currentVer) return true
    return false
}

private fun MainActivity.isShowingUpdates(): Boolean {
    if (settingsScrim.visibility != View.VISIBLE) return false
    val title = findViewById<TextView>(R.id.settingsTitle)?.text?.toString()
    return title == getString(R.string.whats_new_title, myVersion())
}

internal fun MainActivity.checkUpdate(force: Boolean) {
    if (checking || !force && System.currentTimeMillis() - prefs.getLong(K.UPD_TIME, 0) < 24 * 3600_000L) return
    checking = true
    if (force) {
        if (isShowingUpdates()) showUpdates()
        refreshSettings(UPDATE_ROW)
    }
    netIo.execute {
        val latest = try {
            Updater.latest(REPO, beta = betaUpdates())
        } catch (e: Exception) {
            warn("update check failed ($e)")
            null
        }
        if (latest != null) prefs.edit().putString(K.UPD_VER, latest).putLong(K.UPD_TIME, System.currentTimeMillis()).apply()
        ui.post {
            checking = false
            val ver = availableUpdate()
            if (ver != null) {
                prefs.edit().putString(K.UPD_TOLD, ver).apply()
                Toast.makeText(this, getString(R.string.toast_update_available, ver), Toast.LENGTH_LONG).show()
            } else if (force) {
                Toast.makeText(this, if (latest == null) getString(R.string.toast_update_check_failed) else getString(R.string.toast_update_up_to_date), Toast.LENGTH_SHORT).show()
            }
            if (isShowingUpdates()) showUpdates()
            refreshSettings(UPDATE_ROW)
            updateBadge()
        }
    }
}

/** What's new in this version (the CHANGELOG.md section the build put in), with check or install on top. */
internal fun MainActivity.showUpdates() {
    prefs.edit().putString(K.SEEN_NOTES, myVersion()).apply()
    updateBadge()
    val ver = availableUpdate()
    val topRow = when {
        updating -> page(getString(R.string.updates_updating)) {}
        checking -> page(getString(R.string.updates_checking)) {}
        ver != null -> page(getString(R.string.install_update, ver)) { installUpdate(ver) }
        else -> page(getString(R.string.check_updates)) { checkUpdate(force = true) }
    }
    val betaRow = page(getString(R.string.setting_updates_beta), toggle(betaUpdates())) {
        // The other channel's newest version, checked at once: what was found before may not be in it.
        prefs.edit().putBoolean(K.UPD_BETA, !betaUpdates()).remove(K.UPD_VER).remove(K.UPD_TIME).apply()
        updateBadge()
        checkUpdate(force = true)
        showUpdates()
    }
    val rows = listOf(topRow, betaRow)
    showPage(getString(R.string.whats_new_title, myVersion()), rows, back = { renderSettings(UPDATE_ROW) })
    // Focusable, lit on focus, so Down walks through notes longer than the panel and it scrolls along.
    getString(R.string.whats_new).split('\n').forEach {
        settingsList.addView(label("", 13f).apply {
            text = SpannableString(it).apply { setSpan(BulletSpan(dp(10), fg), 0, it.length, 0) }
            maxLines = Int.MAX_VALUE
            ellipsize = null
            alpha = 0.7f
            isFocusable = true
            setOnFocusChangeListener { v, focused -> v.alpha = if (focused) 1f else 0.7f }
            setPadding(dp(14), dp(10), dp(14), dp(2))
        })
    }
    // showPage stops Down at its last row (Receive betas): from there it goes on to the notes.
    settingsList.getChildAt(rows.size - 1).nextFocusDownId = View.NO_ID
    settingsList.getChildAt(settingsList.childCount - 1).let { it.id = View.generateViewId(); it.nextFocusDownId = it.id }
}

/** Notes the user hasn't opened since updating. A fresh install has nothing new to tell. */
internal fun MainActivity.notesUnseen(): Boolean {
    val info = packageManager.getPackageInfo(packageName, 0)
    return info.firstInstallTime != info.lastUpdateTime && prefs.getString(K.SEEN_NOTES, null) != info.versionName
}

/**
 * A dot on the Zephyr TV settings icon while an update waits, and on each row on the way to it (System, then Updates;
 * see updateDot), so the dot leads there.
 */
internal fun MainActivity.updateBadge() {
    val icon = findViewById<ImageView>(R.id.launcherSettings)
    icon.overlay.clear()
    if (availableUpdate() == null) return
    val size = dp(9)
    badge.setBounds(icon.width - size - dp(3), dp(3), icon.width - dp(3), dp(3) + size)
    icon.overlay.add(badge)
}

/** The update dot on the panel's row [index] (a settings row), when an update waits. */
internal fun MainActivity.updateDot(index: Int) {
    if (availableUpdate() == null) return
    (settingsList.getChildAt(index) as? LinearLayout)?.addView(View(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(MainActivity.BADGE) }
    }, 1, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginStart = dp(8); marginEnd = dp(8) })
}

internal fun MainActivity.installUpdate(ver: String) {
    if (updating) return
    if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
        Toast.makeText(this, getString(R.string.toast_update_permission), Toast.LENGTH_LONG).show()
        return openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
    }
    // Android 7: one switch for all apps, Unknown sources, in Security.
    @Suppress("DEPRECATION") val unknownSources = Settings.Secure.INSTALL_NON_MARKET_APPS
    if (android.os.Build.VERSION.SDK_INT < 26 && Settings.Secure.getInt(contentResolver, unknownSources, 0) != 1) {
        Toast.makeText(this, getString(R.string.toast_update_permission), Toast.LENGTH_LONG).show()
        return openSettings(Settings.ACTION_SECURITY_SETTINGS, null)
    }
    updating = true
    if (isShowingUpdates()) showUpdates()
    refreshSettings(UPDATE_ROW)
    longIo.execute { // a download of a megabyte or more: not on the threads pages wait for
        val ok = try {
            Updater.install(this, REPO, ver)
            true
        } catch (e: Exception) {
            warn("update download failed ($e)")
            false
        }
        ui.post {
            if (!ok) {
                updating = false
                Toast.makeText(this, getString(R.string.toast_update_download_failed), Toast.LENGTH_LONG).show()
            }
            if (isShowingUpdates()) showUpdates()
            refreshSettings(UPDATE_ROW)
        }
    }
}

/**
 * The install's outcome, passed on by InstallResult (which shows Android's confirm box itself). Any app can
 * send this action, so it only ever resets the row and may show a toast: nothing in it gets launched.
 */
internal fun MainActivity.installStatus(intent: Intent) {
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) return
    updating = false
    if (status != PackageInstaller.STATUS_SUCCESS) {
        val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        warn("update install failed: $status $why")
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(this, getString(R.string.toast_update_install_failed), Toast.LENGTH_LONG).show()
        }
    }
    if (isShowingUpdates()) showUpdates()
    refreshSettings(UPDATE_ROW)
}
