package link.mccoy88f.zephyr

import android.os.Build
import android.widget.TextView
import org.json.JSONObject

/**
 * This TV linked to an account on Zephyr TV's site (SITE): the site gives it a short code to show (and a QR code of
 * the page to enter it at), someone signed in there allows it, and the TV gets a token of its own, which it sends with
 * every call after. Unlinked from here or from the account's page on the site. Network: off the UI thread.
 */
internal object ZtvAccount {
    /** A link begun: the code to show, the page to enter it at (with the code, for a QR code), how often to ask. */
    class Start(val deviceCode: String, val userCode: String, val url: String, val page: String, val intervalMs: Long, val until: Long)

    /** Where a link is: waiting for someone to allow it, linked (its token and the account's email), refused, gone. */
    sealed class Poll {
        object Waiting : Poll()
        class Linked(val token: String, val email: String) : Poll()
        object Denied : Poll()
        object Gone : Poll()
    }

    /** A link begun for this TV, named [name] ([model], Zephyr TV [version]). Throws when the site can't be asked. */
    fun start(name: String, model: String, version: String): Start {
        val (code, body) = Http.send("$SITE/api/v1/link/start", JSONObject().put("name", name).put("model", model).put("version", version))
        if (code !in 200..299) throw java.io.IOException("link start: $code")
        val o = JSONObject(body)
        return Start(o.getString("device_code"), o.getString("user_code"), o.getString("verification_url_complete"),
            o.optString("verification_url", "$SITE/link"), o.optLong("interval", 5).coerceIn(2, 30) * 1000,
            System.currentTimeMillis() + o.optLong("expires_in", 600) * 1000)
    }

    fun poll(deviceCode: String): Poll {
        val (code, body) = Http.send("$SITE/api/v1/link/poll", JSONObject().put("device_code", deviceCode))
        return when (code) {
            200 -> JSONObject(body).let { Poll.Linked(it.getString("token"), it.optString("email")) }
            202, 429 -> Poll.Waiting
            410 -> Poll.Denied
            else -> Poll.Gone
        }
    }

    /** The account as the site has it ({email, device, users}), or null once the TV is unlinked there (401). Throws offline. */
    fun me(token: String): JSONObject? {
        val (code, body) = Http.send("$SITE/api/v1/me", null, mapOf("Authorization" to "Bearer $token"), "GET")
        if (code == 401) return null
        if (code !in 200..299) throw java.io.IOException("me: $code")
        return JSONObject(body)
    }

    /** This TV unlinked on the site (its token stops working). */
    fun unlink(token: String) {
        runCatching { Http.send("$SITE/api/v1/unlink", JSONObject(), mapOf("Authorization" to "Bearer $token")) }
    }

    /** The TV's token on the site has stopped working (unlinked there): this TV is unlinked too. */
    class Unlinked : Exception("unlinked")

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    private fun call(token: String, path: String, body: JSONObject?, method: String): Pair<Int, String> {
        val r = Http.send("$SITE$path", body, auth(token), method)
        if (r.first == 401) throw Unlinked()
        return r
    }

    /** Where the account stands: {email, users: [{id, name, avatar}], docs: {key: revision}}. Throws offline, or Unlinked. */
    fun state(token: String): JSONObject {
        val (code, body) = call(token, "/api/v1/sync", null, "GET")
        if (code !in 200..299) throw java.io.IOException("sync: $code")
        return JSONObject(body)
    }

