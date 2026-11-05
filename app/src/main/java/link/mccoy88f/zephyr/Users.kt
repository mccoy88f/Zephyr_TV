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
internal class User(val id: String, val name: String, val avatar: String? = null, // avatar: DiceBear's (see Avatars)
                    val sid: String? = null) // their id on the Zephyr TV account this TV is linked to (see Sync)

/** What a user's id may be: it names a folder, so letters, digits, "-" and "_" only (a restored backup could say anything). */
private val USER_ID = Regex("[A-Za-z0-9_-]{1,32}")

/** Zephyr TV's users, in the order they were added; the first one always there. */
internal fun MainActivity.users(): List<User> {
    val saved = runCatching {
        val a = org.json.JSONArray(prefs.getString(K.USERS, null) ?: return@runCatching emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.map { User(it.optString("id"), it.optString("name").take(40), it.optString("avatar").takeIf { a -> Avatars.parse(a) != null }, it.optString("sid").takeIf { s -> s.length == 36 }) }
            .filter { it.id == "" || USER_ID.matches(it.id) }
    }.getOrDefault(emptyList())
    val first = saved.firstOrNull { it.id == "" } ?: User("", getString(R.string.user_default))
    return listOf(first) + saved.filter { it.id != "" }
}

internal fun MainActivity.saveUsers(list: List<User>, synced: Boolean = false) {
    prefs.edit().putString(K.USERS, org.json.JSONArray(list.map { u ->
        org.json.JSONObject().put("id", u.id).put("name", u.name).apply { u.avatar?.let { put("avatar", it) }; u.sid?.let { put("sid", it) } }
    }).toString()).apply()
    if (!synced) syncSoon() // a change made here: to the account too, when linked
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
    phonePanel.kind = PhonePanel.Kind.USERS
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

/**
 * The top bar's users button: a small menu under it, as the TV inputs' (see openInputsMenu), not the whole panel:
 * the users side by side, each their avatar (or initial) and name, the one in use ringed; under them the 18+ lock
 * when it's on, and the users' settings. Remote access keeps the panel (see showUsers), which the phone shows.
 */
internal fun MainActivity.openUsersMenu(anchor: android.view.View) {
    val current = currentUser()
    val list = users()
    val box = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = menuBackground()
        elevation = dp(8).toFloat()
        minimumWidth = dp(260)
    }
    val menu = android.widget.PopupWindow(box, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        elevation = dp(8).toFloat()
    }
    val strip = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
    }
    val tiles = list.map { u ->
        userTile(u, u.id == current.id, pick = { menu.dismiss(); if (u.id != current.id) useUser(u) },
            // Held: that user's page (Trakt, rename, avatar, remove), behind the layout lock as the users' settings are.
            hold = { menu.dismiss(); unlock { if (settingsScrim.visibility != android.view.View.VISIBLE) showPanel(); userPage(u) } })
    }
    tiles.forEach { strip.addView(it, android.widget.LinearLayout.LayoutParams(dp(92), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)) }
    // The users in the middle of the menu, however wide its rows make it (the strip fills the scroll's width).
    box.addView(android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(strip, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
    },
        android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(4)
        })
    val rows = listOfNotNull(
        // The 18+ lock, when it's on: unlock with its PIN (in the panel), or lock again.
        if (adultLockOn()) adultMenuRow { closeLauncherSettings() } else null,
        page(getString(R.string.users_settings), SUBMENU) { unlock { usersSettings() } }, // behind the layout lock
    )
    rows.forEach { (title, value, action) ->
        box.addView(settingsRow(title, value) { menu.dismiss(); action() },
            android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(2) })
    }
    box.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)
    val at = IntArray(2).also { anchor.getLocationInWindow(it) }
    // Under the button, as the inputs' menu, kept on the screen when the button is near its edge.
    val screen = window.decorView.width
    val x = (at[0] - dp(100)).coerceAtMost(screen - box.measuredWidth - dp(16)).coerceAtLeast(dp(16))
    menu.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, x, at[1] + anchor.height + dp(8))
    tiles.getOrNull(list.indexOfFirst { it.id == current.id }.coerceAtLeast(0))?.requestFocus()
}

