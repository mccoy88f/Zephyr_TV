package link.mccoy88f.zephyr

import android.app.Dialog
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import link.mccoy88f.zephyr.MainActivity.Companion.HELP_URL
import link.mccoy88f.zephyr.SettingsRows.HOME_BUTTON_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.ROLE_REQUEST
import link.mccoy88f.zephyr.MainActivity.Companion.WRITE_SECURE

// Making Home land on Zephyr TV: the Home button service, the default-home role, and the guided setup popups.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

/**
 * Android drops our Home service from the enabled accessibility services whenever the app is
 * force-stopped, and then Home lands on Google TV. Put it back (needs the adb-granted permission),
 * unless the user turned it off.
 */
internal fun MainActivity.ensureHomeService() {
    if (homeServiceOn()) return prefs.edit().remove(K.HOME_OFF).apply() // what Android says wins
    if (prefs.getBoolean(K.HOME_OFF, false) || checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return
    val me = ComponentName(this, HomeOverride::class.java).flattenToString()
    val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (on.isEmpty()) me else "$on:$me")
    Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
}

/** Compared as components: Settings may store us in short form (link.mccoy88f.zephyr/.HomeOverride). */
internal fun MainActivity.homeServiceOn(): Boolean {
    val me = ComponentName(this, HomeOverride::class.java)
    return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        .split(':').any { ComponentName.unflattenFromString(it) == me }
}

internal fun MainActivity.toggleHomeButton() = if (homeServiceOn()) turnOffHomeButton() else turnOnHomeButton()

/** Off hands Home back to the TV's own launcher, and Zephyr TV leaves it off until switched on again. */
internal fun MainActivity.turnOffHomeButton() {
    prefs.edit().putBoolean(K.HOME_OFF, true).apply()
    HomeOverride.running?.disableSelf()
    if (homeServiceOn() && checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED) {
        val me = ComponentName(this, HomeOverride::class.java)
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            on.split(':').filter { ComponentName.unflattenFromString(it) != me }.joinToString(":"))
    }
    if (homeServiceOn()) {
        toast(R.string.home_off_hint)
        openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
    renderSettings(HOME_BUTTON_ROW)
}

/** The Home key service is an accessibility service, which Android only lets the user switch on. */
internal fun MainActivity.turnOnHomeButton() {
    prefs.edit().remove(K.HOME_OFF).apply()
    ensureHomeService() // works where the adb permission was granted
    if (homeServiceOn()) return renderSettings(HOME_BUTTON_ROW)
    // The written path is the safety net: some Google TVs (a Xiaomi stick) answer the Accessibility link
    // with "You don't have an app that can do this", which we can't detect from here.
    toast(R.string.home_on_hint)
    openHomeServiceSettings()
}

/**
 * Opens [intent]'s screen if the TV really has it: many TVs answer settings actions with an empty stub, which
 * opens without error and shows nothing. Telling needs the action in the manifest's <queries>; one Android won't
 * resolve for us is still tried, as before. False when nothing opened.
 */
internal fun MainActivity.tryScreen(intent: Intent): Boolean {
    val found = intent.resolveActivity(packageManager)
    if (found != null && found.className.contains("Stub", ignoreCase = true)) return false
    return try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

/** False when no Settings screen opened at all. */
internal fun MainActivity.openHomeServiceSettings(): Boolean {
    val service = ComponentName(this, HomeOverride::class.java).flattenToString()
    val tries = listOf(
        // Straight to our own toggle (Android 12+, where the TV supports it), then the list, then Settings.
        Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, service),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    return tries.any { tryScreen(it) }
}

internal fun MainActivity.isDefaultHome() = Build.VERSION.SDK_INT >= 29 &&
    getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

/**
 * Asks Android to make us the default home app (its own confirmation dialog). On Google TV the system
 * launcher still wins Home intents, which is why the Home button service exists; on other Android TVs
 * this alone is enough. Where Android can't ask: the TV's home app settings, else Android's chooser of home
 * apps (only shown while none is the default; "Always" makes it stick), else the ADB commands.
 */
internal fun MainActivity.makeDefaultHome() {
    if (canAskForHome()) return askForHome()
    if (isDefaultHome()) return openSettings(Settings.ACTION_HOME_SETTINGS) // Android lets apps ask for Home, not give it back
    if (tryScreen(Intent(Settings.ACTION_HOME_SETTINGS))) return
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val current = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
    if ((current == null || current.packageName == "android") && tryScreen(home)) return
    closeLauncherSettings()
    adbPopup()
}

/** Android 10+ can show its own "use as home app" dialog; older versions only have the Settings screen. */
internal fun MainActivity.canAskForHome() = Build.VERSION.SDK_INT >= 29 &&
    getSystemService(RoleManager::class.java).let { it.isRoleAvailable(RoleManager.ROLE_HOME) && !it.isRoleHeld(RoleManager.ROLE_HOME) }

internal fun MainActivity.askForHome() {
    if (Build.VERSION.SDK_INT < 29) return
    @Suppress("DEPRECATION")
    startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME), ROLE_REQUEST)
}

