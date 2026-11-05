package link.mccoy88f.zephyr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV's launcher has to stay enabled (disabling it boot-loops the TV), so we take the Home key before
 * the system sees it and open our home directly; Google TV never flashes up. Holding Home still opens the
 * Google TV dashboard. Covering Google TV whenever it shows stays as the fallback for anything else that
 * opens it (e.g. Back out of the last app).
 *
 * Taking Home means filtering every key: Android hands each one to [onKeyEvent] and waits for the answer, on
 * Zephyr TV's main thread. Where that makes the remote lag, Direct remote keys ([K.DIRECT_KEYS]) turns the
 * filter off: keys go straight to the system, and Home is caught by covering Google TV (it shows for a moment).
 */
class HomeOverride : AccessibilityService() {
    private val adultRelock = AdultRelock()

    override fun onServiceConnected() {
        running = this
        applyKeyFilter()
        // It runs all the time, the home screen not: standby locks 18+ again while an app is in front too.
        registerReceiver(adultRelock, AdultRelock.FILTER)
    }

    /** Filters the keys (Home taken at once) unless Direct remote keys is on. The XML starts unfiltered. */
    fun applyKeyFilter() {
        val direct = getSharedPreferences(K.FILE, MODE_PRIVATE).getBoolean(K.DIRECT_KEYS, false)
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info.apply { flags = if (direct) flags and flag.inv() else flags or flag }
    }

    /**
     * Turned off from Android's Accessibility settings: the app mustn't turn it back on (see ensureHomeService).
     * Unbinding also comes with a force-stop or an update, though, and those kill the process right after: the
     * choice is written a moment later, so only an unbind the process outlives counts as the user's.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        running = null
        runCatching { unregisterReceiver(adultRelock) }
        val app = applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (running == null) app.getSharedPreferences(K.FILE, MODE_PRIVATE).edit().putBoolean(K.HOME_OFF, true).apply()
        }, UNBIND_GRACE_MS)
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
        private const val UNBIND_GRACE_MS = 3000L // a force-stop or an update has killed the process by then
        const val COVER = "link.mccoy88f.zephyr.COVER"
    }
}