/** A user in the users' menu: their avatar (or initial) in a circle, their name under it; [inUse] ringed; OK [pick], held [hold]. */
private fun MainActivity.userTile(u: User, inUse: Boolean, pick: () -> Unit, hold: () -> Unit): android.view.View {
    val bg = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0) }
    val ring = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(field)
        if (inUse) setStroke(dp(2), fg)
    }
    val pic = ImageView(this).apply {
        background = ring
        setPadding(dp(3), dp(3), dp(3), dp(3))
    }
    fun initial() {
        pic.setPadding(dp(14), dp(14), dp(14), dp(14))
        pic.setImageDrawable(initials(u).apply { setColorFilter(android.graphics.PorterDuffColorFilter(fg, android.graphics.PorterDuff.Mode.SRC_IN)) })
    }
    avatarInto(pic, u.avatar, still = { pic.isAttachedToWindow }, none = ::initial, shown = { pic.setPadding(dp(3), dp(3), dp(3), dp(3)) })
    val name = label((if (inUse) "✓ " else "") + u.name, 13f).apply {
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = android.view.Gravity.CENTER
        setPadding(0, dp(6), 0, 0)
    }
    return android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = bg
        isFocusable = true
        isClickable = true
        contentDescription = u.name
        addView(pic, android.widget.LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(name, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        setOnClickListener { pick() }
        setOnLongClickListener { hold(); true }
        setOnFocusChangeListener { _, focused ->
            bg.setColor(if (focused) fg else 0)
            name.setTextColor(if (focused) onFg else fg)
        }
    }
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
        // Sync with a Zephyr TV account: on (and its page, Sync now), or off (linking this TV).
        page(getString(R.string.sync_title), syncValue()) { ztvAccount { usersSettings(list.size + 1) } } +
        adultSettingsRows { usersSettings(list.size + 2) }, focus) { backFromUsersSettings() }
    userIcons(list)
}

/**
 * Back from the users' settings: the users' menu again, under the top bar's button, as they were opened from it (not
 * the panel's list, which only remote access shows: its users button opens that one again).
 */
private fun MainActivity.backFromUsersSettings() {
    closeLauncherSettings()
    findViewById<android.view.View>(R.id.usersButton)?.let { b -> b.post { openUsersMenu(b) } }
}

/**
 * Before each user's name on the users' settings page, their avatar (or initial). Avatars not in memory yet are
 * loaded, and drawn once they all are, if the page is still up.
 */
private fun MainActivity.userIcons(list: List<User>) {
    val title = getString(R.string.users_settings)
    rowIcons(list.mapIndexed { i, u -> i to (Avatars.cached(u.avatar)?.let { AvatarDrawable(it) } ?: initials(u)) }.toMap(), first = 0)
    val missing = list.mapNotNull { u -> u.avatar?.takeIf { Avatars.parse(it) != null && Avatars.cached(it) == null } }
    if (missing.isEmpty()) return
    posterIo.execute {
        val any = missing.map { Avatars.load(it) }.any { it != null }
        ui.post { if (any && pageShowing(title)) usersSettings(focusedRow() ?: 0) }
    }
}

