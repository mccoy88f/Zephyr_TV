package link.mccoy88f.zephyr

import android.content.ContentResolver
import android.os.Build
import android.net.Uri
import java.io.InputStream
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.provider.MediaStore
import android.widget.ImageView
import java.io.File
import java.io.IOException
import link.mccoy88f.zephyr.MainActivity.Companion.BUNDLED_WALLPAPERS
import link.mccoy88f.zephyr.MainActivity.Companion.CHOSEN
import link.mccoy88f.zephyr.MainActivity.Companion.MEDIA_REQUEST
import link.mccoy88f.zephyr.MainActivity.Companion.PHOTO_EVERY
import link.mccoy88f.zephyr.MainActivity.Companion.READ_PHOTOS
import link.mccoy88f.zephyr.MainActivity.Companion.TMDB_FRESH_MS
import link.mccoy88f.zephyr.SettingsRows.DARK_WP_ROW
import link.mccoy88f.zephyr.SettingsRows.LIGHT_WP_ROW
import link.mccoy88f.zephyr.SettingsRows.PHOTO_EVERY_ROW

// Wallpapers: the bundled ones, TMDB backdrops, photos from the phone, and a folder of photos that changes.

internal fun MainActivity.wallpaperDir() = getExternalFilesDir("wallpapers")

/** A plain choice first (just the theme's base colour, no image), then pictures. */
internal fun MainActivity.wallpaperChoices(forDark: Boolean): List<Pair<String, String>> =
    listOf(plainWallpaper(forDark).let { wallpaperName(it) to it }) +
        BUNDLED_WALLPAPERS.keys.map { wallpaperName("res:$it") to "res:$it" } +
        listOf(wallpaperName("tmdb:trending") to "tmdb:trending") + // Cinemeta's titles without a TMDB key
        (wallpaperDir()?.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name }?.map { it.nameWithoutExtension to "file:${it.name}" } ?: emptyList()) +
        listOfNotNull(currentWallpaper(forDark).takeIf { it.startsWith("folder:") }?.let { wallpaperName(it) to it })

// Light gets a soft white (the light ground colour), not #FFFFFF: pure white glares on a TV.
internal fun MainActivity.plainWallpaper(forDark: Boolean) = if (forDark) "plain:Pure black" else "plain:Soft white"

internal fun MainActivity.defaultWallpaper(forDark: Boolean) = bundledWallpaper(forDark)

internal fun MainActivity.currentWallpaper(forDark: Boolean = dark) = chooseWallpaper(
    prefs.getString(if (forDark) K.WP_DARK else K.WP_LIGHT, null), !prefs.getString(K.TMDB_API_KEY, null).isNullOrBlank(), forDark)

/** Fade your own photos into the background at the bottom. Bundled wallpapers have it baked in either way. */
internal fun MainActivity.wallpaperFade() = prefs.getBoolean(K.WP_FADE, true)

/** Slow Ken Burns zoom/pan on the home wallpaper; it pauses on its own after a while without a key (see Scene). Off: a still photo. */
internal fun MainActivity.wallpaperDrift() = prefs.getBoolean(K.WP_DRIFT, true)

/** Continue Playing tile + Watch Next / now-playing work. Off skips those queries. */
internal fun MainActivity.continuePlaying() = prefs.getBoolean(K.CW_ROW, true)

internal fun MainActivity.wallpaperName(choice: String) = when {
    choice == "tmdb:trending" -> getString(R.string.wallpaper_tmdb_trending)
    choice.startsWith("folder:") -> getString(R.string.wallpaper_prefix_folder, choice.substringAfter(':').substringAfter(':'))
    choice == "plain:Pure black" -> getString(R.string.wallpaper_plain_pure_black)
    choice == "plain:Soft white" -> getString(R.string.wallpaper_plain_soft_white)
    choice == "res:Rockies (day)" -> getString(R.string.wallpaper_rockies_day)
    choice == "res:Rockies (dusk)" -> getString(R.string.wallpaper_rockies_dusk)
    else -> choice.substringAfter(':').substringBeforeLast('.')
}