internal fun MainActivity.startHomeSetup() = if (canAskForHome()) rolePopup() else afterHomeRole()

internal fun MainActivity.afterHomeRole() = if (isDefaultHome() || homeServiceOn()) testPopup(1) else tryHomeService()

internal fun MainActivity.tryHomeService() {
    prefs.edit().remove(K.HOME_OFF).apply()
    ensureHomeService() // works where the adb permission was granted
    if (homeServiceOn()) testPopup(2) else servicePopup()
}

/** Back from the role dialog, Accessibility settings, or another home screen after a Home press. */
internal fun MainActivity.homeSetupResumed() {
    if (setupStep == 2) {
        setupStep = 0
        if (homeServiceOn()) testPopup(2) else adbPopup()
    } else if (homeTest != 0 && leftDuringTest) {
        val failed = homeTest
        homeTest = 0
        if (failed == 1) tryHomeService() else adbPopup()
    }
}

/**
 * First start: where films and series come from, Nuvio's catalog (TMDB, in the TV's language) or Cinemeta; then
 * [next], the Home setup. Settings → Catalog source changes it later.
 */
internal fun MainActivity.catalogPopup(next: () -> Unit) = homePopup(0, getString(R.string.setup_catalog_title),
    getString(R.string.setup_catalog_body), null,
    getString(R.string.catalog_nuvio) to { setCatalog(nuvioUrl()); next() },
    getString(R.string.cinemeta_default) to { setCatalog(null); next() })

/**
 * First start, first of all: a local account (nothing to sign up for; this TV keeps its things to itself) or a Zephyr
 * TV account (this TV linked to it, and kept in step with the account's other TVs: see Sync). Then [next].
 */
internal fun MainActivity.accountPopup(next: () -> Unit) = homePopup(0, getString(R.string.setup_account_title),
    getString(R.string.setup_account_body), null,
    getString(R.string.setup_account_local) to next,
    getString(R.string.setup_account_ztv) to { setupLink(next) })

/**
 * The first start's linking: the code and its QR code on the card; once allowed, the first sync. The account had
 * settings: the home screen built again with them, the Home setup after (see K.SETUP_RESUME), the catalog already
 * chosen. Else, or offline, [next] as with a local account. Back: the choice again.
 */
private fun MainActivity.setupLink(next: () -> Unit) {
    var on = true
    val title = getString(R.string.setup_account_ztv)
    val back = getString(R.string.dialog_cancel) to { on = false; accountPopup(next) }
    val status = label("", 14f)
    homePopup(0, title, getString(R.string.ztv_waiting), null, back)
    linkTv({ on && setupDialog != null }, status, { box -> homePopup(0, title, "", box, back) },
        { toast(R.string.ztv_failed); on = false; accountPopup(next) }) { token, email ->
        firstSync(token, email, { main, names, choose ->
            syncChoice(main, names, { t, body, buttons -> homePopup(0, t, body, null, *buttons.toTypedArray()) }, choose)
        }, { r ->
            if (!on) return@firstSync
            on = false
            if (r?.settings == true) {
                prefs.edit().putBoolean(K.SETUP_RESUME, true).apply()
                Sync.recreatePending = false
                setupDialog?.let { setupDialog = null; it.dismiss() }
                recreate()
            } else next()
        }) { on = false; accountPopup(next) }
    }
}

internal fun MainActivity.rolePopup() = homePopup(1, getString(R.string.setup_role_title), getString(R.string.setup_role_body), null,
    getString(R.string.setup_continue) to {
        setupStep = 1
        askForHome()
    },
    getString(R.string.setup_not_now) to { setupDialog?.dismiss() })

internal fun MainActivity.testPopup(way: Int) {
    if (previewAt < 0) {
        homeTest = way
        leftDuringTest = false
    }
    homePopup(way, getString(R.string.setup_test_title), getString(R.string.setup_test_body), null,
        getString(R.string.setup_skip_check) to { homeTest = 0; if (way == 1) tryHomeService() else adbPopup() })
}