/** A user's page: their Trakt account (connect, or sync and disconnect), rename, remove. */
private fun MainActivity.userPage(given: User, focus: Int = 0) {
    val u = users().firstOrNull { it.id == given.id } ?: given // as saved: its avatar may have changed
    val dir = vodDir(u.id)
    val account = Trakt.account(dir)
    val back = { usersSettings(users().indexOfFirst { it.id == u.id }.coerceAtLeast(0)) }
    showPage(u.name, listOfNotNull(
        if (account == null) page(getString(R.string.trakt_connect), getString(R.string.trakt_not_connected)) { traktSignIn(u) }
        else page(getString(R.string.trakt_sync), account.user) { syncTrakt(u.id, tell = true) },
        account?.let { page(getString(R.string.trakt_disconnect)) { Trakt.forget(dir); userPage(u) } },
        // Their avatar first, drawn before its row's name (see below), then their name.
        page(getString(R.string.user_avatar), Avatars.parse(u.avatar)?.let { Avatars.styleName(it.first) } ?: getString(R.string.avatar_none_value)) { avatarStyles(u) },
        page(getString(R.string.user_rename)) { userName(u) },
        // The first user stays: their files are Zephyr TV's own.
        if (u.id == "") null else page(getString(R.string.user_remove)) {
            saveUsers(users().filter { it.id != u.id })
            if (currentUser().id == u.id || prefs.getString(K.USER, "") == u.id) useUser(users().first())
            dir.deleteRecursively()
            usersSettings()
        },
    ), focus, back = back)
    val row = avatarRow(u)
    rowIcons(mapOf(row to (Avatars.cached(u.avatar)?.let { AvatarDrawable(it) } ?: initials(u))), first = 0)
    // Not in memory yet: drawn once it's loaded, if this page is still up.
    if (Avatars.parse(u.avatar) != null && Avatars.cached(u.avatar) == null) posterIo.execute {
        if (Avatars.load(u.avatar!!) != null) ui.post { if (pageShowing(u.name)) userPage(u, focusedRow() ?: focus) }
    }
}

/** The row of Rename on [u]'s page, after Avatar. */
private fun MainActivity.renameRow(u: User) = avatarRow(u) + 1

/** The row of Avatar on [u]'s page (after Trakt's one or two). */
private fun MainActivity.avatarRow(u: User) = if (Trakt.account(vodDir(u.id)) == null) 1 else 2

/**
 * [u]'s avatar, first its style: every style side by side, each drawn with the same seed (the user's own, when they
 * have an avatar: themselves in each style) and named under it; OK on one shows that style's avatars (see avatarPage).
 * [focus]: the style to focus; none, the user's own (or the first).
 */
private fun MainActivity.avatarStyles(u: User, focus: String? = null) {
    val seed = Avatars.parse(u.avatar)?.second ?: Avatars.newSeed()
    val mine = Avatars.parse(u.avatar)?.first
    val (strip, tiles) = avatarStrip(Avatars.STYLES.map { "$it/$seed" to Avatars.styleName(it) }, mine?.let { "$it/$seed" }) { a ->
        avatarPage(u, Avatars.parse(a)!!.first)
    }
    val rows = listOfNotNull(if (u.avatar == null) null else page(getString(R.string.avatar_none)) { setAvatar(u, null) })
    showPage(getString(R.string.user_avatar), rows, 0, top = strip) { userPage(u, avatarRow(u)) }
    linkStrip(tiles)
    tiles[((focus ?: mine)?.let { Avatars.STYLES.indexOf(it) } ?: 0).coerceAtLeast(0)].requestFocus()
}

/**
 * A few of [style]'s avatars to pick from (◀ ▶, OK): the user's own first when it's of this style; then others of it,
 * and none (the initial again). Back: the styles. [focus]: the row to focus; none, the first avatar.
 */
private fun MainActivity.avatarPage(u: User, style: String, focus: Int? = null) {
    val choices = (listOfNotNull(u.avatar?.takeIf { Avatars.parse(it)?.first == style }) + List(6) { "$style/${Avatars.newSeed()}" }).take(6)
    val (strip, tiles) = avatarStrip(choices.map { it to null }, u.avatar) { a -> setAvatar(u, a) }
    val rows = listOfNotNull(
        page(getString(R.string.avatar_more)) { avatarPage(u, style, 0) },
        if (u.avatar == null) null else page(getString(R.string.avatar_none)) { setAvatar(u, null) },
    )
    showPage(Avatars.styleName(style), rows, focus ?: 0, top = strip) { avatarStyles(u, style) }
    linkStrip(tiles)
    if (focus == null) tiles.first().requestFocus()
}

