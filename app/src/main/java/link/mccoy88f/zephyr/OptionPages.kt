package link.mccoy88f.zephyr

import link.mccoy88f.zephyr.MainActivity.Companion.CHOSEN
import link.mccoy88f.zephyr.MainActivity.Companion.SUBMENU
import org.json.JSONArray
import org.json.JSONObject

// Options pages: the fields a plugin or an extension lists (Nuvio.Field), each a row — a switch, a choice, a text
// typed or pasted from a phone, a list of items (an extension's playlists, its accounts) — read and saved through
// the caller. Plugins' and extensions' options pages are made of these.

/**
 * A page titled [title] of [all]'s fields, [about] and their notes ("info") on top: [value] reads a field's value,
 * [set] saves one (null: back to its default). [again] draws the page anew on the row it's given; [back] leaves.
 */
internal fun MainActivity.formPage(title: String, about: String, all: List<Nuvio.Field>, focus: Int, value: (Nuvio.Field) -> Any?,
                                   set: (Nuvio.Field, Any?) -> Unit, again: (Int) -> Unit, back: () -> Unit,
                                   more: List<Triple<String, String, () -> Unit>> = emptyList()) {
    val (notes, fields) = all.filter { it.type != "header" }.partition { it.type == "info" }
    val text = (listOf(about) + notes.map { n -> listOf(n.label, n.description).filter { it.isNotBlank() }.joinToString(": ") })
        .filter { it.isNotBlank() }.joinToString("\n")
    val top = text.takeIf { it.isNotEmpty() }?.let {
        label(it, 13f).apply { alpha = 0.7f; maxLines = 10; setLineSpacing(0f, 1.15f); setPadding(dp(14), 0, dp(14), dp(12)) }
    }
    showPage(title, fields.mapIndexed { i, f ->
        when (f.type) {
            "toggle" -> {
                val on = value(f).let { it == true || it?.toString() == "true" }
                page(f.label, toggle(on)) { set(f, !on); again(i) }
            }
            "select" -> {
                val now = value(f)?.toString()
                page(f.label, f.choices.firstOrNull { it.second.toString() == now }?.first ?: SUBMENU) {
                    pickField(f, now, { v -> set(f, v); again(i) }) { again(i) }
                }
            }
            "list" -> {
                val items = value(f) as? JSONArray ?: JSONArray()
                page(f.label, if (items.length() == 0) SUBMENU else "${items.length()}") {
                    listPage(f, { value(f) as? JSONArray ?: JSONArray() }, { a -> set(f, a) }, 0) { again(i) }
                }
            }
            else -> {
                val t = value(f)?.toString().orEmpty()
                page(f.label, if (t.isEmpty()) SUBMENU else if (f.secret) "••••••" else t) {
                    typeField(f, t, { v -> set(f, v.ifEmpty { null }) }) { again(i) }
                }
            }
        }
    } + more, focus, top = top, back = back)
}

/** One of [f]'s choices, the current ([now]) marked; [chosen] gets the value of the one picked. */
private fun MainActivity.pickField(f: Nuvio.Field, now: String?, chosen: (Any) -> Unit, back: () -> Unit) {
    val top = f.description.takeIf { it.isNotBlank() }?.let {
        label(it, 13f).apply { alpha = 0.7f; maxLines = 4; setPadding(dp(14), 0, dp(14), dp(12)) }
    }
    val at = f.choices.indexOfFirst { it.second.toString() == now }
    showPage(f.label, f.choices.map { (name, v) -> page(name, if (v.toString() == now) CHOSEN else "") { chosen(v) } },
        at.coerceAtLeast(0), top = top, back = back)
}

/** [f]'s text typed with the remote or pasted on a phone; [save] gets it ("" clears it), then [back]. */
private fun MainActivity.typeField(f: Nuvio.Field, text: String, save: (String) -> Unit, back: () -> Unit) {
    textPage(f.label, text, f.placeholder.ifBlank { f.label }, f.description.ifBlank { null }, Typing.KEY, back = back) { typed ->
        listOf(
            page(getString(R.string.setting_tmdb_key_phone)) { fieldFromPhone(f, save, back) },
            page(getString(R.string.dialog_save)) { save(typed()); back() },
            page(getString(R.string.dialog_clear)) { save(""); back() },
        )
    }
}

/** [f]'s text pasted on a phone (a cookie, a token, an address): saved as it arrives. */
private fun MainActivity.fieldFromPhone(f: Nuvio.Field, save: (String) -> Unit, back: () -> Unit) {
    val status = label(getString(R.string.nuvio_option_waiting), 14f)
    val texts = PhoneTexts(f.label, f.description.ifBlank { getString(R.string.nuvio_option_phone_intro) }, getString(R.string.phone_send),
        getString(R.string.phone_sending), getString(R.string.phone_unreachable), phoneFooter())
    showPhoneLink(f.label, PhoneTask(PhonePages.text(texts, f.placeholder.ifBlank { f.label }), 256L * 1024) { file ->
        val text = file.readText().trim()
        if (text.isEmpty()) return@PhoneTask 400 to getString(R.string.nuvio_option_empty)
        ui.post {
            save(text)
            status.text = getString(R.string.nuvio_option_saved)
        }
        200 to getString(R.string.nuvio_option_saved)
    }, status, back = back)
}