internal fun MainActivity.servicePopup() = homePopup(2, getString(R.string.setup_service_title), getString(R.string.setup_service_body), null,
    getString(R.string.setup_open_accessibility) to {
        setupStep = 2
        if (!openHomeServiceSettings()) {
            setupStep = 0
            adbPopup()
        }
    },
    getString(R.string.setup_not_now) to { setupDialog?.dismiss() })

/** The one-time grant lets Zephyr TV switch the service on itself, past the greyed-out toggle. */
internal fun MainActivity.adbPopup() {
    val mono = Typeface.MONOSPACE
    val code = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (dark) 0xFF09090B.toInt() else 0xFFE9EBEF.toInt())
        }
        // The grant switches the Home button service on; set-home-activity makes Zephyr TV the home app where the
        // TV has no screen for it (on TVs without Google TV's launcher that alone is enough); the listener, what's playing.
        listOfNotNull(lanAddress()?.let { "adb connect $it:5555" }, "adb shell pm grant $packageName $WRITE_SECURE",
            "adb shell cmd package set-home-activity $packageName/.MainActivity", "adb shell $LISTENER_GRANT").forEach {
            addView(label(it, 12f).apply { typeface = mono; maxLines = 2; ellipsize = null; setPadding(0, dp(2), 0, dp(2)) })
        }
    }
    val qr = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(context).apply { setImageBitmap(qrBitmap(HELP_URL, dp(108))) }, LinearLayout.LayoutParams(dp(108), dp(108)))
        addView(label(getString(R.string.setup_scan_help), 11f).apply { alpha = 0.6f; setPadding(0, dp(6), 0, 0) })
    }
    val extra = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, 0)
        addView(code, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(qr, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(20) })
    }
    homePopup(3, getString(R.string.setup_adb_title), getString(R.string.setup_adb_body), extra,
        getString(R.string.setup_try_again) to { tryHomeService() },
        getString(R.string.setup_close) to { setupDialog?.dismiss() })
}

internal fun MainActivity.donePopup() = homePopup(0, getString(R.string.setup_done_title),
    getString(if (homeServiceOn()) R.string.setup_done_body_service else R.string.setup_done_body), null,
    getString(R.string.setup_done) to { setupDialog?.dismiss() })

/** A card in the middle of the screen over the dimmed home screen, in the hold menus' colours. */
internal fun MainActivity.homePopup(step: Int, title: String, body: String, extra: View?, vararg buttons: Pair<String, () -> Unit>) {
    setupDialog?.let { setupDialog = null; it.dismiss() } // replaced, not closed: the dismiss listener leaves the flow alone
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(24), dp(28), dp(20))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (dark) 0xFF141418.toInt() else 0xFFF7F8FA.toInt())
        }
        if (step > 0) addView(label(getString(R.string.setup_step, step), 12f).apply { alpha = 0.6f })
        addView(label(title, 22f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, 0) })
        addView(label(body, 14f).apply { maxLines = 9; alpha = 0.8f; setLineSpacing(0f, 1.15f); setPadding(0, dp(10), 0, 0) })
        extra?.let { addView(it) }
        buttons.forEachIndexed { i, (name, action) ->
            addView(settingsRow(name, "") { if (previewAt >= 0) previewHomeSetup(previewAt + 1) else action() },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = if (i == 0) dp(20) else dp(4) })
        }
    }
    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(card)
    dialog.window?.run {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(dp(if (extra != null) 600 else 480), ViewGroup.LayoutParams.WRAP_CONTENT)
        setDimAmount(0.6f)
    }
    dialog.setOnDismissListener {
        if (setupDialog !== dialog) return@setOnDismissListener
        setupDialog = null // closed with Back or a button: setup stops here, and Settings can start it again
        setupStep = 0
        homeTest = 0
        previewAt = -1
    }
    setupDialog = dialog
    dialog.show()
    card.getChildAt(card.childCount - buttons.size).requestFocus()
}

/** Debug builds: every popup in turn, with buttons that only move on, to check the look on a TV. */
internal fun MainActivity.previewHomeSetup(at: Int = 0) {
    previewAt = at
    when (at) {
        0 -> accountPopup {}
        1 -> rolePopup()
        2 -> testPopup(1)
        3 -> servicePopup()
        4 -> adbPopup()
        5 -> donePopup()
        else -> setupDialog?.dismiss()
    }
}
