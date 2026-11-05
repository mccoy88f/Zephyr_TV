package link.mccoy88f.zephyr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV's launcher has to stay enabled (disabling it boot-loops the TV), so we open our home when
 * Google TV's home screens appear. Key filtering follows [K.SMOOTH_VOLUME] (default off = filter on for
 * instant Home). On many TVs filtering makes the remote lag; Smooth volume turns the filter off and
 * Home is caught by the window watcher instead (Google TV may flash briefly).
 */
class HomeOverride : AccessibilityService() {
    private val adultRelock = AdultRelock()

    override fun onServiceConnected() {
        running = this
        applyKeyFilterFromPrefs()
        // It runs all the time, the home screen not: standby locks 18+ again while an app is in front too.
        registerReceiver(adultRelock, AdultRelock.FILTER)
    }

    /**
     * Reads [K.SMOOTH_VOLUME]: when on, turns key filtering off so the remote stays usable; Home is then
     * caught by the window watcher (Google TV may flash ~1 s). Default off keeps filtering for instant Home.
     */
    fun applyKeyFilterFromPrefs() {
        val smooth = getSharedPreferences(K.FILE, MODE_PRIVATE).getBoolean(K.SMOOTH_VOLUME, false)
        applyKeyFilter(filter = !smooth)
    }

    /**
     * When [filter] is true, Home is taken in [onKeyEvent] before the system sees it (no Google TV flash).
     * Off: keys go straight to apps; Home is covered when Google TV shows.
     */
    fun applyKeyFilter(filter: Boolean) {
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info.apply { flags = if (filter) flags or flag else flags and flag.inv() }
    }

    /**
     * Unbind also runs on force-stop and on every APK update — not only when the user turns the service off.
     * Do not set [K.HOME_OFF] here: that flag is only written from [turnOffHomeButton], so [ensureHomeService]
     * can put the service back after an install. Otherwise Home stays broken until Accessibility is toggled by hand.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        running = null
        runCatching { unregisterReceiver(adultRelock) }
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME) return false
        // Decided on release, by how long it was held: this TV's remote doesn't reliably send key repeats.
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) {
            if (e.eventTime - e.downTime < LONG_PRESS_MS) goHome(cover = false) else performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
        return true
    }

    /** Back for the phone's remote when another app is in front. */
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.packageName == GOOGLE_TV &&
            e.className?.contains("dashboard", ignoreCase = true) != true
        ) goHome(cover = true)
    }

    // Marked as Home so the setup check can tell it from Zephyr TV being opened from the apps list. [cover]: Google TV
    // showed up by itself (Back out of an app), not the Home key: the home screen stays where it was (see onNewIntent).
    private fun goHome(cover: Boolean) = startActivity(Intent(this, MainActivity::class.java).addCategory(Intent.CATEGORY_HOME)
        .putExtra(COVER, cover).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override fun onInterrupt() {}

    companion object {
        /** The bound service, so Zephyr TV's switch can turn it off without any permission. */
        var running: HomeOverride? = null
            private set
        private const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
        private const val LONG_PRESS_MS = 500L
        const val COVER = "link.mccoy88f.zephyr.COVER"
    }
}
