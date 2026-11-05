package link.mccoy88f.zephyr

import android.content.SharedPreferences
import java.util.Calendar
import android.graphics.Color
import android.provider.MediaStore
import android.view.View
import android.widget.TextView

// The theme: light, dark or glass, and when Auto turns dark (see prefersDark).

internal fun MainActivity.wantDark() = prefersDark(prefs)

internal fun MainActivity.applyTheme() {
    dark = wantDark()
    glass = prefs.getString(K.THEME, "light") == "glass"
    fg = if (glass) Color.WHITE else if (dark) 0xFFF5F5F7.toInt() else 0xFF111214.toInt()
    // Small controls (top-bar icons, settings and menu rows, Spotlight buttons) show focus by inverting:
    // an fg fill with onFg content. A white-on-near-white or grey-on-black highlight barely reads from the sofa.
    onFg = if (dark) 0xFF111214.toInt() else Color.WHITE
    field = if (dark) 0xFF2A2A32.toInt() else 0xFFE5E7EB.toInt()
    // Dark cards are matte black glass on a true-black ground: lit faintly from the top and fading to
    // near-black, no outline (outlines read as a grey grid). Focus brightens the glass, never to grey.
    // Light cards are frosted grey glass, so they still read on white wallpapers; focus turns them white.
    // Glass cards sample a blurred copy of the photograph (see Scene). The smoked tint is the fallback when
    // there is no photo. Focus lightens the glass; the rim and the sheen (see style) give the gloss.
    cardRest = when {
        glass -> intArrayOf(0x8C2C2C34.toInt(), 0xA6121216.toInt())
        dark -> intArrayOf(0xFF16161B.toInt(), 0xFF060607.toInt())
        else -> intArrayOf(0xD9E2E5E9.toInt(), 0xD9D8DCE1.toInt())
    }
    cardLit = when {
        glass -> intArrayOf(0xCC5A5A66.toInt(), 0xCC24242C.toInt())
        dark -> intArrayOf(0xFF26262E.toInt(), 0xFF0C0C0F.toInt())
        else -> intArrayOf(Color.WHITE, Color.WHITE)
    }
    // Must match the colour each wallpaper fades into, or the bottom edge shows a seam.
    ground = if (dark) Color.BLACK else 0xFFF3F5F7.toInt()
    findViewById<View>(R.id.root).setBackgroundColor(ground)
    allApps.setBackgroundColor(ground)
    // A halo in the ground colour: dark text gets a light one, light text a dark one, so either stays readable
    // where it crosses a photo or a TMDB backdrop.
    // Glass has white text straight on the photo, with no fade to a plain colour behind it: a stronger halo.
    val textShadow = if (glass) 0xB3000000.toInt() else if (dark) 0x80000000.toInt() else 0xB3FFFFFF.toInt()
    val textRadius = if (glass) 8f else if (dark) 6f else 5f
    for (id in intArrayOf(R.id.clock, R.id.date, R.id.appsTitle, R.id.watchTitle, R.id.allAppsTitle, R.id.rowLabel, R.id.weather, R.id.spotlightTitle, R.id.spotlightMeta, R.id.spotlightOverview)) {
        findViewById<TextView>(id)?.apply {
            setTextColor(fg)
            setShadowLayer(textRadius, 0f, if (dark) 2f else 0f, textShadow)
        }
    }
    // The top bar's icons get the same halo round their shapes (see HaloImageView).
    for (id in intArrayOf(R.id.usersButton, R.id.searchButton, R.id.downloadsButton, R.id.wifi, R.id.allAppsButton, R.id.tvInputsButton, R.id.systemSettings, R.id.launcherSettings))
        (findViewById<View>(id) as? HaloImageView)?.halo(textShadow, dp(if (glass) 4 else 3).toFloat(), if (dark) dp(1).toFloat() else 0f)
    wifi.setColorFilter(fg)
    spotlightPrev.setColorFilter(fg)
    spotlightNext.setColorFilter(fg)
    renderSpotlight()
}

// ---------- wallpapers ----------
// Bundled ones have their fade baked in. Your own go in Android/data/link.mccoy88f.zephyr/files/wallpapers/
// (adb push, no permission needed) and get the fade painted once when loaded. A folder on the TV (MediaStore,
// so USB drives too) shows its photos in shuffled turns, one decoded at a time.
// Each theme remembers its own pick. Decoded off the UI thread, max 1080p (~8 MB). Full colour, not RGB_565:
// 565 rounds a smooth sky's 1-2 level JPEG ripples into visible grey and green blocks.

/** Light/dark from the launcher's theme setting; shared with the Gallery screensaver. Glass is a dark theme. */
internal fun prefersDark(prefs: SharedPreferences) = when (prefs.getString(K.THEME, "light")) {
    "dark", "glass" -> true
    "auto" -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY) !in 7..18
    else -> false
}