/**
 * A row of avatars ([items]: the avatar, and a name to show under it or none), side by side, scrolling sideways;
 * [chosen] ringed; OK on one [pick]s it. The row, and its tiles (each focusable).
 */
private fun MainActivity.avatarStrip(items: List<Pair<String, String?>>, chosen: String?, pick: (String) -> Unit): Pair<android.view.View, List<android.view.View>> {
    val strip = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        setPadding(dp(14), dp(6), dp(14), dp(12))
    }
    val tiles = items.map { (a, name) ->
        val ring = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
        val pic = android.widget.ImageView(this).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = ring
            avatarInto(this, a, still = { isAttachedToWindow })
        }
        val label = name?.let { label(it, 12f).apply { gravity = android.view.Gravity.CENTER; isSingleLine = true; setPadding(0, dp(4), 0, 0) } }
        fun mark(focused: Boolean) {
            ring.setStroke(if (focused) dp(3) else if (a == chosen) dp(2) else 0, fg)
            label?.alpha = if (focused) 1f else 0.7f
        }
        android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            id = android.view.View.generateViewId()
            isFocusable = true
            isClickable = true
            contentDescription = name ?: getString(R.string.user_avatar)
            addView(pic, android.widget.LinearLayout.LayoutParams(dp(72), dp(72)))
            label?.let { addView(it, android.widget.LinearLayout.LayoutParams(dp(84), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)) }
            mark(false)
            setOnFocusChangeListener { _, focused -> mark(focused) }
            setOnClickListener { pick(a) }
        }.also { strip.addView(it, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }) }
    }
    val scroll = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(strip)
    }
    return scroll to tiles
}

/** Up from the page's first row to the avatars on top, Down from them to it (the panel pins Up on its first row otherwise). */
private fun MainActivity.linkStrip(tiles: List<android.view.View>) {
    val first = settingsList.getChildAt(1)
    tiles.forEach { it.nextFocusUpId = it.id; if (first != null) it.nextFocusDownId = first.id else it.nextFocusDownId = it.id }
    first?.nextFocusUpId = tiles.first().id
}

/** [u]'s avatar set ([avatar], or none: their initial), and their page again. */
private fun MainActivity.setAvatar(u: User, avatar: String?) {
    saveUsers(users().map { if (it.id == u.id) User(it.id, it.name, avatar, it.sid) else it })
    if (currentUser().id == u.id) showUserIcon()
    userPage(u, avatarRow(u))
}

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
    val view = findViewById<ImageView>(R.id.usersButton)
    view.contentDescription = getString(R.string.users_title) + ": " + u.name
    // Their avatar, round and nearly the button's size; else, or while it loads, their initial.
    avatarInto(view, u.avatar, still = { currentUser().id == u.id },
        none = { view.setPadding(dp(8), dp(8), dp(8), dp(8)); view.setImageDrawable(initials(u)) },
        shown = { view.setPadding(dp(3), dp(3), dp(3), dp(3)) })
}

/** [u]'s initial, as the top bar draws it: a capital letter, or an emoji whole, in its own colours. */
private fun initials(u: User): Initials {
    val name = u.name.trim()
    val first = java.text.BreakIterator.getCharacterInstance().run {
        setText(name)
        next().takeIf { it > 0 }?.let { name.substring(0, it) }
    } ?: "?"
    val emoji = !Character.isLetterOrDigit(first.codePointAt(0))
    return Initials(if (emoji) first else first.uppercase(), keepColours = emoji)
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
            saveUsers(list.map { if (it.id == u.id) User(it.id, name, it.avatar, it.sid) else it })
            if (currentUser().id == u.id) usersChanged()
            userPage(User(u.id, name, u.avatar, u.sid), renameRow(u))
        }
    }
    fun fromPhone() {
        fromPhoneText(title, getString(R.string.phone_user_intro), getString(R.string.user_name_hint),
            getString(R.string.user_phone_waiting), { save(it.take(40)) }, { userName(u) }, maxBytes = 1024,
            refuse = { if (it.take(40).isEmpty()) getString(R.string.user_name_hint) else null },
            saved = { getString(R.string.phone_user_saved, it.take(40)) }, target = "user-name")
    }
    textPage(title, u?.name.orEmpty(), getString(R.string.user_name_hint), getString(R.string.user_name_intro), Typing.NAME, back = back) { typed ->
        listOf(
            page(getString(R.string.dialog_save)) { save(typed()) },
            page(getString(R.string.from_phone)) { fromPhone() },
        )
    }
}

