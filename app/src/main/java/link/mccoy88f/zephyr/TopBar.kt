package link.mccoy88f.zephyr

import android.widget.ImageView
import android.graphics.drawable.GradientDrawable
import link.mccoy88f.zephyr.MainActivity.Companion.SPRING
import link.mccoy88f.zephyr.MainActivity.Companion.DECELERATE
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings

// The top bar: the network dot, the voice assistant, and Android's own settings.

internal fun MainActivity.showNet(caps: NetworkCapabilities?) {
    val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    wifi.setImageResource(if (ethernet) R.drawable.ic_ethernet else R.drawable.ic_wifi)
    // Dim only the icon: the focus ring is the background and should stay full strength.
    wifi.imageAlpha = if (caps == null) 90 else 255
    // A red dot while there's no internet: no network, or one Android couldn't reach the internet through.
    val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    wifi.post {
        wifi.overlay.remove(offlineDot)
        if (online || wifi.width == 0) return@post
        val size = dp(9)
        offlineDot.setBounds(wifi.width - size - dp(3), dp(3), wifi.width - dp(3), dp(3) + size)
        wifi.overlay.add(offlineDot)
    }
}

/**
 * The TV's voice search (Google Assistant on Google TV), by the ways Android TVs offer it, most specific first.
 * Started directly rather than looked up first: Android 11+ hides other apps' activities from lookups.
 */
internal fun MainActivity.assistant() {
    for (intent in listOf(
        Intent("android.intent.action.VOICE_ASSIST"),
        Intent(Intent.ACTION_ASSIST),
        Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH),
        Intent(android.app.SearchManager.INTENT_ACTION_GLOBAL_SEARCH),
    )) {
        try {
            return startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }
    toast(R.string.toast_no_voice_search, long = false)
}

internal fun MainActivity.openSettings(action: String, data: Uri? = null) {
    try {
        startActivity(Intent(action, data))
    } catch (e: Exception) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

// ---------- apps row ----------

/** The top bar's buttons: users, search, network, All apps, TV inputs, Android settings, launcher settings. Round, lit on focus. */
internal fun MainActivity.setupTopBar() {
    for ((id, action) in listOf<Pair<Int, () -> Unit>>(
        R.id.usersButton to { showUsers() },
        R.id.searchButton to { openSearch(if (searchQuery != null) lastSearchWord else "") },
        R.id.wifi to { openSettings(Settings.ACTION_WIFI_SETTINGS) },
        R.id.allAppsButton to { openAllApps() },
        R.id.tvInputsButton to { openInputsMenu(findViewById(R.id.tvInputsButton)) },
        R.id.systemSettings to { openSettings(Settings.ACTION_SETTINGS) },
        R.id.launcherSettings to { unlock { openLauncherSettings() } },
    )) findViewById<ImageView>(id).apply {
        setColorFilter(fg)
        val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        background = ring
        setOnFocusChangeListener { v, focused ->
            if (focused) lastTop = v
            ring.setColor(if (focused) fg else 0)
            setColorFilter(if (focused) onFg else fg)
            animate().scaleX(if (focused) 1.15f else 1f).scaleY(if (focused) 1.15f else 1f)
                .setDuration(if (focused) FOCUS_IN_MS else FOCUS_OUT_MS)
                .setInterpolator(if (focused) SPRING else DECELERATE).start()
        }
        setOnClickListener { action() }
    }
}
