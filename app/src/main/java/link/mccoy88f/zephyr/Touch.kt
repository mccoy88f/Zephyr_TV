package link.mccoy88f.zephyr

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import link.mccoy88f.zephyr.MainActivity.Companion.DECELERATE
import kotlin.math.abs

// Phones and tablets: the home screen with a finger. The rows already scroll sideways (they're scroll views) and
// tiles and cards open with a tap; this file adds what a remote's focus does on a TV: the page up and down, and
// Spotlight's titles with a swipe. Functions of MainActivity, kept here by topic; their state lives in MainActivity.

/** A finger on the home screen, from its press: where, and what it turned out to be. */
internal class HomeDrag {
    var x = 0f
    var y = 0f
    var from = 0f // the page's shift when it went down
    var mode = Mode.UNDECIDED
    var onSpotlight = false
    var velocity: VelocityTracker? = null
    var hanging: View? = null // the rows hanging below the page that the finger is on (see hanging)

    enum class Mode { UNDECIDED, PAGE, PULL, SPOTLIGHT, VIEWS }
}

/**
 * Takes the vertical drags on the home screen (the page under the apps scrolls, as focus going down slides it on a TV,
 * see keepInView) and the sideways swipes on Spotlight (the title before or after). Everything else, a tap, a row
 * scrolled sideways, goes on to the views. True when the event was taken.
 */
internal fun MainActivity.homeDrag(e: MotionEvent): Boolean {
    if (settingsScrim.visibility == View.VISIBLE || allApps.visibility == View.VISIBLE || editing) return false
    val d = drag
    val content = findViewById<View>(R.id.content)
    when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            d.x = e.rawX
            d.y = e.rawY
            d.mode = HomeDrag.Mode.UNDECIDED
            content.animate().cancel()
            d.from = -content.translationY
            // Spotlight: its title and the picture round it, all the screen above the apps.
            d.onSpotlight = spotlightContainer.visibility == View.VISIBLE &&
                e.rawY < IntArray(2).also { bottomSection.getLocationOnScreen(it) }[1]
            d.velocity?.recycle()
            d.velocity = VelocityTracker.obtain().apply { addMovement(e) }
            return false
        }
        MotionEvent.ACTION_MOVE -> {
            d.velocity?.addMovement(e)
            val dx = e.rawX - d.x
            val dy = e.rawY - d.y
            if (d.mode == HomeDrag.Mode.UNDECIDED) {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                d.mode = when {
                    // Down with the page at its top: the rows of the app selected are read again (pull to refresh).
                    dy > slop && dy > abs(dx) && content.translationY == 0f -> HomeDrag.Mode.PULL
                    // The page moves while it has room, or when it's still down from rows no longer there.
                    abs(dy) > slop && abs(dy) > abs(dx) -> if (pageRoom() > 0 || content.translationY != 0f) HomeDrag.Mode.PAGE else HomeDrag.Mode.VIEWS
                    abs(dx) > slop && d.onSpotlight -> HomeDrag.Mode.SPOTLIGHT
                    abs(dx) > slop -> HomeDrag.Mode.VIEWS
                    else -> return false
                }
                // Ours from here: the views under the finger let go of it (no tap, no press shown).
                if (d.mode != HomeDrag.Mode.VIEWS) {
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    window.superDispatchTouchEvent(cancel)
                    d.hanging?.let { send(it, cancel) }
                    d.hanging = null
                    cancel.recycle()
                }
            }
            return when (d.mode) {
                HomeDrag.Mode.PAGE -> {
                    content.translationY = -(d.from - dy).coerceIn(0f, pageRoom())
                    cardsOnScreen()
                    veilFromScroll() // the veil behind the rows, as the page goes up
                    true
                }
                HomeDrag.Mode.PULL -> {
                    content.translationY = (dy * 0.35f).coerceIn(0f, dp(64).toFloat()) // the page follows, a little
                    true
                }
                HomeDrag.Mode.SPOTLIGHT -> {
                    spotlightContainer.translationX = dx * 0.6f // the title follows the finger
                    true
                }
                else -> false
            }
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            val mode = d.mode
            d.mode = HomeDrag.Mode.UNDECIDED
            val v = d.velocity?.apply { addMovement(e); computeCurrentVelocity(1000) }
            val vy = v?.yVelocity ?: 0f
            d.velocity?.recycle()
            d.velocity = null
            if (mode == HomeDrag.Mode.PULL) {
                val far = e.actionMasked == MotionEvent.ACTION_UP && e.rawY - d.y > dp(120)
                content.animate().translationY(0f).setDuration(200).setInterpolator(DECELERATE)
                    .setUpdateListener { veilFromScroll() }.withEndAction { content.animate().setUpdateListener(null) }.start()
                if (far) refreshRows()
            }
            if (mode == HomeDrag.Mode.SPOTLIGHT) {
                val dx = e.rawX - d.x
                // Far enough: the title after (finger to the left) or before, coming in from that side; else back.
                if (e.actionMasked == MotionEvent.ACTION_UP && abs(dx) > dp(48)) {
                    cycleSpotlight(if (dx < 0) 1 else -1)
                    spotlightContainer.translationX = if (dx < 0) dp(40).toFloat() else -dp(40).toFloat()
                }
                spotlightContainer.animate().translationX(0f).setDuration(200).setInterpolator(DECELERATE).start()
            }
            if (mode == HomeDrag.Mode.PAGE && abs(vy) > ViewConfiguration.get(this).scaledMinimumFlingVelocity) {
                // A flick goes on a little past the finger, as a list does.
                val to = (-content.translationY - vy * 0.25f).coerceIn(0f, pageRoom())
                content.animate().translationY(-to).setDuration(280).setInterpolator(DECELERATE)
                    .setUpdateListener { cardsOnScreen(); veilFromScroll() }
                    .withEndAction { content.animate().setUpdateListener(null) }.start()
            }
            return mode == HomeDrag.Mode.PAGE || mode == HomeDrag.Mode.PULL || mode == HomeDrag.Mode.SPOTLIGHT
        }
    }
    return d.mode == HomeDrag.Mode.PAGE || d.mode == HomeDrag.Mode.PULL || d.mode == HomeDrag.Mode.SPOTLIGHT
}

