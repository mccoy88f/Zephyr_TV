package link.mccoy88f.zephyr

import android.widget.EditText

/**
 * What the phone's remote-access page shows of the TV's panel (see Remote): the page in front, its rows, and a
 * box or a PIN the phone can fill. The panel's pages write here; the phone only reads it, on the main thread.
 */
internal class PhonePanel {
    /** Which panel is open, for the button the phone lights up. */
    enum class Kind(val id: String) { SETTINGS("settings"), USERS("users"), SEARCH("search") }

    var title = ""
        private set
    var rows: List<Triple<String, String, () -> Unit>> = emptyList()
        private set
    var back: (() -> Unit)? = null
        private set
    var kind = Kind.SETTINGS

    /** A box on this page that the phone can type into. */
    var field: EditText? = null

    /** The PIN page in front: the phone's PIN goes here. Null when the PIN is right, else the line to show. */
    var pin: ((String) -> String?)? = null

    /** A panel the phone asked for while another screen was in front: it opens when the home screen is back. */
    var pending: (() -> Unit)? = null

    /** A new page. It clears the box and the PIN of the page before it. */
    fun show(title: String, rows: List<Triple<String, String, () -> Unit>>, back: (() -> Unit)?) {
        this.title = title
        this.rows = rows
        this.back = back
        field = null
        pin = null
    }

    /** The panel closed. The next panel says which one it is; settings when it doesn't. */
    fun close() {
        show("", emptyList(), null)
        kind = Kind.SETTINGS
    }

    /** The page as the phone saw it (see [remotePageKey]). */
    val key get() = remotePageKey(title, rows.map { it.first })
}
