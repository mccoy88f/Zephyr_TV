package link.mccoy88f.zephyr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.IOException
import link.mccoy88f.zephyr.MainActivity.App
import link.mccoy88f.zephyr.MainActivity.Companion.ACCENTS
import link.mccoy88f.zephyr.MainActivity.Companion.BRAND_COLOURS
import link.mccoy88f.zephyr.MainActivity.Companion.GLYPHS
import link.mccoy88f.zephyr.MainActivity.Companion.GREYSCALE
import link.mccoy88f.zephyr.MainActivity.Companion.HDMI
import link.mccoy88f.zephyr.MainActivity.Companion.LOGOS

// The apps' tiles: brand marks, a mono mark made from the icon (glyph), its colour (accent), and the user's own icons.

/** The home row shows marks only by default (see rowNames); the All apps grid always labels them. */
internal fun MainActivity.appTile(a: App, labeled: Boolean = true): View {
    val logo = LOGOS[a.pkg]
    val custom = customIcon(a.pkg)
    val name = a.label.takeIf { labeled }
    // Colour mode keeps the same marks, tinted with the brand colour (or the colour the app's icon is mostly).
    val colour = colourIcons()
    val t = when {
        custom != null -> tile(name, custom, false, a.pkg) { launch(a) }
        a.picture != null -> {
            val pic = if (colour) a.picture.constantState?.newDrawable(resources) ?: a.picture
                      else (a.picture.constantState?.newDrawable(resources) ?: a.picture).mutate().apply { colorFilter = GREYSCALE }
            tile(null, pic, false, a.pkg, padding = dp(4)) {
                if (allApps.visibility == View.VISIBLE) {
                    closeAllApps()
                    appRow.findViewWithTag<View>(a.pkg)?.requestFocus()
                } else if (!playLastChannel(a.pkg) && watchApp == a.pkg) firstCard()?.requestFocus()
            }
        }
        // An add-on's tile (see addonApps): OK goes down to its channels; from All apps, to its tile in the row.
        a.pkg.startsWith(STREMIO) -> {
            val icon = if (colour || a.icon !is android.graphics.drawable.BitmapDrawable) a.icon
                       else (a.icon.constantState?.newDrawable(resources) ?: a.icon).mutate().apply { colorFilter = GREYSCALE }
            tile(name, icon, a.icon !is android.graphics.drawable.BitmapDrawable, a.pkg) {
                if (allApps.visibility == View.VISIBLE) {
                    closeAllApps()
                    appRow.findViewWithTag<View>(a.pkg)?.requestFocus()
                } else if (!playLastChannel(a.pkg) && watchApp == a.pkg) firstCard()?.requestFocus()
            }
        }
        a.pkg.startsWith(HDMI) -> {
            val tileLabel = if (labeled) a.label else a.sublabel
            tile(tileLabel, a.icon, true, a.pkg,
                tint = BRAND_COLOURS[hdmiIcon(a.label, true)]?.takeIf { colour }) { launch(a) }
        }
        logo != null -> tile(name.takeIf { !logo.second }, getDrawable(logo.first)!!, true, a.pkg,
            wordmarkInset = if (!logo.second) null else dp(if (labeled) 28 else 14),
            tint = BRAND_COLOURS[a.pkg]?.takeIf { colour }) { launch(a) }
        else -> cachedGlyph(a)?.let { tile(name, it, true, a.pkg, tint = if (colour) cachedAccent(a) else null) { launch(a) } }
            ?: if (colour) tile(name, a.icon.constantState?.newDrawable(resources) ?: a.icon, false, a.pkg) { launch(a) }
            // A copy: greying the shared icon itself would keep it grey after switching to colour.
            else tile(name, (a.icon.constantState?.newDrawable(resources) ?: a.icon).mutate().apply { colorFilter = GREYSCALE }, false, a.pkg) { launch(a) }
    }
    t.contentDescription = a.label
    return t
}