/**
 * The sections after the first hang below the page, past its bottom edge (see BottomSection): scrolled up into view,
 * they're still outside the page's bounds, where Android hands no touch on. A finger that goes down there is handed
 * to them here, its taps and sideways scrolls with it. True when it was.
 */
internal fun MainActivity.hanging(e: MotionEvent): Boolean {
    val d = drag
    if (e.actionMasked == MotionEvent.ACTION_DOWN) {
        d.hanging = null
        if (settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE && !editing &&
            !hits(findViewById(R.id.content), e) && hits(bottomSection, e)) d.hanging = bottomSection
    }
    val to = d.hanging ?: return false
    send(to, e)
    if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) d.hanging = null
    return true
}

/** [e] to [v], in its own coordinates. */
private fun send(v: View, e: MotionEvent) {
    val at = IntArray(2).also { v.getLocationOnScreen(it) }
    val local = MotionEvent.obtain(e).apply { setLocation(e.rawX - at[0], e.rawY - at[1]) }
    v.dispatchTouchEvent(local)
    local.recycle()
}

private fun hits(v: View, e: MotionEvent): Boolean {
    val at = IntArray(2).also { v.getLocationOnScreen(it) }
    return e.rawX >= at[0] && e.rawX < at[0] + v.width && e.rawY >= at[1] && e.rawY < at[1] + v.height
}

/** How far the page can go up: until the last row's bottom is on screen, as keepInView does for its focus. */
internal fun MainActivity.pageRoom(): Float {
    val content = findViewById<View>(R.id.content)
    val last = moreSections.takeIf { it.visibility == View.VISIBLE && it.childCount > 0 }?.let { it.getChildAt(it.childCount - 1) }
        ?: watchScroll.takeIf { it.visibility == View.VISIBLE } ?: return 0f
    val at = IntArray(2).also { last.getLocationInWindow(it) }
    val bottom = at[1] - content.translationY - bottomSection.translationY + last.height
    return (bottom - (findViewById<View>(R.id.root).height - dp(16))).coerceAtLeast(0f)
}

/**
 * The rows scrolled onto the screen get their cards: on a TV the rows below get theirs as focus comes near (see
 * watchCard), and a finger moves no focus.
 */
internal fun MainActivity.cardsOnScreen() {
    val bottom = findViewById<View>(R.id.root).height
    for (row in cardRows()) {
        if ((row.getTag(R.id.watchScroll) as? RowCards)?.built != 0) continue
        val at = IntArray(2).also { (row.parent as? View ?: row).getLocationInWindow(it) }
        if (at[1] < bottom + dp(300)) moreCards(row)
    }
}

/** A row scrolled sideways with a finger near the end of its cards: the next page, as focus there does. */
internal fun MainActivity.cardsAlong(scroll: ViewGroup, x: Int) {
    val row = scroll.getChildAt(0) as? ViewGroup ?: return
    if (row.getTag(R.id.watchScroll) !is RowCards) return
    if (x + scroll.width * 2 >= row.width) moreCards(row)
}

/**
 * The page back up as far as its rows still reach, once they change under a finger: an app with fewer rows (or none)
 * selected after scrolling down another's. On a TV focus does it (see keepInView). Set up once, from onCreate.
 */
internal fun MainActivity.touchSetup() {
    val content = findViewById<View>(R.id.content)
    bottomSection.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (!bottomSection.isInTouchMode || drag.mode == HomeDrag.Mode.PAGE) return@addOnLayoutChangeListener
        content.post {
            val room = pageRoom()
            if (-content.translationY > room) content.animate().translationY(-room).setDuration(200).setInterpolator(DECELERATE).start()
        }
    }
    // Spotlight's title: a tap shows its buttons (focus, as on a TV), the next shows all of what it's about. It only
    // takes focus once tapped: focusable with a finger from the start, Android would put the first focus there, not
    // on the Play queue.
    spotlightInfoBox.isClickable = true
    spotlightInfoBox.setOnClickListener {
        if (spotlightInfoBox.isFocused) toggleOverview()
        else {
            spotlightInfoBox.isFocusableInTouchMode = true
            spotlightInfoBox.requestFocus()
        }
    }
}

/** Pulled down: the rows under the app selected read again (the Play queue's when none is). */
internal fun MainActivity.refreshRows() {
    android.widget.Toast.makeText(this, getString(R.string.rows_refreshing), android.widget.Toast.LENGTH_SHORT).show()
    watchKey = null
    val app = tmdbLastFocusApp
    if (app == null) refreshWatchNext() else rowsAgain(app)
}