internal fun MainActivity.loadWallpaper() {
    val view = findViewById<ImageView>(R.id.wallpaper)
    val choice = currentWallpaper()
    val gen = scene.bump()
    if (choice.startsWith("plain:")) { // the root's ground colour shows through
        view.setImageDrawable(null)
        scene.clear()
        return
    }
    val fallback = defaultWallpaper(dark)
    io.execute {
        val bmp = try {
            decodeWallpaper(choice)
        } catch (e: Exception) {
            null
        } ?: decodeWallpaper(fallback)
        val blur = bmp?.let { blurPhoto(it) }
        ui.post {
            if (gen != scene.generation) {
                bmp?.recycle()
                blur?.recycle()
                return@post
            }
            // The next title's countdown starts once this backdrop is up, so slow loads never pile up.
            if (choice == "tmdb:trending") {
                restartSpotlightTick()
                prefetchBackdrops()
            }
            if (bmp == null || blur == null) {
                view.setImageDrawable(null)
                scene.clear()
                renderSpotlight()
                return@post
            }
            // Soft change from one photo to the next, then a slow drift. The old photo is dropped once the fade ends.
            scene.show(view, bmp, blur)
            renderSpotlight()
        }
    }
}

internal fun MainActivity.decodeWallpaper(choice: String): Bitmap? {
    BUNDLED_WALLPAPERS[choice.removePrefix("res:")]?.let { res ->
        return BitmapFactory.decodeResource(resources, res)
    }
    if (choice == "tmdb:trending") {
        val key = prefs.getString(K.TMDB_API_KEY, null)?.takeIf { it.isNotBlank() }
        val type = prefs.getString(K.TMDB_TYPE, "all") ?: "all"
        // Trending is per day: refetch now and then, or a home screen that never closes keeps last week's list.
        // Cinemeta's popular titles need no key (in the TV's language with one); TMDB's lists need it.
        if (tmdbTrending.isEmpty() || System.currentTimeMillis() - tmdbFetchedAt > TMDB_FRESH_MS) {
            val listId = prefs.getString(K.TMDB_LIST, null)
            val source = spotlightSource()
            val fresh = when {
                key == null || source == "cinemeta" -> spotlightTitles(type)
                source == "list" && listId != null -> TmdbClient.fetchList(key, listId, tmdbLang(), type)
                else -> TmdbClient.fetchCategory(key, source, tmdbLang(), type, TmdbClient.region(tmdbLang()))
            }
            // Films not out at home yet (in cinemas, or to come) left out, when they're hidden (see Releases).
            val shown = withoutUnreleased(fresh, key)
            if (shown.isNotEmpty()) {
                val fresh = shown
                // A different order each time, so the same top titles don't always open the round.
                tmdbTrending = fresh.shuffled()
                tmdbIndex = 0
                tmdbFetchedAt = System.currentTimeMillis()
                saveBackdropTitles(fresh)
            }
        }
        val item = spotlightItem() ?: return null
        val url = TmdbClient.backdropUrl(item.backdropPath) ?: return null
        val file = Images.file(url, Images.Kind.BACKDROP)
        val bmp = fitWallpaper { file.inputStream() } ?: return null
        val h = bmp.height.toFloat()
        // Only the fade under the apps row, from halfway down: the backdrop shows clear everywhere else. The
        // clock, the icons and the Spotlight text read by their halo.
        if (wallpaperFade()) glassOrFade(bmp, h * 0.5f, h * 0.8f)
        return bmp
    }
    // fitWallpaper's bitmap is mutable: the fades are painted onto it.
    val bmp = if (choice.startsWith("folder:")) {
        val uri = ContentUris.withAppendedId(PHOTOS, folderPhoto(choice) ?: return null)
        fitWallpaper { contentResolver.openInputStream(uri) ?: throw IOException("gone: $uri") }
    } else {
        val file = File(wallpaperDir(), choice.removePrefix("file:"))
        fitWallpaper { file.inputStream() }
    } ?: return null
    // Same fade as the bundled ones: clear above 28% height, into the background colour by 50%.
    val h = bmp.height.toFloat()
    val base = ground and 0x00FFFFFF
    if (wallpaperFade()) glassOrFade(bmp, h * 0.28f, h * 0.5f)
    // Photos rarely have the bundled ones' empty sky: a soft veil behind the clock and top-bar icons keeps them readable.
    // Strong over the clock and icons (top ~20%), gone by 30% so the photo still shows above the apps.
    Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h * 0.3f, Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, h * 0.3f, intArrayOf(base or 0xC0000000.toInt(), base or 0xA0000000.toInt(), base),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    })
    return bmp
}