internal fun MainActivity.tile(label: String?, icon: Drawable, mono: Boolean, tag: String, wordmarkInset: Int? = null, tint: Int? = null, padding: Int? = null, onClick: () -> Unit): View {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }
    val img = ImageView(this).apply {
        setImageDrawable(icon.mutate())
        scaleType = ImageView.ScaleType.FIT_CENTER
        if (mono) setColorFilter(tint?.let { readable(it) } ?: fg)
    }
    val text = label?.let {
        label(it, 13f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), 0)
        }
    }
    // Same card as light mode; in dark the marks rest slightly dimmed and brighten with focus.
    val restLogo = if (dark && !glass) 0.8f else 1f
    val restText = if (glass) 0.85f else if (dark) 0.65f else 1f
    img.alpha = restLogo
    text?.alpha = restText
    style(col, dp(8), card = true, frost = glass) { focused ->
        if (!dark) return@style
        img.animate().alpha(if (focused) 1f else restLogo).setDuration(160).start()
        text?.animate()?.alpha(if (focused) 1f else restText)?.setDuration(160)?.start()
    }
    if (text == null) {
        // Icons share one height. Wordmarks sit in a square viewport, so fit them by width instead
        // (crop the empty top and bottom) or they shrink to a sliver.
        if (wordmarkInset != null) {
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.setPadding(wordmarkInset, 0, wordmarkInset, 0)
        } else if (padding != null) {
            img.setPadding(padding, padding, padding, padding)
        } else {
            img.setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        col.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    } else {
        col.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, dp(26)))
        col.addView(text, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    col.tag = tag
    col.setOnClickListener { onClick() }
    return col
}

/**
 * A mono mark from an adaptive icon's foreground layer (its symbol, without the coloured backplate),
 * cropped to the safe zone. Brightness becomes opacity: the light symbol becomes the mark, and dark
 * details in it (a note inside a circle) become holes, so the mono tint keeps them. Null for old
 * single-layer icons and for photo-like layers that fill the square, which keep the greyscale look.
 */
