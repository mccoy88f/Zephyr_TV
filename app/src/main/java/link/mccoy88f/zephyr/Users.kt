package link.mccoy88f.zephyr

import android.widget.ImageView
import android.widget.Toast
import link.mccoy88f.zephyr.MainActivity.Companion.CHOSEN
import link.mccoy88f.zephyr.MainActivity.Companion.SUBMENU
import java.io.File

// Who's watching: Zephyr TV's users, each with what they watched and where they got to (VodProgress, VodWatched, in a
// folder of their own) and, if they like, their Trakt account (see Trakt). The one in use is picked from the top bar,
// left of search. The first user's files are the ones Zephyr TV kept before there were users, so nothing is lost.
// Functions of MainActivity, kept here by topic; the users themselves are in the preferences.

/** A Zephyr TV user: [id] names their folder ("" for the first one: Zephyr TV's own files), [name] is what they're called. */
internal class User(val id: String, val name: String)

/** What a user's id may be: it names a folder, so letters, digits, "-" and "_" only (a restored backup could say anything). */
private val USER_ID = Regex("[A-Za-z0-9_-]{1,32}")

/** Zephyr TV's users, in the order they were added; the first one always there. */
internal fun MainActivity.users(): List<User> {
    val saved = runCatching {
        val a = org.json.JSONArray(prefs.getString(K.USERS, null) ?: return@runCatching emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.map { User(it.optString("id"), it.optString("name").take(40)) }
            .filter { it.id == "" || USER_ID.matches(it.id) }
    }.getOrDefault(emptyList())
    val first = saved.firstOrNull { it.id == "" } ?: User("", getString(R.string.user_default))
    return listOf(first) + saved.filter { it.id != "" }
}

private fun MainActivity.saveUsers(list: List<User>) {
    prefs.edit().putString(K.USERS, org.json.JSONArray(list.map { org.json.JSONObject().put("id", it.id).put("name", it.name) }).toString()).apply()
}

/** The user in use: their progress, watched titles and Trakt account are the ones Zephyr TV shows and keeps. */
internal fun MainActivity.currentUser(): User = users().let { list -> list.firstOrNull { it.id == prefs.getString(K.USER, "") } ?: list.first() }

/** [user]'s folder: what they watched and where they got to, and their Trakt account. Never outside Zephyr TV's files. */
internal fun MainActivity.vodDir(user: String = currentUser().id): File =
    if (user == "" || !USER_ID.matches(user)) filesDir else File(filesDir, "user_$user").apply { mkdirs() }

/**
 * Top bar, the users' icon: the users, the one in use marked; OK on one uses it at once (and closes the panel).
 * Under them, Settings: each user's page and Add user.
 */
internal fun MainActivity.showUsers(focus: Int? = null) {
    if (settingsScrim.visibility != android.view.View.VISIBLE) showPanel()
    val current = currentUser()
    val list = users()
    showPage(getString(R.string.users_title), list.map { u ->
        page(u.name, if (u.id == current.id) CHOSEN else "") {
            if (u.id != current.id) useUser(u)
            closeLauncherSettings()
        }
    } + listOfNotNull(
        // The 18+ lock, when it's on: unlock with its PIN, or lock again.
        if (adultLockOn()) adultMenuRow { showUsers(list.size) } else null,
        page(getString(R.string.users_settings), SUBMENU) { unlock { usersSettings() } }, // adding, removing, Trakt: behind the layout lock
    ),
        focus ?: list.indexOfFirst { it.id == current.id }.coerceAtLeast(0)) { closeLauncherSettings() }
}

/** The users' settings: each user (their page: Trakt, rename, remove), then Add user. */
private fun MainActivity.usersSettings(focus: Int = 0) {
    if (settingsScrim.visibility != android.view.View.VISIBLE) showPanel() // after the PIN, which closes the panel
    val current = currentUser()
    val list = users()
    showPage(getString(R.string.users_settings), list.map { u ->
        val trakt = Trakt.account(vodDir(u.id))?.user?.let { "Trakt: $it" }
        page(u.name, listOfNotNull(trakt, if (u.id == current.id) CHOSEN else null).joinToString("  ")) { userPage(u) }
    } + page(getString(R.string.user_add)) { userName(null) } +
        adultSettingsRows { usersSettings(list.size + 1) }, focus) { showUsers(list.size + if (adultLockOn()) 1 else 0) }
}

/** A user's page: their Trakt account (connect, or sync and disconnect), rename, remove. */
private fun MainActivity.userPage(u: User, focus: Int = 0) {
    val dir = vodDir(u.id)
    val account = Trakt.account(dir)
    val back = { usersSettings(users().indexOfFirst { it.id == u.id }.coerceAtLeast(0)) }
    showPage(u.name, listOfNotNull(
        if (account == null) page(getString(R.string.trakt_connect), getString(R.string.trakt_not_connected)) { traktSignIn(u) }
        else page(getString(R.string.trakt_sync), account.user) { syncTrakt(u.id, tell = true) },
        account?.let { page(getString(R.string.trakt_disconnect)) { Trakt.forget(dir); userPage(u) } },
        page(getString(R.string.user_rename)) { userName(u) },
        // The first user stays: their files are Zephyr TV's own.
        if (u.id == "") null else page(getString(R.string.user_remove)) {
            saveUsers(users().filter { it.id != u.id })
            if (currentUser().id == u.id || prefs.getString(K.USER, "") == u.id) useUser(users().first())
            dir.deleteRecursively()
            usersSettings()
        },
    ), focus, back = back)
}

/** The row of Rename on [u]'s page (after Trakt's one or two). */
private fun MainActivity.renameRow(u: User) = if (Trakt.account(vodDir(u.id)) == null) 1 else 2

/** [u] in use from now on: Continue Playing and the ✓ marks are theirs; synced with Trakt when they have it. */
internal fun MainActivity.useUser(u: User) {
    prefs.edit().putString(K.USER, u.id).apply()
    Toast.makeText(this, getString(R.string.user_switched, u.name), Toast.LENGTH_SHORT).show()
    usersChanged()
    syncTrakt(u.id)
}

/** What shows what the user watched again: Continue Playing, the rows with their ✓ marks, the users' icon. */
internal fun MainActivity.usersChanged() {
    watchKey = null
    watchApp = null
    showUserIcon()
    refreshWatchNext()
    rowsAgain(tmdbLastFocusApp)
}

/**
 * The users' icon in the top bar: the first character of the user in use's name, a capital ("A"), or an emoji, whole
 * ("🦊", "👨‍👩‍👧": one character as it shows, however many code points it's made of).
 */
internal fun MainActivity.showUserIcon() {
    val u = currentUser()
    val name = u.name.trim()
    val first = java.text.BreakIterator.getCharacterInstance().run {
        setText(name)
        next().takeIf { it > 0 }?.let { name.substring(0, it) }
    } ?: "?"
    val emoji = !Character.isLetterOrDigit(first.codePointAt(0))
    findViewById<ImageView>(R.id.usersButton).apply {
        setImageDrawable(Initials(if (emoji) first else first.uppercase(), keepColours = emoji))
        contentDescription = getString(R.string.users_title) + ": " + u.name
    }
}

/**
 * A user's letter, drawn white in the middle of what it's given (the top bar tints it, as its icons); an emoji in
 * its own colours ([keepColours]: the tint would make it a blot).
 */
private class Initials(private val text: String, private val keepColours: Boolean) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        textAlign = android.graphics.Paint.Align.CENTER
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        paint.textSize = b.height() * if (keepColours) 0.8f else 0.78f
        val fm = paint.fontMetrics
        canvas.drawText(text, b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { if (!keepColours) paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** A new user's name ([u] null), or [u]'s new one, typed with the remote. */
/**
 * A new user's name ([u] null), or [u]'s new one: typed with the remote, or on a phone (whose keyboard has emoji,
 * for the users' icon; a TV's often has none).
 */
private fun MainActivity.userName(u: User?) {
    val title = getString(if (u == null) R.string.user_add else R.string.user_rename)
    val back = { if (u == null) usersSettings(users().size) else userPage(u, renameRow(u)) }
    fun save(typed: String) {
        val name = typed.trim().take(40).ifEmpty { return }
        val list = users()
        if (u == null) {
            val added = User(java.util.UUID.randomUUID().toString().take(8), name)
            saveUsers(list + added)
            userPage(added)
        } else {
            saveUsers(list.map { if (it.id == u.id) User(it.id, name) else it })
            if (currentUser().id == u.id) usersChanged()
            userPage(User(u.id, name), renameRow(u))
        }
    }
    fun fromPhone() {
        val status = label(getString(R.string.user_phone_waiting), 14f)
        val page = PhonePages.text(phoneTexts(R.string.phone_user_title, R.string.phone_user_intro), getString(R.string.user_name_hint))
        showPhoneLink(title, PhoneTask(page, 1024) { file ->
            val name = file.readText().trim().take(40).ifEmpty { return@PhoneTask 400 to getString(R.string.user_name_hint) }
            ui.post { save(name) }
            200 to getString(R.string.phone_user_saved, name)
        }, status) { userName(u) }
    }
    textPage(title, u?.name.orEmpty(), getString(R.string.user_name_hint), getString(R.string.user_name_intro), Typing.NAME, back = back) { typed ->
        listOf(
            page(getString(R.string.dialog_save)) { save(typed()) },
            page(getString(R.string.user_name_phone)) { fromPhone() },
        )
    }
}

// ---------- Trakt ----------

/**
 * Connect [u] to Trakt from a phone: a QR code of the sign-in (Trakt's own page, through mccoy88f.link), and the TV
 * asking every few seconds whether it's done, while the page is open (ten minutes at most).
 */
internal fun MainActivity.traktSignIn(u: User) {
    val state = Trakt.newState()
    val url = Trakt.signInUrl(state)
    val status = label(getString(R.string.trakt_waiting), 14f)
    val box = qrBox(url, null, getString(R.string.trakt_sign_in_intro), status)
    val title = getString(R.string.trakt_connect) + " · " + u.name
    showPage(title, listOf(page(getString(R.string.dialog_cancel)) { userPage(u) }), top = box, back = { userPage(u) })
    val until = System.currentTimeMillis() + 10 * 60_000L
    val dir = vodDir(u.id)
    fun ask() {
        if (!pageShowing(title)) return // left: no more asking
        if (System.currentTimeMillis() > until) return run { status.text = getString(R.string.trakt_expired) }
        netIo.execute {
            val sign = runCatching { Trakt.poll(dir, state) }.getOrDefault(Trakt.SignIn.WAITING) // offline for a moment: ask again
            ui.post {
                when (sign) {
                    Trakt.SignIn.DONE -> {
                        Toast.makeText(this, getString(R.string.trakt_connected, Trakt.account(dir)?.user.orEmpty()), Toast.LENGTH_LONG).show()
                        if (pageShowing(title)) userPage(u)
                        syncTrakt(u.id, tell = true)
                    }
                    Trakt.SignIn.REFUSED -> status.text = getString(R.string.trakt_refused)
                    Trakt.SignIn.EXPIRED -> status.text = getString(R.string.trakt_expired)
                    Trakt.SignIn.WAITING -> ui.postDelayed({ ask() }, 5000)
                }
            }
        }
    }
    ui.postDelayed({ ask() }, 5000)
}

private val syncing = java.util.Collections.synchronizedSet(HashSet<String>())

/**
 * Brings [user]'s Trakt history and pauses into Zephyr TV: what they watched gets its ✓ (and leaves Continue Playing),
 * where they paused elsewhere goes into Continue Playing (when newer than Zephyr TV's own). Trakt is the history once
 * connected: the first sync sends it what Zephyr TV has that it hasn't; after that, what was taken off on Trakt since
 * the last sync is taken off here too. [tell]: say how it went. Without an account, nothing.
 */
internal fun MainActivity.syncTrakt(user: String = currentUser().id, tell: Boolean = false) {
    val dir = vodDir(user)
    if (Trakt.account(dir) == null || !syncing.add(user)) return
    if (tell) Toast.makeText(this, getString(R.string.trakt_syncing), Toast.LENGTH_SHORT).show()
    longIo.execute { // the whole history: not on the threads pages wait for
        var signedOut = false
        val result = runCatching {
            val watched = try {
                Trakt.watched(dir)
            } catch (e: Trakt.SignedOut) {
                Trakt.forget(dir) // refused on Trakt's side: connected again from the user's page
                signedOut = true
                null
            } ?: return@runCatching null
            val ids = watched.map { it.id }.toSet()
            val before = Trakt.seen(dir)
            // Films and episodes, not the marks of series watched whole (Zephyr TV's own, from their episodes).
            val mine = VodWatched.all(dir).values.let { all -> val series = all.filter { ':' in it.id }.map { it.metaId }.toSet(); all.filter { ':' in it.id || it.id !in series } }
            // First sync: what Zephyr TV knew before (watched here, or marked) goes to Trakt, with when. Until Trakt took
            // it, nothing counts as seen there (else a failed send would read as taken off on Trakt next time).
            val sent = before != null || mine.filter { it.id !in ids }.let { missing ->
                missing.isEmpty() || Trakt.history(dir, missing.map { it.id to it.metaId }, true, missing.associate { it.id to it.at })
            }
            if (before != null) {
                // Taken off on Trakt since: off here too.
                val gone = mine.filter { it.id in before && it.id !in ids }
                if (gone.isNotEmpty()) VodWatched.set(dir, gone.map { it.id to it.metaId }, false)
            }
            VodWatched.add(dir, watched) // with Trakt's dates: the Watched rows' order
            if (sent) Trakt.seen(dir, ids + (if (before == null) mine.map { it.id } else emptyList()))
            // Watched elsewhere after it was paused here: nothing left to resume.
            val marks = watched.associateBy { it.id }
            VodProgress.all(dir).filter { e -> (marks[e.id]?.at ?: 0L) > e.at }.forEach { VodProgress.remove(dir, it.id) }
            val paused = Trakt.paused(dir).orEmpty().filter { it.runtimeMin > 0 }
            val begun = VodProgress.all(dir).associateBy { it.id }
            var resumed = 0
            for (p in paused) {
                // Newer than Zephyr TV's own; and paused after it was last watched (begun again), or never watched.
                if ((begun[p.id]?.at ?: 0L) >= p.at || (marks[p.id]?.at ?: 0L) >= p.at) continue
                val duration = p.runtimeMin * 60_000L
                val sub = if (!p.series) "" else listOf(getString(R.string.title_season_episode, p.season, p.number), p.episodeTitle)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                // Cinemeta's background for the title, as its card in the Films and Series rows has.
                VodProgress.put(dir, VodProgress.Entry(p.id, if (p.series) "series" else "movie", p.metaId, p.title, sub,
                    Stremio.art(p.metaId, "background/medium"), (duration * p.progress / 100).toLong(), duration, p.at))
                resumed++
            }
            Trakt.synced(dir, System.currentTimeMillis())
            watched.size to resumed
        }.getOrNull()
        syncing.remove(user)
        ui.post {
            if (user == currentUser().id) usersChanged()
            if (!tell && !signedOut) return@post
            Toast.makeText(this, when {
                signedOut -> getString(R.string.trakt_signed_out)
                result == null -> getString(R.string.trakt_failed)
                else -> getString(R.string.trakt_synced, result.first, result.second)
            }, Toast.LENGTH_LONG).show()
        }
    }
}

/** Back on the home screen: the user in use synced with Trakt now and then (every quarter of an hour at most). */
internal fun MainActivity.syncTraktNow() {
    val a = Trakt.account(vodDir()) ?: return
    if (System.currentTimeMillis() - a.synced > 15 * 60_000L) syncTrakt()
}

/** Films and episodes marked watched or not on a page: the same in the user's Trakt history. */
internal fun MainActivity.traktHistory(items: List<Pair<String, String>>, on: Boolean) {
    val dir = vodDir()
    if (Trakt.account(dir) != null) netIo.execute { Trakt.history(dir, items, on) }
}