    /** A document's revision and what it holds (null: none yet, or unreadable on the site). */
    fun doc(token: String, key: String): Pair<Int, JSONObject?> {
        val (code, body) = call(token, "/api/v1/sync/doc?key=${java.net.URLEncoder.encode(key, "UTF-8")}", null, "GET")
        if (code !in 200..299) throw java.io.IOException("doc $key: $code")
        val o = JSONObject(body)
        return o.optInt("rev") to o.optString("data").takeIf { !o.isNull("data") && it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    /** [data] written over revision [base]: its new revision, or (409) the document as it is now, to merge with. */
    fun put(token: String, key: String, base: Int, data: JSONObject): Pair<Int, JSONObject?>? {
        val (code, body) = call(token, "/api/v1/sync/doc?key=${java.net.URLEncoder.encode(key, "UTF-8")}",
            JSONObject().put("base", base).put("data", data.toString()), "PUT")
        return when (code) {
            in 200..299 -> JSONObject(body).optInt("rev") to null
            409 -> JSONObject(body).let { o -> o.optInt("rev") to (o.optString("data").takeIf { !o.isNull("data") && it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()) }
            else -> throw java.io.IOException("put $key: $code")
        }
    }

    /** A user added to the account: their id there, or null (12 already). */
    fun addUser(token: String, name: String, avatar: String?): String? {
        val (code, body) = call(token, "/api/v1/users", JSONObject().put("name", name).put("avatar", avatar ?: JSONObject.NULL), "POST")
        if (code == 409) return null
        if (code !in 200..299) throw java.io.IOException("add user: $code")
        return JSONObject(body).getString("id")
    }

    fun updateUser(token: String, id: String, name: String, avatar: String?) {
        call(token, "/api/v1/users/$id", JSONObject().put("name", name).put("avatar", avatar ?: JSONObject.NULL), "POST")
    }

    fun removeUser(token: String, id: String) {
        call(token, "/api/v1/users/$id", null, "DELETE")
    }
}

/** The account this TV is linked to (its email), or null. */
internal fun MainActivity.ztvEmail(): String? = prefs.getString(K.ZTV_EMAIL, null)?.takeIf { prefs.contains(K.ZTV_TOKEN) }

/** The row's value: the account's email, or Not linked. */
internal fun MainActivity.ztvValue(): String = ztvEmail() ?: getString(R.string.ztv_not_linked)

/**
 * Settings → System → Zephyr TV account, or the users' settings' Sync: linked, its page; else linking it. [back]:
 * where Back goes (System's row by default).
 */
internal fun MainActivity.ztvAccount(back: (() -> Unit)? = null) =
    if (ztvEmail() != null) ztvAccountPage(back = back) else ztvLink(back)

/** Sync's state for a row: on, with the account's email; or off. */
internal fun MainActivity.syncValue(): String = ztvEmail()?.let { getString(R.string.sync_on, it) } ?: getString(R.string.sync_off)

/**
 * This TV unlinked here (by the user, or found unlinked on the site): its token, what sync kept, and its users' ids
 * on the account forgotten. The users themselves and what they watched stay, as a TV of its own.
 */
internal fun MainActivity.ztvForget() {
    prefs.edit().remove(K.ZTV_TOKEN).remove(K.ZTV_EMAIL).remove(K.SYNC_USERS).remove(K.SYNC_AT).apply()
    syncDir().deleteRecursively()
    saveUsers(users().map { User(it.id, it.name, it.avatar) }, synced = true)
}

/**
 * Linking, wherever it's shown ([alive]: still showing): a code from the site for [box] (its QR code, the code, how
 * to), then asked about every few seconds until it's allowed ([linked], the token kept), refused or too old ([status]).
 * [failed]: the site couldn't be reached.
 */
internal fun MainActivity.linkTv(alive: () -> Boolean, status: TextView, box: (view: android.view.View, url: String) -> Unit, failed: () -> Unit,
                                  linked: (token: String, email: String) -> Unit) {
    status.text = getString(R.string.ztv_waiting)
    netIo.execute {
        val start = runCatching { ZtvAccount.start(tvName(), "${Build.MANUFACTURER} ${Build.MODEL}".trim(), myVersion()) }
            .onFailure { warn("account link failed ($it)") }.getOrNull()
        ui.post {
            if (!alive()) return@post
            if (start == null) return@post failed()
            status.text = getString(R.string.ztv_waiting_allow)
            box(qrBox(start.url, start.userCode, getString(R.string.ztv_link_intro, start.page, start.userCode), status), start.url)
            fun ask() {
                if (!alive()) return
                if (System.currentTimeMillis() > start.until) return run { status.text = getString(R.string.ztv_expired) }
                netIo.execute {
                    val answer = runCatching { ZtvAccount.poll(start.deviceCode) }.getOrDefault(ZtvAccount.Poll.Waiting)
                    ui.post {
                        if (!alive()) return@post
                        when (answer) {
                            is ZtvAccount.Poll.Linked -> {
                                status.text = getString(R.string.sync_working)
                                linked(answer.token, answer.email) // kept once the first sync's choice is made (see firstSync)
                            }
                            ZtvAccount.Poll.Waiting -> ui.postDelayed({ ask() }, start.intervalMs)
                            ZtvAccount.Poll.Denied -> status.text = getString(R.string.ztv_denied)
                            ZtvAccount.Poll.Gone -> status.text = getString(R.string.ztv_expired)
                        }
                    }
                }
            }
            ui.postDelayed({ ask() }, start.intervalMs)
        }
    }
}

/** Linking from Settings: once linked, the first sync; the home screen again if the account's settings came in. */
private fun MainActivity.ztvLink(backTo: (() -> Unit)? = null) {
    val title = getString(R.string.setting_ztv_account)
    val back: () -> Unit = backTo ?: { renderSettings(accountRow()) }
    val status = label("", 14f)
    val rows = listOf(page(getString(R.string.dialog_cancel)) { back() })
    showPage(title, rows, back = back)
    linkTv({ pageShowing(title) }, status, { box, url -> showPage(title, listOfNotNull(openLinkRow(url)) + rows, top = box, back = back) },
        { toast(R.string.ztv_failed); back() }) { token, email ->
        toast(R.string.ztv_done, long = false)
        firstSync(token, email, { main, names, choose ->
            syncChoice(main, names, { t, body, buttons ->
                val intro = label(body, 14f).apply { setPadding(dp(14), dp(4), dp(14), dp(10)) }
                showPage(t, buttons.map { (name, act) -> page(name) { act() } }, top = intro, back = { choose(null) })
            }, choose)
        }, { r ->
            if (r?.settings == true) {
                prefs.edit().putInt(K.REOPEN_ROW, accountRow()).apply()
                Sync.recreatePending = false
                recreate()
            } else if (ztvEmail() != null) ztvAccountPage(back = backTo) else back()
        }, back)
    }
}

/**
 * The linked account: its email, its Zephyr TV users, Sync now (when it last did), where to manage it, and Unlink.
 * Unlinked on the site meanwhile: Not linked here too (see Sync).
 */
private fun MainActivity.ztvAccountPage(focus: Int = 0, back: (() -> Unit)? = null) {
    val title = getString(R.string.setting_ztv_account)
    val email = ztvEmail() ?: return ztvLink(back)
    val leave: () -> Unit = back ?: { renderSettings(accountRow()) }
    val intro = label(getString(R.string.ztv_manage, "$SITE/account"), 13f).apply { alpha = 0.7f; setPadding(dp(14), dp(4), dp(14), dp(10)) }
    val at = prefs.getLong(K.SYNC_AT, 0L)
    val synced = users().count { it.sid != null }
    showPage(title, listOf(
        page(getString(R.string.ztv_linked), email) {},
        page(getString(R.string.ztv_users), "$synced") { showUsers() },
        page(getString(R.string.sync_now), if (at == 0L) getString(R.string.sync_never)
            else android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), 60_000L).toString()) {
            toast(R.string.sync_working, long = false)
            syncNow { r ->
                toast(if (r == null) R.string.sync_failed else R.string.sync_done, long = false)
                if (pageShowing(title) && ztvEmail() != null) ztvAccountPage(2, back)
            }
        },
        page(getString(R.string.ztv_unlink)) {
            val token = prefs.getString(K.ZTV_TOKEN, null)
            ztvForget()
            token?.let { netIo.execute { ZtvAccount.unlink(it) } }
            toast(R.string.ztv_unlinked, long = false)
            leave()
        },
    ), focus, top = intro, back = leave)
}

/** The row of Zephyr TV account in System, after About. */
internal fun MainActivity.accountRow() = aboutRow() + 1
