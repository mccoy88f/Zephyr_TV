package link.mccoy88f.zephyr

import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout

// Zephyr TV settings → System → About: the logo and version, who made it and whose work it builds on, its
// license, and the pages to see on a phone (a QR code each: a TV has no browser to speak of).
// Functions of MainActivity, kept here by topic.

private const val AUTHOR_URL = "https://github.com/mccoy88f"
private const val SOURCE_URL = "https://github.com/mccoy88f/Zephyr_TV"
private const val PLUME_URL = "https://github.com/aradhyas8/Plume_Launcher"

/** The About page: logo, name and version, the credits and the license, then the links. */
internal fun MainActivity.showAbout(focus: Int = 0) {
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(4), dp(14), dp(16))
        addView(ImageView(this@showAbout).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(96), dp(96)))
        addView(label(getString(R.string.app_name), 20f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(10), 0, 0) })
        addView(label(getString(R.string.about_version, myVersion()), 13f).apply { alpha = 0.7f; setPadding(0, dp(2), 0, dp(12)) })
        // The credits and the license: several paragraphs, read whole.
        for (text in listOf(getString(R.string.about_made), getString(R.string.about_data), getString(R.string.about_license))) {
            addView(label(text, 12f).apply {
                maxLines = Int.MAX_VALUE
                ellipsize = null
                alpha = 0.8f
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(6), 0, dp(6))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }
    // Each link's page comes back to its own row.
    showPage(getString(R.string.setting_about), listOf(
        page(getString(R.string.about_author), "github.com/mccoy88f") { showLink(getString(R.string.about_author), AUTHOR_URL) { showAbout(0) } },
        page(getString(R.string.about_source)) { showLink(getString(R.string.about_source), SOURCE_URL) { showAbout(1) } },
        page(getString(R.string.about_plume)) { showLink(getString(R.string.about_plume), PLUME_URL) { showAbout(2) } },
    ), focus, top = top) { renderSettings(aboutRow()) }
}

/** A page with [url] as a QR code, to open it on a phone. */
private fun MainActivity.showLink(title: String, url: String, back: () -> Unit) {
    val box = qrBox(url, url, getString(R.string.about_link_intro), label("", 12f))
    showPage(title, listOf(page(getString(R.string.dialog_done)) { back() }), top = box, back = back)
}
