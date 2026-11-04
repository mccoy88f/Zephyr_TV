package link.mccoy88f.zephyr

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import link.mccoy88f.zephyr.MainActivity.Companion.DECELERATE
import link.mccoy88f.zephyr.MainActivity.Companion.SPRING

// Views every screen of the home screen builds with: text in the theme, and sizes in dp.

/**
 * tvOS-style focus, no outline: the focused card lifts (scale + shadow). Light mode adds a soft
 * top sheen; dark mode brightens the glass gradient instead (a white sheen would haze it grey).
 * Only transform and colour change, so it stays on the GPU and costs no re-layout. Glass app tiles ([frost])
 * sample the blurred photograph and, once, a band of light crosses them.
 */
internal fun MainActivity.style(v: View, corner: Int, card: Boolean, lift: Float = 1.08f, frost: Boolean = false, onFocus: ((Boolean) -> Unit)? = null) {
    val radius = if (glass && card) corner * 2 else corner // glass panes are rounder
    val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40FFFFFF, 0x00FFFFFF)).apply {
        cornerRadius = radius.toFloat()
        alpha = 0
    }
    val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (card) cardRest else intArrayOf(0, 0)).apply {
        cornerRadius = radius.toFloat()
    }
    // Glass: a gloss on the top half of the card and a thin bright rim, brighter with focus, like light caught
    // on the edge of a glass pane. Over the content, so a poster gets the rim too.
    val rim = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setStroke(1, 0x1FFFFFFF) // a hairline: a bright outline marks the panes off more than the glass itself
    }
    val gloss = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x24FFFFFF, 0x08FFFFFF, 0x00FFFFFF)).apply {
        cornerRadius = radius.toFloat()
    }
    val slice = if (frost && card) GlassSlice(this, radius.toFloat()) else null
    val glint = if (slice != null) GlintDrawable(radius.toFloat()) else null
    v.background = when {
        slice != null -> LayerDrawable(arrayOf(slice, gloss))
        glass && card -> LayerDrawable(arrayOf(bg, gloss))
        else -> bg
    }
    v.foreground = when {
        glint != null -> LayerDrawable(arrayOf(rim, glint))
        glass && card -> rim
        !dark -> sheen
        else -> null
    }
    v.clipToOutline = true
    // Android 9+ only (8.x would crash on these); black is the default shadow colour before that anyway.
    if (Build.VERSION.SDK_INT >= 28) {
        v.outlineSpotShadowColor = Color.BLACK
        v.outlineAmbientShadowColor = Color.BLACK
    }
    v.elevation = if (card) dp(2).toFloat() else 0f
    v.isFocusable = true
    v.isClickable = true
    v.setOnLongClickListener { if (!holdFired) fireHold(v); true }
    v.setOnFocusChangeListener { _, focused ->
        sheen.alpha = if (focused) 255 else 0
        rim.setStroke(if (focused) dp(1) else 1, if (focused) 0x99FFFFFF.toInt() else 0x1FFFFFFF)
        if (slice != null) slice.lit = focused else if (card) bg.colors = if (focused) cardLit else cardRest
        if (focused) glint?.play() else glint?.cancel()
        v.animate()
            .scaleX(if (focused) lift else 1f).scaleY(if (focused) lift else 1f)
            .translationZ(if (focused) dp(14).toFloat() else 0f)
            .setDuration(if (focused) FOCUS_IN_MS else FOCUS_OUT_MS)
            .setInterpolator(if (focused) SPRING else DECELERATE).start()
        onFocus?.invoke(focused)
    }
}

internal fun MainActivity.label(text: String, size: Float) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(fg)
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
}

internal fun MainActivity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
