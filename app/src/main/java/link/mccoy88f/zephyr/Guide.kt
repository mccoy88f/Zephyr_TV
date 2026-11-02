package link.mccoy88f.zephyr

import android.graphics.drawable.GradientDrawable
import android.text.Html
import android.view.View
import android.widget.LinearLayout

// The user guide on the TV: Settings → System → Guide. It's docs/guida.md (Italian) or docs/guide.md (every other
// language), copied into the assets when the app is built (see build.gradle.kts), so it's written once. Its chapters
// ("## ") are a list; a chapter is its paragraphs, each focusable so Up and Down scroll through it.
// Functions of MainActivity, kept here by topic.

/** A chapter of the guide: its title and its paragraphs, as HTML (bold kept, links and the rest of Markdown gone). */
private class Chapter(val title: String, val blocks: List<String>)

/** The guide in the TV's language (Italian, else English), as chapters; none when it can't be read. */
private fun MainActivity.guide(): List<Chapter> {
    val file = if (java.util.Locale.getDefault().language == "it") "guide/guida.md" else "guide/guide.md"
    val text = runCatching { assets.open(file).use { it.readBytes().decodeToString() } }.getOrNull() ?: return emptyList()
    val chapters = ArrayList<Chapter>()
    var title: String? = null
    var lines = ArrayList<String>()
    fun close() {
        val t = title ?: return
        // The table of contents is links alone: not a chapter to read.
        if (lines.filter { it.isNotBlank() }.all { Regex("""^\d+\.\s+\[""").containsMatchIn(it.trim()) }) return
        chapters += Chapter(t, blocks(lines))
    }
    for (line in text.lines()) {
        if (line.startsWith("## ")) {
            close()
            title = line.removePrefix("## ").trim()
            lines = ArrayList()
        } else if (title != null) lines += line
    }
    close()
    return chapters
}

/** A chapter's lines as paragraphs: consecutive lines together, a heading or a question alone, tables as lines. */
private fun blocks(lines: List<String>): List<String> {
    val out = ArrayList<String>()
    val now = ArrayList<String>()
    fun flush() { if (now.isNotEmpty()) out += now.joinToString("<br>"); now.clear() }
    for (raw in lines) {
        val line = raw.trimEnd()
        val t = line.trim()
        when {
            t.isEmpty() || t == "---" || t == "<details>" || t == "</details>" -> flush()
            t.startsWith("> [!") -> {} // a note's marker: its text follows
            t.startsWith("### ") -> { flush(); out += "<b><big>${inline(t.removePrefix("### "))}</big></b>" }
            t.startsWith("<summary>") -> { flush(); out += "<b>${inline(t.replace(Regex("<[^>]+>"), ""))}</b>" }
            Regex("""^\|[\s:|-]+\|$""").matches(t) -> {} // a table's rule
            t.startsWith("|") -> now += t.trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" — ") { inline(it) }
            t.startsWith("> ") -> now += "› " + inline(t.removePrefix("> "))
            Regex("""^\s{2,}- """).containsMatchIn(line) -> now += "&nbsp;&nbsp;&nbsp;◦ " + inline(t.removePrefix("- "))
            t.startsWith("- ") -> now += "• " + inline(t.removePrefix("- "))
            else -> now += inline(t)
        }
    }
    flush()
    return out
}

/** Markdown inside a line as HTML: bold kept; links as their text; code, images and other tags gone. */
private fun inline(s: String): String = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
    .replace(Regex("""\*\*(.+?)\*\*"""), "<b>$1</b>")
    .replace("`", "")
    .replace(Regex("&lt;[^&]*?&gt;"), "")

/** The guide's chapters; OK on one opens it. [focus]: the chapter to focus. */
internal fun MainActivity.showGuide(focus: Int = 0) {
    val chapters = guide()
    showPage(getString(R.string.setting_guide), chapters.mapIndexed { i, c -> page(c.title, MainActivity.SUBMENU) { showChapter(chapters, i) } },
        focus) { renderSettings(guideRow()) }
}

/** A chapter: its paragraphs, each taking focus in turn, so Up and Down go through it and the page scrolls. */
private fun MainActivity.showChapter(chapters: List<Chapter>, at: Int) {
    val c = chapters[at]
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), 0, dp(4), dp(16))
    }
    for (block in c.blocks) {
        val bg = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        box.addView(label("", 14f).apply {
            text = Html.fromHtml(block, Html.FROM_HTML_MODE_COMPACT)
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setLineSpacing(0f, 1.15f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isFocusable = true
            background = bg
            setOnFocusChangeListener { _, focused -> bg.setColor(if (focused) (fg and 0xFFFFFF) or 0x1F000000 else 0) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    showPage(c.title, emptyList(), top = box) { showGuide(at) }
    box.getChildAt(0)?.let { first ->
        first.requestFocus()
        (settingsList.parent as? android.widget.ScrollView)?.scrollTo(0, 0)
    }
    // Up on the first paragraph and Down on the last stay there.
    box.getChildAt(0)?.let { it.id = View.generateViewId(); it.nextFocusUpId = it.id }
    box.getChildAt(box.childCount - 1)?.let { if (it.id == View.NO_ID) it.id = View.generateViewId(); it.nextFocusDownId = it.id }
}
