package link.mccoy88f.zephyr

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.widget.Toast

// Hiding the device's IP from the sites add-ons, plugins and extensions read, and from the servers of the videos:
// Cloudflare WARP as a VPN for Zephyr TV alone (see Warp), or Cloudflare's 1.1.1.1 app for the whole device.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

/** Cloudflare's 1.1.1.1 app, with WARP for the whole device. */
private const val WARP_APP = "com.cloudflare.onedotonedotonedotone"

/** Whether the device's traffic goes through a VPN now (Zephyr TV's WARP, or another). */
internal fun MainActivity.vpnOn(): Boolean = runCatching {
    val cm = getSystemService(ConnectivityManager::class.java)
    cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
}.getOrDefault(false)

internal fun MainActivity.warpOn() = prefs.getBoolean(K.WARP, false)

/** What hiding the IP is about, WARP for Zephyr TV on or off, and the 1.1.1.1 app for the whole device. */
internal fun MainActivity.privacyPage(back: () -> Unit) {
    val intro = label(getString(R.string.warp_intro), 13f).apply {
        alpha = 0.7f
        maxLines = 14
        setLineSpacing(0f, 1.15f)
        setPadding(dp(14), 0, dp(14), dp(12))
    }
    showPage(getString(R.string.warp_title), listOf(
        page(getString(R.string.warp_switch), toggle(warpOn())) { if (warpOn()) warpOff() else warpAsk() },
        page(getString(R.string.warp_state), getString(if (vpnOn()) R.string.warp_vpn_on else R.string.state_off)) { privacyPage(back) },
        page(getString(R.string.warp_device)) { openWarpApp() },
    ), top = intro, back = back)
    warpBack = back
}

/** WARP on: Android's permission for the VPN first (once), then the tunnel (see warpUp). */
internal fun MainActivity.warpAsk() {
    val ask = try {
        VpnService.prepare(this)
    } catch (e: Exception) {
        return toast(R.string.warp_no_vpn)
    }
    if (ask == null) return warpUp(tell = true)
    try {
        @Suppress("DEPRECATION")
        startActivityForResult(ask, MainActivity.VPN_REQUEST)
    } catch (e: Exception) {
        toast(R.string.warp_no_vpn)
    }
}

/** Back from Android's VPN permission: on with it, nothing without. */
internal fun MainActivity.vpnAnswer(allowed: Boolean) {
    if (allowed) warpUp(tell = true)
    else toast(R.string.warp_not_allowed)
}

/**
 * The tunnel up, off the UI thread, with the account kept (or a new one, then kept). [tell]: the user turned it on,
 * so how it went is said and the page shown again; at start-up it's quiet, but for a failure.
 */
internal fun MainActivity.warpUp(tell: Boolean) {
    val saved = prefs.getString(K.WARP_ACCOUNT, null)
    Thread {
        val result = runCatching { Warp.up(applicationContext, saved) }
        ui.post {
            result.onSuccess { account ->
                prefs.edit().putBoolean(K.WARP, true).putString(K.WARP_ACCOUNT, account).apply()
                if (tell) toast(R.string.warp_on_toast, long = false)
            }.onFailure { e ->
                warn("WARP up: $e")
                if (saved != null && tell) prefs.edit().remove(K.WARP_ACCOUNT).apply() // a new account, next time
                toast(R.string.warp_failed)
            }
            if (tell) warpBack?.let { privacyPage(it) }
        }
    }.start()
}

internal fun MainActivity.warpOff() {
    prefs.edit().putBoolean(K.WARP, false).apply()
    Thread { Warp.down(applicationContext) }.start()
    warpBack?.let { privacyPage(it) }
}

/** At start-up: WARP up again when it was on (Android drops the VPN when the app stops), if still allowed. */
internal fun MainActivity.warpAgain() {
    if (!warpOn() || Warp.isUp(this)) return
    val allowed = runCatching { VpnService.prepare(this) == null }.getOrDefault(false)
    if (allowed) warpUp(tell = false) else prefs.edit().putBoolean(K.WARP, false).apply() // permission taken back
}

/** Cloudflare's 1.1.1.1 app, for WARP on the whole device: opened, or its Play Store page. */
private fun MainActivity.openWarpApp() {
    val launch = packageManager.getLeanbackLaunchIntentForPackage(WARP_APP) ?: packageManager.getLaunchIntentForPackage(WARP_APP)
    val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$WARP_APP"))
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://one.one.one.one/"))
    for (i in listOfNotNull(launch, store, web)) {
        if (runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}