/** What an item of the list [f] is called: its [Nuvio.Field.itemLabel] value, else its first text, else its number. */
private fun itemName(f: Nuvio.Field, o: JSONObject, i: Int): String =
    o.optString(f.itemLabel).takeIf { f.itemLabel.isNotBlank() && it.isNotBlank() }
        ?: f.item.firstOrNull { it.type == "text" && !it.secret }?.let { o.optString(it.key) }?.takeIf { it.isNotBlank() }
        ?: "#${i + 1}"

/** The list [f]: its items ([items] reads them, [save] keeps them), each to edit, then Add. */
private fun MainActivity.listPage(f: Nuvio.Field, items: () -> JSONArray, save: (JSONArray) -> Unit, focus: Int, back: () -> Unit) {
    val list = items()
    showPage(f.label, (0 until list.length()).map { i ->
        val o = list.optJSONObject(i) ?: JSONObject()
        page(itemName(f, o, i), SUBMENU) { itemPage(f, items, save, i) { listPage(f, items, save, i, back) } }
    } + page(getString(R.string.list_add), SUBMENU) { itemPage(f, items, save, -1) { listPage(f, items, save, items().length(), back) } },
        focus, top = f.description.takeIf { it.isNotBlank() }?.let {
            label(it, 13f).apply { alpha = 0.7f; maxLines = 6; setPadding(dp(14), 0, dp(14), dp(12)) }
        }, back = back)
}

/**
 * An item of the list [f] ([at]; -1: a new one, added once a field is set): its fields, then all of them from a phone at
 * once, Move up / down and Remove.
 */
private fun MainActivity.itemPage(f: Nuvio.Field, items: () -> JSONArray, save: (JSONArray) -> Unit, at: Int, focus: Int = 0, back: () -> Unit) {
    var index = at
    fun current(): JSONObject = items().optJSONObject(index) ?: JSONObject().apply { f.item.forEach { it.default?.let { d -> put(it.key, d) } } }
    fun keep(o: JSONObject) {
        val list = items()
        if (index < 0 || index >= list.length()) { list.put(o); index = list.length() - 1 } else list.put(index, o)
        save(list)
    }
    fun move(by: Int) {
        val list = items()
        val all = (0 until list.length()).map { list.get(it) }.toMutableList()
        all.add(index + by, all.removeAt(index))
        save(JSONArray(all))
        index += by
    }
    val title = if (index < 0) getString(R.string.list_add) else itemName(f, current(), index)
    val n = items().length()
    val more = listOfNotNull(
        page(getString(R.string.list_from_phone)) { itemFromPhone(f) { o -> keep(current().also { c -> o.keys().forEach { k -> c.put(k, o.get(k)) } }); itemPage(f, items, save, index, 0, back) } },
        if (index in 1 until n) page(getString(R.string.addon_move_up)) { move(-1); itemPage(f, items, save, index, 0, back) } else null,
        if (index in 0 until n - 1) page(getString(R.string.addon_move_down)) { move(+1); itemPage(f, items, save, index, 0, back) } else null,
        if (index in 0 until n) page(getString(R.string.addon_remove)) {
            val list = items()
            save(JSONArray((0 until list.length()).filter { it != index }.map { list.get(it) }))
            back()
        } else null,
    )
    formPage(title, "", f.item, focus, { fld -> current().opt(fld.key)?.takeIf { it != JSONObject.NULL } ?: fld.default },
        { fld, v -> keep(current().also { c -> if (v == null) c.remove(fld.key) else c.put(fld.key, v) }) },
        { i -> itemPage(f, items, save, index, i, back) }, back, more)
}

/** All the text fields of an item of [f] typed on a phone at once; [got] gets them. */
private fun MainActivity.itemFromPhone(f: Nuvio.Field, got: (JSONObject) -> Unit) {
    val status = label(getString(R.string.nuvio_option_waiting), 14f)
    val texts = PhoneTexts(f.label, f.description.ifBlank { getString(R.string.nuvio_option_phone_intro) }, getString(R.string.phone_send),
        getString(R.string.phone_sending), getString(R.string.phone_unreachable), phoneFooter())
    val inputs = f.item.filter { it.type == "text" }.map { Triple(it.key, it.label, it.placeholder to it.secret) }
    var received: JSONObject? = null
    showPhoneLink(f.label, PhoneTask(PhonePages.form(texts, inputs), 256L * 1024) { file ->
        val o = runCatching { JSONObject(file.readText()) }.getOrNull()?.takeIf { it.length() > 0 }
            ?: return@PhoneTask 400 to getString(R.string.nuvio_option_empty)
        ui.post {
            received = o
            status.text = getString(R.string.nuvio_option_saved)
        }
        200 to getString(R.string.nuvio_option_saved)
    }, status) { received?.let(got) ?: got(JSONObject()) }
}
