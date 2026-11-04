package link.mccoy88f.zephyr

import android.view.View
import android.widget.GridLayout

// All apps: every app in a grid over the home screen.

internal fun MainActivity.openAllApps() {
    allAppsGrid.removeAllViews()
    val gap = dp(10)
    val width = (resources.displayMetrics.widthPixels - dp(96) - gap * 5) / 6
    for (a in sortedApps()) {
        val t = appTile(a) // holding OK here pins it to the row (see onHold)
        allAppsGrid.addView(t, GridLayout.LayoutParams().apply {
            this.width = width
            height = dp(76)
            setMargins(0, 0, gap, gap)
        })
    }
    allApps.visibility = View.VISIBLE
    homeFocusable(false)
    allAppsGrid.getChildAt(0)?.requestFocus()
}

internal fun MainActivity.closeAllApps() {
    allApps.visibility = View.GONE
    homeFocusable(true)
    renderApps()
    findViewById<View>(R.id.allAppsButton).requestFocus() // back where it was opened from
}

// ---------- screensaver ----------
// Gallery on: remember whatever screensaver was set (Aerial Views here) and switch to ours.
// Gallery off: put that one back. Needs the adb-granted WRITE_SECURE_SETTINGS, like the Home service.
