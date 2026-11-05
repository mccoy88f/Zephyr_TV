package link.mccoy88f.zephyr

import android.os.Build
import android.provider.Settings
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
}

/** The account this TV is linked to (its email), or null. */
internal fun MainActivity.ztvEmail(): String? = prefs.getString(K.ZTV_EMAIL, null)?.takeIf { prefs.contains(K.ZTV_TOKEN) }

/** The row's value: the account's email, or Not linked. */
internal fun MainActivity.ztvValue(): String = ztvEmail() ?: getString(R.string.ztv_not_linked)

/** Settings → System → Zephyr TV account: linked, its page; else linking it. */
internal fun MainActivity.ztvAccount() = if (ztvEmail() != null) ztvAccountPage() else ztvLink()

/** This TV's name as the site shows it: the name set in Android's settings, else its model. */
private fun MainActivity.tvName() =
    runCatching { Settings.Global.getString(contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

/** Linking: a code from the site, shown with a QR code of its page; asked about until it's allowed, refused or too old. */
private fun MainActivity.ztvLink() {
    val title = getString(R.string.setting_ztv_account)
    val back: () -> Unit = { renderSettings(accountRow()) }
    val status = label(getString(R.string.ztv_waiting), 14f)
    showPage(title, listOf(page(getString(R.string.dialog_cancel)) { back() }), back = back)
    netIo.execute {
        val start = runCatching { ZtvAccount.start(tvName(), "${Build.MANUFACTURER} ${Build.MODEL}".trim(), myVersion()) }
            .onFailure { warn("account link failed ($it)") }.getOrNull()
        ui.post {
            if (!pageShowing(title)) return@post
            if (start == null) {
                toast(R.string.ztv_failed)
                back()
                return@post
            }
            status.text = getString(R.string.ztv_waiting_allow)
            val box = qrBox(start.url, start.userCode, getString(R.string.ztv_link_intro, start.page, start.userCode), status)
            showPage(title, listOf(page(getString(R.string.dialog_cancel)) { back() }), top = box, back = back)
            fun ask() {
                if (!pageShowing(title)) return
                if (System.currentTimeMillis() > start.until) return run { status.text = getString(R.string.ztv_expired) }
                netIo.execute {
                    val answer = runCatching { ZtvAccount.poll(start.deviceCode) }.getOrDefault(ZtvAccount.Poll.Waiting)
                    ui.post {
                        when (answer) {
                            is ZtvAccount.Poll.Linked -> {
                                prefs.edit().putString(K.ZTV_TOKEN, answer.token).putString(K.ZTV_EMAIL, answer.email).apply()
                                toast(R.string.ztv_done, long = false)
                                if (pageShowing(title)) ztvAccountPage()
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

/**
 * The linked account: its email, how many Zephyr TV users it has (asked of the site; they come to this TV with sync),
 * where to manage it, and Unlink. Unlinked on the site meanwhile: Not linked here too.
 */
private fun MainActivity.ztvAccountPage(users: Int? = null) {
    val title = getString(R.string.setting_ztv_account)
    val email = ztvEmail() ?: return ztvLink()
    val intro = label(getString(R.string.ztv_manage, "$SITE/account"), 13f).apply { alpha = 0.7f; setPadding(dp(14), dp(4), dp(14), dp(10)) }
    showPage(title, listOfNotNull(
        page(getString(R.string.ztv_linked), email) {},
        users?.let { page(getString(R.string.ztv_users), "$it") {} },
        page(getString(R.string.ztv_unlink)) {
            val token = prefs.getString(K.ZTV_TOKEN, null)
            prefs.edit().remove(K.ZTV_TOKEN).remove(K.ZTV_EMAIL).apply()
            token?.let { netIo.execute { ZtvAccount.unlink(it) } }
            toast(R.string.ztv_unlinked, long = false)
            renderSettings(accountRow())
        },
    ), top = intro, back = { renderSettings(accountRow()) })
    if (users != null) return
    val token = prefs.getString(K.ZTV_TOKEN, null) ?: return
    netIo.execute {
        val me = runCatching { ZtvAccount.me(token) }
        ui.post {
            if (!pageShowing(title)) return@post
            val answer = me.getOrNull()
            when {
                me.isFailure -> {} // offline: as it is
                answer == null -> { // unlinked on the site
                    prefs.edit().remove(K.ZTV_TOKEN).remove(K.ZTV_EMAIL).apply()
                    toast(R.string.ztv_unlinked, long = false)
                    renderSettings(accountRow())
                }
                else -> {
                    answer.optString("email").takeIf { it.isNotBlank() && it != email }?.let { prefs.edit().putString(K.ZTV_EMAIL, it).apply() }
                    ztvAccountPage(answer.optJSONArray("users")?.length() ?: 0)
                }
            }
        }
    }
}

/** The row of Zephyr TV account in System, after About. */
internal fun MainActivity.accountRow() = aboutRow() + 1