/**
 * Fades the photo into the ground colour from [from] to [to]. Glass keeps the photo instead: a soft shade from
 * the middle down, so the rows' white text reads while the photo still shows behind the glass cards.
 */
internal fun MainActivity.glassOrFade(bmp: Bitmap, from: Float, to: Float) {
    val h = bmp.height.toFloat()
    val base = ground and 0x00FFFFFF
    Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h, Paint().apply {
        shader = if (glass) LinearGradient(0f, h * 0.45f, 0f, h, intArrayOf(0, 0x73000000, 0xA6000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        else LinearGradient(0f, from, 0f, to, base, base or 0xE6000000.toInt(), Shader.TileMode.CLAMP)
    })
}

/** Wallpapers page, framed by "Add from phone…" first and, once there are photos, "Remove a photo…" last. */
internal fun MainActivity.pickWallpaper(forDark: Boolean) {
    val row = if (forDark) DARK_WP_ROW else LIGHT_WP_ROW
    val choices = wallpaperChoices(forDark)
    val own = choices.filter { it.second.startsWith("file:") }
    val current = currentWallpaper(forDark)
    val rows = listOf(page(getString(R.string.add_from_phone)) { uploadWallpaper(forDark) }, page(getString(R.string.choose_from_tv)) { pickFolder(forDark) }) +
        choices.map { (name, choice) -> page(name, if (choice == current) CHOSEN else "") { setWallpaper(forDark, choice); renderSettings(row) } } +
        if (own.isEmpty()) emptyList() else listOf(page(getString(R.string.remove_photo)) { removeWallpaper(own, forDark) })
    showPage(if (forDark) getString(R.string.setting_dark_wallpaper) else getString(R.string.setting_light_wallpaper), rows, 2 + choices.indexOfFirst { it.second == current }) {
        renderSettings(row)
    }
}

internal fun MainActivity.setWallpaper(forDark: Boolean, choice: String) {
    prefs.edit().putString(if (forDark) K.WP_DARK else K.WP_LIGHT, choice)
        .remove(photoKey(forDark)).putLong(photoAtKey(forDark), System.currentTimeMillis()).apply()
    if (forDark == dark) loadWallpaper()
}

// ---------- folder wallpapers ----------

internal fun MainActivity.photoKey(forDark: Boolean = dark) = if (forDark) K.WP_PHOTO_DARK else K.WP_PHOTO_LIGHT

internal fun MainActivity.photoAtKey(forDark: Boolean = dark) = if (forDark) K.WP_PHOTO_AT_DARK else K.WP_PHOTO_AT_LIGHT

internal fun MainActivity.photoEvery() = prefs.getInt(K.WP_EVERY, 30)

internal fun MainActivity.photoEveryName(minutes: Int) = when (minutes) {
    1 -> getString(R.string.interval_every_minute)
    5 -> getString(R.string.interval_every_5_min)
    10 -> getString(R.string.interval_every_10_min)
    15 -> getString(R.string.interval_every_15_min)
    30 -> getString(R.string.interval_every_30_min)
    60 -> getString(R.string.interval_every_hour)
    1440 -> getString(R.string.interval_every_day)
    else -> "$minutes min"
}

internal fun MainActivity.pickPhotoEvery() {
    val rows = PHOTO_EVERY.map { minutes ->
        page(photoEveryName(minutes), if (minutes == photoEvery()) CHOSEN else "") {
            prefs.edit().putInt(K.WP_EVERY, minutes).apply()
            ui.removeCallbacks(wallpaperTick)
            ui.post(wallpaperTick)
            renderSettings(PHOTO_EVERY_ROW)
        }
    }
    showPage(getString(R.string.setting_folder_photo_changes), rows, PHOTO_EVERY.indexOf(photoEvery())) { renderSettings(PHOTO_EVERY_ROW) }
}

internal fun MainActivity.nextPhoto() {
    prefs.edit().remove(photoKey()).putLong(photoAtKey(), System.currentTimeMillis()).apply()
    loadWallpaper()
}

/** The folder's photo on show now, or the next one in this round's shuffle. Runs on the io thread. */
internal fun MainActivity.folderPhoto(choice: String): Long? {
    val ids = folderPhotos(contentResolver, choice)
    val all = ids.toHashSet()
    prefs.getLong(photoKey(), -1).takeIf { it in all }?.let { return it }
    deck.removeAll { it !in all } // deleted, or another folder's
    if (deck.isEmpty()) deck.addAll(ids.shuffled())
    return deck.removeFirstOrNull()?.also { prefs.edit().putLong(photoKey(), it).apply() }
}

/** Every folder with photos in it, internal storage and USB drives alike: (bucket id, name, photo count). */
internal fun MainActivity.photoFolders(): List<Triple<String, String, Int>> {
    val found = LinkedHashMap<String, Pair<String, Int>>()
    contentResolver.query(PHOTOS, arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
        null, null, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} COLLATE NOCASE")?.use { c ->
        while (c.moveToNext()) {
            val id = c.getString(0) ?: continue
            found[id] = (c.getString(1) ?: getString(R.string.folder_untitled)) to (found[id]?.second ?: 0) + 1
        }
    }
    return found.map { (id, v) -> Triple(id, v.first, v.second) }
}

