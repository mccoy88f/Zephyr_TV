package link.mccoy88f.zephyr

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import java.io.File
import link.mccoy88f.zephyr.MainActivity.Companion.CHOSEN
import link.mccoy88f.zephyr.MainActivity.Companion.WRITE_SECURE
import link.mccoy88f.zephyr.SettingsRows.SCREENSAVER_ROW

// The screensaver's settings: Zephyr TV's own (paintings, photos) or another one, and trying it.

internal fun MainActivity.gallery() = ComponentName(this, GalleryDream::class.java).flattenToString()

internal fun MainActivity.currentScreensaver() = Settings.Secure.getString(contentResolver, "screensaver_components").orEmpty()

internal fun MainActivity.galleryOn() = currentScreensaver() == gallery()

internal fun MainActivity.screensaverName(component: String = currentScreensaver()): String {
    val pkg = ComponentName.unflattenFromString(component)?.packageName ?: return getString(R.string.state_off)
    return try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        getString(R.string.screensaver_system_default)
    }
}

/** The folder the Gallery screensaver shows instead of paintings, or null for paintings. */
internal fun MainActivity.photoScreensaver() = prefs.getString(K.SS_FOLDER, null)

internal fun MainActivity.pickScreensaver(focus: Int = 0) {
    val paintings = galleryOn() && photoScreensaver() == null
    val tmdb = galleryOn() && photoScreensaver() == SS_TMDB
    val photos = galleryOn() && photoScreensaver() != null && !tmdb
    val other = if (galleryOn()) prefs.getString(K.PREV_SCREENSAVER, "").orEmpty() else currentScreensaver()
    showPage(getString(R.string.setting_screensaver), listOf(
        page(getString(R.string.screensaver_gallery_paintings), if (paintings) CHOSEN else if (downloading) getString(R.string.updates_checking) else PAINTINGS_MB) {
            useGallery(null)
            pickScreensaver(0)
        },
        page(getString(R.string.screensaver_your_photos), if (photos) "${wallpaperName(photoScreensaver()!!)} $CHOSEN" else "") {
            pickFolder({ pickScreensaver(1) }) { choice ->
                useGallery(choice)
                pickScreensaver(1)
            }
        },
        page(screensaverName(other), if (galleryOn()) "" else CHOSEN) {
            if (galleryOn()) stopGallery()
            pickScreensaver(2)
        },
    ) + listOfNotNull(
        // Spotlight's backdrops (Cinemeta's, or TMDB's lists with a key).
        page(getString(R.string.screensaver_tmdb), if (tmdb) CHOSEN else "") {
            useGallery(SS_TMDB)
            pickScreensaver(3)
        },
    ), focus) { renderSettings(SCREENSAVER_ROW) }
}

/** Gallery on, with paintings ([folder] null, downloaded first if needed) or a folder of photos. */
internal fun MainActivity.useGallery(folder: String?) {
    prefs.edit().putString(K.SS_FOLDER, folder).apply()
    if (folder == null && !paintingsReady(this)) return downloadPaintings { useGallery(null) }
    if (folder == SS_TMDB) prefetchAllBackdrops() // the screensaver shows what's on disk; it doesn't download
    if (folder != null) paintingsDir(this).deleteRecursively() // not needed for photos: give the space back
    if (galleryOn()) return
    if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
        // Without the adb permission Zephyr TV can't switch it itself; Android's own list has "Gallery".
        toast(R.string.toast_screensaver_choose_gallery)
        return openScreensaverSettings()
    }
    prefs.edit().putString(K.PREV_SCREENSAVER, currentScreensaver()).apply()
    Settings.Secure.putString(contentResolver, "screensaver_components", gallery())
    Settings.Secure.putInt(contentResolver, "screensaver_enabled", 1)
}

/** Back to whatever screensaver was set before Gallery. */
internal fun MainActivity.stopGallery() {
    if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return openScreensaverSettings()
    Settings.Secure.putString(contentResolver, "screensaver_components", prefs.getString(K.PREV_SCREENSAVER, "") ?: "")
    paintingsDir(this).deleteRecursively() // another screensaver is in charge: give the space back
}

/** Android's screensaver settings (timing, sleep): plain Android TV and Google TV name them differently. */
internal fun MainActivity.openScreensaverSettings() {
    for (action in listOf(Settings.ACTION_DREAM_SETTINGS, "com.google.android.tv.settings.ambient")) {
        try {
            return startActivity(Intent(action))
        } catch (e: Exception) {
        }
    }
    openSettings(Settings.ACTION_SETTINGS)
}

/**
 * Fetches the Gallery paintings into a temporary folder and only swaps it in once every file arrived,
 * so a dropped connection never leaves a half-filled Gallery behind.
 */
internal fun MainActivity.downloadPaintings(then: () -> Unit) {
    if (downloading) return
    downloading = true
    Toast.makeText(this, getString(R.string.toast_screensaver_downloading, PAINTINGS_MB), Toast.LENGTH_SHORT).show()
    Thread {
        val part = File(filesDir, "paintings.part").apply { deleteRecursively(); mkdirs() }
        val ok = try {
            for (f in PAINTING_FILES) openStream(PAINTINGS_URL + f).use { input ->
                File(part, f).outputStream().use { input.copyTo(it) }
            }
            paintingsDir(this).deleteRecursively()
            part.renameTo(paintingsDir(this))
        } catch (e: Exception) {
            warn("paintings download failed ($e)")
            part.deleteRecursively()
            false
        }
        ui.post {
            downloading = false
            if (ok) then() else toast(R.string.toast_screensaver_download_failed)
            refreshSettings(SCREENSAVER_ROW)
        }
    }.start()
}

/** Starts whichever screensaver is set, through SystemUI's "dream now" entry point. */
internal fun MainActivity.testScreensaver() {
    try {
        startActivity(Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))
    } catch (e: Exception) {
        toast(R.string.toast_screensaver_cannot_start, long = false)
    }
}

// ---------- updates (GitHub releases) ----------
// Checked once a day from the home screen; a new version is announced once, then waits in settings.
// The APK streams straight into an install session, so there's no temporary file to clean up.