internal fun MainActivity.glyph(icon: Drawable): Bitmap? {
    if (Build.VERSION.SDK_INT < 26) return null // Android 7 has no adaptive icons: the greyscale look
    val fg = (icon as? AdaptiveIconDrawable)?.foreground ?: return null
    val size = dp(96) // large, so a small symbol is still sharp after trimming and scaling up
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    // Layers are 108dp with the symbol inside the middle 72dp; drawing at 1.5x crops to that.
    fg.setBounds(-size / 4, -size / 4, size * 5 / 4, size * 5 / 4)
    fg.draw(Canvas(bmp))
    val px = IntArray(size * size)
    bmp.getPixels(px, 0, size, 0, 0, size, size)
    if (px.count { Color.alpha(it) > 200 } > px.size * 0.7) return null // a picture, not a symbol
    val alpha = FloatArray(px.size) { i ->
        val c = px[i]
        Color.alpha(c) * (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
    }
    val peak = alpha.max().takeIf { it > 0f } ?: return null
    // Stretch so the brightest part is fully opaque: an orange symbol shouldn't look dimmer than a white one.
    for (i in px.indices) px[i] = Color.argb((alpha[i] * 255f / peak).toInt().coerceAtMost(255), 255, 255, 255)
    bmp.setPixels(px, 0, size, 0, 0, size, size)
    // Trim to the visible mark (as a square, centred), so small symbols fill the tile like the logos do.
    val on = px.indices.filter { Color.alpha(px[it]) > 40 }.ifEmpty { return null }
    val l = on.minOf { it % size }; val r = on.maxOf { it % size }
    val t = on.minOf { it / size }; val b = on.maxOf { it / size }
    val side = maxOf(r - l, b - t) + 1
    val x = ((l + r + 1 - side) / 2).coerceIn(0, size - side)
    val y = ((t + b + 1 - side) / 2).coerceIn(0, size - side)
    return Bitmap.createBitmap(bmp, x, y, side, side)
}

/**
 * A brand colour nudged towards the theme's foreground until it stands out from the card at 3:1 (the bar for
 * icons): Spotify green or VLC orange on the light theme's pale grey cards are only about 2:1 as they come.
 */
internal fun MainActivity.readable(c: Int): Int {
    val card = if (dark) 0xFF0E0E11.toInt() else 0xFFE0E3E7.toInt() // the rest card, middle of its gradient
    fun contrast(a: Int): Float {
        val (hi, lo) = Color.luminance(a).let { la -> Color.luminance(card).let { lc -> maxOf(la, lc) to minOf(la, lc) } }
        return (hi + 0.05f) / (lo + 0.05f)
    }
    var out = c
    var mix = 0f
    while (contrast(out) < 3f && mix < 1f) {
        mix += 0.1f
        out = Color.rgb(
            (Color.red(c) * (1 - mix) + Color.red(fg) * mix).toInt(),
            (Color.green(c) * (1 - mix) + Color.green(fg) * mix).toInt(),
            (Color.blue(c) * (1 - mix) + Color.blue(fg) * mix).toInt())
    }
    return out
}

/** glyph() walks every pixel, too slow to redo on each row rebuild; kept per app until the app changes. */
internal fun MainActivity.cachedGlyph(a: App): Drawable? {
    val bmp = if (a.pkg in GLYPHS) GLYPHS[a.pkg] else glyph(a.icon).also { GLYPHS[a.pkg] = it }
    return bmp?.let { BitmapDrawable(resources, it) }
}

/**
 * The colour an icon is mostly, for tinting its mark: the biggest hue among its vivid pixels (backplate
 * included, which is where most apps keep their colour). Null if it's all greys, or too close to the
 * card colour to read on it; the mark then stays the theme's mono colour.
 */
internal fun MainActivity.accent(icon: Drawable): Int? {
    val size = 32
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    icon.constantState?.newDrawable(resources)?.apply { setBounds(0, 0, size, size) }?.draw(Canvas(bmp)) ?: return null
    val px = IntArray(size * size).also { bmp.getPixels(it, 0, size, 0, 0, size, size) }
    bmp.recycle()
    val hsv = FloatArray(3)
    val buckets = px.filter { Color.alpha(it) > 200 }
        .filter { Color.colorToHSV(it, hsv); hsv[1] > 0.35f && hsv[2] > 0.3f }
        .groupBy { Color.colorToHSV(it, hsv); (hsv[0] / 30).toInt() } // 12 hue buckets
    val top = buckets.values.maxByOrNull { it.size }?.takeIf { it.size > px.size / 20 } ?: return null
    return Color.rgb(top.sumOf { Color.red(it) } / top.size, top.sumOf { Color.green(it) } / top.size, top.sumOf { Color.blue(it) } / top.size)
}

/** accent() per app, kept like GLYPHS; the contrast check is per theme, so it runs on every use. */
internal fun MainActivity.cachedAccent(a: App): Int? {
    val c = if (a.pkg in ACCENTS) ACCENTS[a.pkg] else accent(a.icon).also { ACCENTS[a.pkg] = it }
    val lum = c?.let { Color.luminance(it) } ?: return null
    return c.takeIf { if (dark) lum > 0.08f else lum < 0.6f }
}

/** Optional per-theme icons: assets/icons/<package>_dark.png, <package>_light.png, or <package>.png. */
internal fun MainActivity.customIcon(pkg: String): Drawable? =
    listOf("icons/${pkg}_${if (dark) "dark" else "light"}.png", "icons/$pkg.png").firstNotNullOfOrNull { path ->
        try {
            assets.open(path).use { Drawable.createFromStream(it, null) }
        } catch (e: IOException) {
            null
        }
    }

// ---------- holding OK, and editing the apps row ----------