// ---------- Trakt ----------

/**
 * Connect [u] to Trakt from a phone: a QR code of the sign-in (Trakt's own page, through mccoy88f.link), and the TV
 * asking every few seconds whether it's done, while the page is open (ten minutes at most).
 */
private fun MainActivity.traktSignIn(u: User, intro: String? = null, then: (() -> Unit)? = null) {
    // The site it goes through first (Zephyr TV's once it's set up for Trakt, else the one before it): asked off the UI.
    netIo.execute {
        val site = Trakt.signInSite()
        ui.post { traktSignIn(u, site, intro, then) }
    }
}

/** [u]'s Trakt sign-in through [site]: its QR code, and the TV asking that site until the phone is done. */
private fun MainActivity.traktSignIn(u: User, site: String, intro: String?, then: (() -> Unit)?) {
    val state = Trakt.newState()
    val verifier = Trakt.newVerifier() // PKCE: stays here; the site only ever sees its challenge
    val url = Trakt.signInUrl(state, verifier, site)
    val status = label(getString(R.string.trakt_waiting), 14f)
    val box = qrBox(url, null, intro ?: getString(R.string.trakt_sign_in_intro), status)
    val title = getString(R.string.trakt_connect) + " · " + u.name
    // After a restore ([then]): Skip goes on to the next user; else Cancel, back to the user's page.
    val leave = then ?: { userPage(u) }
    showPage(title, listOf(page(getString(if (then != null) R.string.trakt_skip else R.string.dialog_cancel)) { leave() }), top = box, back = leave)
    val until = System.currentTimeMillis() + 10 * 60_000L
    val dir = vodDir(u.id)
    fun ask() {
        if (!pageShowing(title)) return // left: no more asking
        if (System.currentTimeMillis() > until) return run { status.text = getString(R.string.trakt_expired) }
        netIo.execute {
            val sign = runCatching { Trakt.poll(dir, state, verifier, site) }.getOrDefault(Trakt.SignIn.WAITING) // offline for a moment: ask again
            ui.post {
                when (sign) {
                    Trakt.SignIn.DONE -> {
                        Toast.makeText(this, getString(R.string.trakt_connected, Trakt.account(dir)?.user.orEmpty()), Toast.LENGTH_LONG).show()
                        if (pageShowing(title)) { if (then != null) then() else userPage(u) }
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

/**
 * After a restore: each user that had Trakt on the TV the backup came from (and hasn't here) asked to sign in again,
 * one after another (Skip goes on), as a restore asks for a new PIN; the keys never travel in a backup (see
 * Backup.exportTrakt). [done] once none is left.
 */
internal fun MainActivity.reconnectTrakt(done: () -> Unit) {
    val pending = runCatching { org.json.JSONObject(prefs.getString(K.TRAKT_PENDING, null) ?: "{}") }.getOrDefault(org.json.JSONObject())
    val u = users().firstOrNull { pending.has(it.id) && Trakt.account(vodDir(it.id)) == null }
    if (u == null) {
        prefs.edit().remove(K.TRAKT_PENDING).apply()
        return done()
    }
    val name = pending.optString(u.id)
    pending.remove(u.id)
    prefs.edit().putString(K.TRAKT_PENDING, pending.toString()).apply()
    if (settingsScrim.visibility != android.view.View.VISIBLE) showPanel()
    traktSignIn(u, getString(R.string.trakt_reconnect_intro, u.name, name.ifEmpty { "Trakt" })) { reconnectTrakt(done) }
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
    if (tell) toast(R.string.trakt_syncing, long = false)
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