internal fun MainActivity.pickFolder(forDark: Boolean) = pickFolder({ pickWallpaper(forDark) }) { choice ->
    setWallpaper(forDark, choice)
    renderSettings(if (forDark) DARK_WP_ROW else LIGHT_WP_ROW)
}

/** Folders page for wallpapers and the screensaver; [onPick] gets "folder:<bucket id>:<name>". */
internal fun MainActivity.pickFolder(back: () -> Unit, onPick: (String) -> Unit) {
    if (checkSelfPermission(READ_PHOTOS) != PackageManager.PERMISSION_GRANTED) {
        afterPhotoAccess = { pickFolder(back, onPick) }
        return requestPermissions(arrayOf(READ_PHOTOS), MEDIA_REQUEST)
    }
    val folders = photoFolders()
    if (folders.isEmpty()) {
        return toast(R.string.toast_no_photos_found)
    }
    val rows = folders.map { (id, name, n) ->
        val countStr = if (n == 1) getString(R.string.photos_count_singular, n) else getString(R.string.photos_count_plural, n)
        page(name, countStr) { onPick("folder:$id:$name") }
    }
    showPage(getString(R.string.choose_folder_title), rows, back = back)
}

internal fun MainActivity.removeWallpaper(own: List<Pair<String, String>>, forDark: Boolean) {
    showPage(getString(R.string.remove_photo), own.map { (name, choice) ->
        page(name) {
            showPage(getString(R.string.delete_photo_confirm, name), listOf(
                page(getString(R.string.dialog_cancel)) { removeWallpaper(own, forDark) },
                page(getString(R.string.dialog_delete)) {
                    File(wallpaperDir(), choice.removePrefix("file:")).delete()
                    // A theme showing it falls back to its default.
                    prefs.edit().apply {
                        if (prefs.getString(K.WP_LIGHT, null) == choice) remove(K.WP_LIGHT)
                        if (prefs.getString(K.WP_DARK, null) == choice) remove(K.WP_DARK)
                    }.apply()
                    loadWallpaper()
                    pickWallpaper(forDark)
                },
            )) { removeWallpaper(own, forDark) }
        }
    }) { pickWallpaper(forDark) }
}

internal fun MainActivity.openStream(uri: String): InputStream =
    if (uri.startsWith("http")) {
        Http.open(uri)
    } else {
        contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("no stream for $uri")
    }

// ---------- shared view bits ----------

/** The wallpaper to show: the one chosen, else Spotlight once there's a TMDB key, else the bundled one for the theme. */
internal fun chooseWallpaper(saved: String?, hasTmdbKey: Boolean, forDark: Boolean) = saved
    ?: if (hasTmdbKey) SS_TMDB else bundledWallpaper(forDark)

internal fun bundledWallpaper(forDark: Boolean) = if (forDark) "res:Rockies (dusk)" else "res:Rockies (day)"

/** "external" covers every attached volume (USB drives too) from Android 10; before that only the main one. */
internal val PHOTOS: Uri by lazy { // lazy: unit tests load this file without Android
    if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
}

/** Photo ids in a "folder:<bucket id>:<name>" choice. */
internal fun folderPhotos(cr: ContentResolver, choice: String): List<Long> =
    cr.query(PHOTOS, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(choice.split(':')[1]), null)
        ?.use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } } ?: emptyList()
