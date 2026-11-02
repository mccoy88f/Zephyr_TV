package link.mccoy88f.zephyr

import android.animation.ObjectAnimator
import android.content.pm.ApplicationInfo
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import link.mccoy88f.zephyr.MainActivity.Companion.ALIGN_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.ADDONS_SECTION
import link.mccoy88f.zephyr.MainActivity.Companion.APPS_SECTION
import link.mccoy88f.zephyr.MainActivity.Companion.CHOSEN
import link.mccoy88f.zephyr.MainActivity.Companion.CLOCK_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.FADE_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.HDMI
import link.mccoy88f.zephyr.MainActivity.Companion.ADDON_ROWS
import link.mccoy88f.zephyr.MainActivity.Companion.HIDDEN_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.UPDATE_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.EPG_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.PLAY_CHANNELS_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.CHANNEL_LOGOS_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.ICONS_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.INPUTS_MODE_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.INPUTS_IN_APPS_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.LOCK_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.LOOK_SECTION
import link.mccoy88f.zephyr.MainActivity.Companion.NAMES_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.NIGHT_FROM_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.NIGHT_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.NIGHT_TO_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.STATUS_BAR_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.PIN_KEYS
import link.mccoy88f.zephyr.MainActivity.Companion.PIN_TRIES
import link.mccoy88f.zephyr.MainActivity.Companion.ROW_ALIGN
import link.mccoy88f.zephyr.MainActivity.Companion.SCREENSAVER_SECTION
import link.mccoy88f.zephyr.MainActivity.Companion.SECTION
import link.mccoy88f.zephyr.MainActivity.Companion.SORT_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.SPOTLIGHT_EVERY
import link.mccoy88f.zephyr.MainActivity.Companion.SPOTLIGHT_SECTION
import link.mccoy88f.zephyr.MainActivity.Companion.SYSTEM_SECTION
import link.mccoy88f.zephyr.MainActivity.Companion.THEME_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.TMDB_CYCLE_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.TMDB_SOURCE_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.TMDB_TYPE_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.SUBMENU
import link.mccoy88f.zephyr.MainActivity.Companion.TOGGLE_OFF
import link.mccoy88f.zephyr.MainActivity.Companion.TOGGLE_ON
import link.mccoy88f.zephyr.MainActivity.Companion.UNLOCKED_MS
import link.mccoy88f.zephyr.MainActivity.Companion.TV_SECTION
import link.mccoy88f.zephyr.MainActivity.Companion.WATCH_SECTION
import link.mccoy88f.zephyr.MainActivity.Companion.WEATHER_ROW
import link.mccoy88f.zephyr.MainActivity.Companion.WEATHER_UNITS_ROW

// Zephyr TV settings: the side panel, its sections and pages, the on/off rows and the layout lock PIN.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

/** Opens the panel on its list of sections, or straight on the section page holding [row]. */
internal fun MainActivity.openLauncherSettings(row: Int? = null) {
    showPanel()
    if (row == null) renderSettingsHome() else renderSettings(row)
}

internal fun MainActivity.showPanel() {
    panelFrom = currentFocus
    val panel = findViewById<View>(R.id.settingsPanel)
    panelClose() // a phone or tablet: an X at its top left, and a tap beside it closes it (see Handheld)
    // Glass: a smoked pane over the home screen, lit along its inner edge, instead of a solid sheet.
    if (glass) panel.background = LayerDrawable(arrayOf(
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD92A2A32.toInt(), 0xE6101014.toInt())),
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x1FFFFFFF, 0x00FFFFFF)).apply { setSize(dp(2), 0) },
    )).apply { setLayerWidth(1, dp(2)) }
    else panel.setBackgroundColor(if (dark) 0xFF09090B.toInt() else 0xFFF7F8FA.toInt())
    findViewById<View>(R.id.settingsScrim).setBackgroundColor(if (glass) 0x59000000 else 0x99000000.toInt())
    panelArrows()
    findViewById<TextView>(R.id.settingsTitle).setTextColor(fg)
    settingsScrim.visibility = View.VISIBLE
    homeFocusable(false)
}

internal fun MainActivity.closeLauncherSettings() {
    // A box on the page (search, city, API key) may have the keyboard up: it would stay over the home screen.
    getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
    pageExit?.invoke()
    pageExit = null
    pageBack = null
    pageSides = null
    pageReturn = null
    settingsScrim.visibility = View.GONE
    homeFocusable(allApps.visibility != View.VISIBLE) // the PIN can open over All apps
    (panelFrom?.takeIf { it.isAttachedToWindow } ?: findViewById(R.id.launcherSettings)).requestFocus()
    panelFrom = null
    noteRemotePage("", emptyList(), null) // the phone's page follows the panel: closed with it
}

internal fun MainActivity.sectionTitle(section: Int) = getString(when (section) {
    LOOK_SECTION -> R.string.settings_section_look
    APPS_SECTION -> R.string.settings_section_apps
    WATCH_SECTION -> R.string.settings_section_content
    TV_SECTION -> R.string.settings_section_tv
    ADDONS_SECTION -> R.string.settings_section_addons
    SPOTLIGHT_SECTION -> R.string.setting_tmdb
    SCREENSAVER_SECTION -> R.string.setting_screensaver
    else -> R.string.settings_section_system
})

/** Icon at the start of that section's row on the first settings page. */
internal fun MainActivity.sectionIcon(section: Int) = when (section) {
    LOOK_SECTION -> R.drawable.ic_settings_look
    APPS_SECTION -> R.drawable.ic_apps
    WATCH_SECTION -> R.drawable.ic_movie
    TV_SECTION -> R.drawable.ic_live_tv
    ADDONS_SECTION -> R.drawable.ic_stream
    SPOTLIGHT_SECTION -> R.drawable.ic_settings_spotlight
    SCREENSAVER_SECTION -> R.drawable.ic_settings_screensaver
    else -> R.drawable.ic_settings
}

/** The sections in the order the settings' first page lists them (their numbers aren't that order). */
internal val HOME_SECTIONS = listOf(LOOK_SECTION, APPS_SECTION, WATCH_SECTION, TV_SECTION, ADDONS_SECTION, SPOTLIGHT_SECTION, SCREENSAVER_SECTION, SYSTEM_SECTION)

/** The sections, each a page of its own: an arrow says so (a value here would only repeat the page's first row). */
internal fun MainActivity.renderSettingsHome(focus: Int = 0) {
    val sections = HOME_SECTIONS
    showPage(getString(R.string.plume_settings), sections.map { section ->
        // Add-ons and plugins: Stremio's add-ons and Nuvio's plugins, a page that leads to each.
        if (section == ADDONS_SECTION) page(getString(R.string.settings_section_addons_plugins), SUBMENU) { addonsAndPlugins() }
        else page(sectionTitle(section), SUBMENU) { renderSettings(section * SECTION) }
    }, focus, back = null)
    updateDot(sections.indexOf(SYSTEM_SECTION))
    // The one thing that needs doing by hand still shows on its section: Zephyr TV isn't fully set up as the home screen.
    if (!homeServiceOn()) (settingsList.getChildAt(sections.indexOf(SYSTEM_SECTION)) as? LinearLayout)?.addView(
        label(getString(R.string.settings_summary_setup), 13f).apply { alpha = 0.6f; setPadding(dp(12), 0, dp(8), 0) }, 1)
    rowIcons(sections.mapIndexed { i, section ->
        i to getDrawable(sectionIcon(section))!!.mutate()
    }.toMap(), first = 0)
}

/** Shows the section page holding [row], focused on it. */
internal fun MainActivity.renderSettings(row: Int) {
    val section = row / SECTION
    showPage(sectionTitle(section), sectionRows(section), row % SECTION) {
        if (section == ADDONS_SECTION) addonsAndPlugins(1) else renderSettingsHome(HOME_SECTIONS.indexOf(section))
    }
    shownSection = section
    // Each add-on's row starts with its icon, as its Watch on rows on a title's page do.
    if (section == ADDONS_SECTION) rowIcons(addonUrls().mapIndexed { i, url ->
        ADDON_ROWS % SECTION + i to (Stremio.saved(filesDir, url)?.addon?.let { addonIcon(it) } ?: getDrawable(R.drawable.ic_stream)!!.mutate())
    }.toMap(), first = 0)
    if (section == ADDONS_SECTION) fetchAddonLogos()
    if (section == SYSTEM_SECTION) updateDot(UPDATE_ROW % SECTION)
}

/** Logos asked for once while Zephyr TV runs: one that won't download isn't asked again on every redraw. */
internal val askedLogos = java.util.Collections.synchronizedSet(HashSet<String>())

/** The add-ons' logos not on disk yet (added before every logo was kept): downloaded, then their rows again. */
private fun MainActivity.fetchAddonLogos() {
    val missing = addonUrls().mapNotNull { Stremio.saved(filesDir, it)?.addon?.logo }.filter { !Images.has(it, Images.Kind.LOGO) && askedLogos.add(it) }
    if (missing.isEmpty()) return
    netIo.execute {
        val got = missing.count { uri -> runCatching { Images.file(uri, Images.Kind.LOGO) }.isSuccess }
        if (got > 0) ui.post { refreshSettings(ADDON_ROWS) }
    }
}

/** Redraws the section page holding [row] for news that arrives later (a download, an update), only if it's on screen. */
internal fun MainActivity.refreshSettings(row: Int) {
    if (settingsScrim.visibility != View.VISIBLE || shownSection != row / SECTION) return
    val at = (0 until settingsList.childCount).firstOrNull { settingsList.getChildAt(it).hasFocus() } ?: (row % SECTION)
    renderSettings(row / SECTION * SECTION + at)
}

internal fun MainActivity.themeName() = when (prefs.getString(K.THEME, "light")) {
    "light" -> getString(R.string.setting_theme_light)
    "dark" -> getString(R.string.setting_theme_dark)
    "glass" -> getString(R.string.setting_theme_glass)
    else -> getString(R.string.setting_theme_auto)
}

internal fun MainActivity.screensaverValue() = when {
    downloading -> getString(R.string.screensaver_downloading)
    !galleryOn() -> screensaverName()
    else -> photoScreensaver()?.let { wallpaperName(it) } ?: getString(R.string.screensaver_gallery_label)
}

internal fun MainActivity.updatesValue() = when {
    updating -> getString(R.string.updates_updating)
    checking -> getString(R.string.updates_checking)
    availableUpdate() != null -> getString(R.string.updates_available_prompt, availableUpdate()!!)
    notesUnseen() -> getString(R.string.whats_new_title, myVersion())
    else -> getString(R.string.updates_up_to_date, myVersion())
}

/** Each section's rows, in the order of its *_ROW constants. */
internal fun MainActivity.sectionRows(section: Int): List<Triple<String, String, () -> Unit>> = when (section) {
    LOOK_SECTION -> {
        val theme = prefs.getString(K.THEME, "light")!!
        val weather = prefs.getString(K.WEATHER, "off")!!
        val unit = if (weather == "off") prefs.getString(K.WX_UNIT, "c")!! else weather
        listOf(
            page(getString(R.string.setting_theme), themeName()) {
                prefs.edit().putString(K.THEME, mapOf("light" to "dark", "dark" to "glass", "glass" to "auto", "auto" to "light")[theme])
                    .putInt(K.REOPEN_ROW, THEME_ROW).apply()
                recreate() // colours are applied once per activity; reopen the panel after
            },
            page(getString(R.string.setting_light_wallpaper), wallpaperName(currentWallpaper(false))) { pickWallpaper(false) },
            page(getString(R.string.setting_dark_wallpaper), wallpaperName(currentWallpaper(true))) { pickWallpaper(true) },
            page(getString(R.string.setting_photo_fade), toggle(wallpaperFade())) {
                prefs.edit().putBoolean(K.WP_FADE, !wallpaperFade()).apply()
                loadWallpaper()
                renderSettings(FADE_ROW)
            },
            page(getString(R.string.setting_folder_photo_changes), photoEveryName(photoEvery())) { pickPhotoEvery() },
            // "weather" is off/c/f as before; "wx_unit" keeps the unit while it's off.
            page(getString(R.string.setting_weather), toggle(weather != "off")) {
                if (weather == "off") prefs.edit().putString(K.WEATHER, unit).apply()
                else prefs.edit().putString(K.WX_UNIT, weather).putString(K.WEATHER, "off").apply()
                showWeather()
                ui.removeCallbacks(weatherTick)
                ui.post(weatherTick)
                renderSettings(WEATHER_ROW)
            },
            page(getString(R.string.weather_units), if (unit == "f") "°F" else "°C") {
                val next = if (unit == "f") "c" else "f"
                prefs.edit().putString(K.WX_UNIT, next).putString(K.WEATHER, if (weather == "off") "off" else next).apply()
                showWeather()
                renderSettings(WEATHER_UNITS_ROW)
            },
            page(getString(R.string.weather_location), if (manualCity()) prefs.getString(K.GEO_CITY, null) ?: "" else getString(R.string.automatic)) { pickCity() },
            page(getString(R.string.setting_clock), clockName(prefs.getString(K.CLOCK_24, "auto")!!)) {
                val next = mapOf("auto" to "24", "24" to "12", "12" to "auto")[prefs.getString(K.CLOCK_24, "auto")] ?: "auto"
                prefs.edit().putString(K.CLOCK_24, next).apply()
                applyClock()
                renderSettings(CLOCK_ROW)
            },
            page(getString(R.string.setting_night), toggle(prefs.getBoolean(K.NIGHT, false))) {
                prefs.edit().putBoolean(K.NIGHT, !prefs.getBoolean(K.NIGHT, false)).apply()
                applyNight()
                renderSettings(NIGHT_ROW)
            },
            page(getString(R.string.setting_night_from), hourName(nightFrom())) { pickNightHour(K.NIGHT_FROM, R.string.setting_night_from, NIGHT_FROM_ROW) },
            page(getString(R.string.setting_night_to), hourName(nightTo())) { pickNightHour(K.NIGHT_TO, R.string.setting_night_to, NIGHT_TO_ROW) },
        ) + listOfNotNull(
            // A phone or tablet: its own status bar over the home screen, or not (the home screen has its clock).
            if (!handheld()) null else page(getString(R.string.setting_status_bar), toggle(prefs.getBoolean(K.STATUS_BAR, false))) {
                prefs.edit().putBoolean(K.STATUS_BAR, !prefs.getBoolean(K.STATUS_BAR, false)).apply()
                applyStatusBar()
                renderSettings(STATUS_BAR_ROW)
            },
        )
    }
    APPS_SECTION -> {
        val sort = prefs.getString(K.SORT, "manual")!!
        val rowHidden = rowHiddenSet().count { pkg -> apps.any { it.pkg == pkg } } // uninstalled ones don't count
        val names = rowNames()
        val colour = colourIcons()
        listOf(
            page(getString(R.string.edit_apps_row)) { closeLauncherSettings(); startEdit(grab = null) },
            page(getString(R.string.setting_sort_apps), if (sort == "manual") getString(R.string.setting_sort_manual) else getString(R.string.setting_sort_usage)) {
                prefs.edit().putString(K.SORT, if (sort == "manual") "usage" else "manual").apply()
                renderApps()
                renderSettings(SORT_ROW)
            },
            page(getString(R.string.setting_apps_in_row), if (rowHidden == 0) getString(R.string.apps_in_row_all) else getString(R.string.apps_in_row_count, apps.size - rowHidden, apps.size)) { pickRowApps() },
            page(getString(R.string.row_position), getString(ROW_ALIGN[rowAlign()]!!.first)) {
                prefs.edit().putString(K.ROW_ALIGN, ROW_ALIGN.keys.let { it.elementAt((it.indexOf(rowAlign()) + 1) % it.size) }).apply()
                alignRow()
                renderSettings(ALIGN_ROW)
            },
            page(getString(R.string.setting_app_names), when (names) {
                "always" -> getString(R.string.app_names_always)
                "never" -> getString(R.string.app_names_never)
                else -> getString(R.string.app_names_focus)
            }) {
                prefs.edit().putString(K.NAMES, mapOf("focus" to "always", "always" to "never", "never" to "focus")[names]).apply()
                renderApps()
                renderSettings(NAMES_ROW)
            },
            page(getString(R.string.setting_icon_style), if (colour) getString(R.string.icon_style_colour) else getString(R.string.icon_style_monochrome)) {
                prefs.edit().putBoolean(K.COLOUR_ICONS, !colour).apply()
                renderApps()
                renderSettings(ICONS_ROW)
            },
            page(getString(R.string.setting_lock_layout), toggle(lockOn())) {
                if (!lockOn()) return@page setLock()
                prefs.edit().remove(K.LOCK_PIN).apply()
                renderSettings(LOCK_ROW)
            },
        )
    }
    WATCH_SECTION -> {
        val hidden = prefs.getStringSet(K.HIDDEN_WATCH, emptySet())!!.size
        val cwOff = prefs.getStringSet(K.CW_OFF, emptySet())!!.count { pkg -> apps.any { it.pkg == pkg } }
        listOf(
            page(getString(R.string.setting_cw_apps), if (cwOff == 0) getString(R.string.cw_apps_all_on) else getString(R.string.cw_apps_count_off, cwOff)) { pickWatchSources() },
            page(getString(R.string.setting_restore_hidden_shows), if (hidden == 0) getString(R.string.hidden_shows_none) else getString(R.string.hidden_shows_count, hidden)) {
                prefs.edit().remove(K.HIDDEN_WATCH).apply()
                watchKey = null
                refreshWatchNext()
                renderSettings(HIDDEN_ROW)
            },
            // What's playing in the Play queue: allowed only by the system (adb on most TVs; see NowPlaying).
            page(getString(R.string.setting_now_playing), getString(when {
                listenerAllowed() -> R.string.state_on
                lowRamTv() -> R.string.now_playing_low_ram_short
                else -> R.string.now_playing_needs_access
            })) {
                if (listenerAllowed()) Toast.makeText(this, getString(R.string.now_playing_on), Toast.LENGTH_SHORT).show()
                else allowListener { renderSettings(WATCH_SECTION * SECTION) }
            },
            // Where films and series come from (Cinemeta, Nuvio, an added catalog), and TMDB completing them.
            page(getString(R.string.setting_cinemeta), sourcesValue()) { configureCinemeta() },
            // How a film or episode starts: its streams tried in turn, or picked by hand from one list of them all.
            page(getString(R.string.setting_stream_choice), getString(if (manualStreams()) R.string.stream_choice_manual else R.string.stream_choice_auto)) {
                prefs.edit().putBoolean(K.STREAM_MANUAL, !manualStreams()).apply()
                renderSettings(WATCH_SECTION * SECTION + 4)
            },
            // The quality tried first: the highest, or one the TV shows (a 1080p one: 1080p down, then those above).
            page(getString(R.string.setting_stream_quality), qualityName(preferredQuality(prefs))) {
                val now = QUALITIES.indexOf(preferredQuality(prefs)).coerceAtLeast(0)
                prefs.edit().putInt(K.STREAM_QUALITY, QUALITIES[(now + 1) % QUALITIES.size]).apply()
                renderSettings(WATCH_SECTION * SECTION + 5)
            },
            // Quick start: the first working stream in the preferred quality plays; the other sources go on looking.
            // Not with the highest quality: that one waits for every source (see quickStart).
            page(getString(R.string.setting_fast_start),
                if (preferredQuality(prefs) == 0) getString(R.string.fast_start_not_highest) else toggle(prefs.getBoolean(K.FAST_START, true))) {
                if (preferredQuality(prefs) == 0) return@page Toast.makeText(this, getString(R.string.fast_start_pick_quality), Toast.LENGTH_LONG).show()
                prefs.edit().putBoolean(K.FAST_START, !prefs.getBoolean(K.FAST_START, true)).apply()
                renderSettings(WATCH_SECTION * SECTION + 6)
            },
            // Films' and episodes' streams in the TV's language only (the TMDB language's), or all of them.
            page(getString(R.string.setting_stream_language), toggle(streamLanguage(prefs) != null)) {
                prefs.edit().putBoolean(K.STREAM_LANG_ONLY, streamLanguage(prefs) == null).apply()
                renderSettings(WATCH_SECTION * SECTION + 7)
            },
            // Streams that say no language (and whose plugin doesn't either): hidden, with the TV's language only on.
            page(getString(R.string.setting_hide_unknown_language), toggle(prefs.getBoolean(K.HIDE_UNKNOWN_LANG, false))) {
                prefs.edit().putBoolean(K.HIDE_UNKNOWN_LANG, !prefs.getBoolean(K.HIDE_UNKNOWN_LANG, false)).apply()
                renderSettings(WATCH_SECTION * SECTION + 8)
            },
            // Subtitles: off unless the stream forces them (a foreign line in a dubbed film), or on in every stream.
            page(getString(R.string.setting_subtitles_on), toggle(prefs.getBoolean(K.SUBTITLES_ON, false))) {
                prefs.edit().putBoolean(K.SUBTITLES_ON, !prefs.getBoolean(K.SUBTITLES_ON, false)).apply()
                renderSettings(WATCH_SECTION * SECTION + 9)
            },
            // Films not out at home yet (in cinemas, or to come, as TMDB says for sure): left out of Spotlight and
            // the apps' rows. Needs TMDB's key.
            page(getString(R.string.setting_hide_unreleased), toggle(hideUnreleased())) {
                prefs.edit().putBoolean(K.HIDE_UNRELEASED, !hideUnreleased()).apply()
                tmdbTrending = emptyList()
                watchKey = null
                renderSettings(WATCH_SECTION * SECTION + 10)
            },
        )
    }
    // The TV's own side: its inputs (HDMI and the like) and its channels, with their logos and guide.
    TV_SECTION -> listOf(
        page(getString(R.string.setting_inputs_mode), if (inputsMode() == "apps") getString(R.string.inputs_mode_apps) else getString(R.string.inputs_mode_menu)) {
            val next = if (inputsMode() == "apps") "menu" else "apps"
            prefs.edit().putString(K.INPUTS_MODE, next).apply()
            applyInputsMode()
            renderSettings(INPUTS_MODE_ROW)
        },
        page(getString(R.string.setting_inputs_in_apps), toggle(inputsInApps())) {
            prefs.edit().putBoolean(K.INPUTS_IN_APPS, !inputsInApps()).apply()
            applyInputsMode()
            renderSettings(INPUTS_IN_APPS_ROW)
        },
        page(getString(R.string.setting_channel_logos), toggle(channelLogosOn())) {
            prefs.edit().putBoolean(K.CHANNEL_LOGOS, !channelLogosOn()).apply()
            watchKey = null
            renderSettings(CHANNEL_LOGOS_ROW)
        },
        page(getString(R.string.setting_channel_logos_update)) {
            Toast.makeText(this, getString(R.string.searching), Toast.LENGTH_SHORT).show()
            updateChannelLogos(null) { n ->
                watchKey = null
                Toast.makeText(this, if (n == null) getString(R.string.toast_channel_logos_failed)
                    else getString(R.string.toast_channel_logos_updated, n), Toast.LENGTH_LONG).show()
            }
        },
        page(getString(R.string.setting_epg), epgUrl()?.let { Uri.parse(it).host } ?: getString(R.string.state_off)) { configureEpg() },
        page(getString(R.string.setting_play_channels), toggle(playChannelsOnOpen())) {
            prefs.edit().putBoolean(K.PLAY_CHANNELS, !playChannelsOnOpen()).apply()
            renderSettings(PLAY_CHANNELS_ROW)
        },
    )
    ADDONS_SECTION -> addonRows()
    SPOTLIGHT_SECTION -> spotlightRows()
    SCREENSAVER_SECTION -> listOf(
        page(getString(R.string.setting_screensaver), screensaverValue()) { pickScreensaver() },
        page(getString(R.string.setting_test_screensaver)) { testScreensaver() },
        page(getString(R.string.setting_screensaver_timing)) { openScreensaverSettings() },
    )
    else -> listOfNotNull(
        page(getString(R.string.setup_home)) { closeLauncherSettings(); startHomeSetup() },
        page(getString(R.string.setting_home_button), toggle(homeServiceOn())) { toggleHomeButton() },
        page(getString(R.string.setting_default_launcher), if (isDefaultHome()) getString(R.string.plume_press_change) else getString(R.string.default_launcher_set_prompt)) { makeDefaultHome() },
        page(getString(R.string.setting_updates), updatesValue()) { showUpdates() },
        page(getString(R.string.setting_remote), toggle(remoteOn())) { toggleRemote() },
        if (!remoteOn()) null else page(getString(R.string.remote_show)) { showRemoteAddress() },
        page(getString(R.string.setting_backup)) { showBackup() },
        page(getString(R.string.setting_problems), Problems.recent().size.let { if (it == 0) getString(R.string.problems_none) else "$it" }) { showProblems() },
        page(getString(R.string.setting_guide), SUBMENU) { showGuide() }, // the user guide, in the TV's language
        page(getString(R.string.setting_about), myVersion()) { showAbout() },
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) null
        else page(getString(R.string.preview_home_setup)) { closeLauncherSettings(); previewHomeSetup() },
    )
}

/** The Spotlight section: what it shows, the user's TMDB list, films or series, and how often it moves on. */
internal fun MainActivity.spotlightRows(): List<Triple<String, String, () -> Unit>> {
    val type = prefs.getString(K.TMDB_TYPE, "all") ?: "all"
    val typeName = when (type) {
        "movie" -> getString(R.string.tmdb_type_movie)
        "tv" -> getString(R.string.tmdb_type_tv)
        else -> getString(R.string.tmdb_type_all)
    }
    val every = spotlightEvery()
    return listOf(
        // What Spotlight shows: Cinemeta's popular titles, one of TMDB's ready-made lists, or the user's own public TMDB list (a watchlist).
        page(getString(R.string.setting_tmdb_source), sourceName(spotlightSource())) { pickSpotlightSource() },
        page(getString(R.string.setting_tmdb_list), prefs.getString(K.TMDB_LIST, null) ?: getString(R.string.tmdb_list_none)) { tmdbListFromPhone() },
        page(getString(R.string.setting_tmdb_media_type), typeName) {
            val types = listOf("all" to getString(R.string.tmdb_type_all), "movie" to getString(R.string.tmdb_type_movie), "tv" to getString(R.string.tmdb_type_tv))
            showPage(getString(R.string.setting_tmdb_media_type), types.map { (k, name) ->
                page(name, if (k == type) CHOSEN else "") {
                    prefs.edit().putString(K.TMDB_TYPE, k).apply()
                    tmdbTrending = emptyList()
                    loadWallpaper()
                    renderSettings(TMDB_TYPE_ROW)
                }
            }, types.indexOfFirst { it.first == type }) { renderSettings(TMDB_TYPE_ROW) }
        },
        page(getString(R.string.setting_tmdb_cycle), cycleName(every)) {
            showPage(getString(R.string.setting_tmdb_cycle), SPOTLIGHT_EVERY.map { n ->
                page(cycleName(n), if (n == every) CHOSEN else "") {
                    prefs.edit().putInt(K.TMDB_CYCLE, n).apply()
                    restartSpotlightTick()
                    renderSettings(TMDB_CYCLE_ROW)
                }
            }, SPOTLIGHT_EVERY.indexOf(every)) { renderSettings(TMDB_CYCLE_ROW) }
        },
    )
}

/**
 * TMDB on the content catalog's page, after its catalogs (see tmdbRow): Complete with TMDB (names, what it's about
 * and episodes in the TV's language), the key, the language, and the services and apps on a title's page.
 */
internal fun MainActivity.tmdbContentRows(): List<Triple<String, String, () -> Unit>> {
    val hasKey = !prefs.getString(K.TMDB_API_KEY, null).isNullOrBlank()
    val lang = tmdbLang()
    val offSet = prefs.getStringSet(K.TMDB_DEFAULT_APPS, emptySet()) ?: emptySet()
    val availableApps = apps.filter { !it.pkg.startsWith(HDMI) && !it.pkg.startsWith(STREMIO) && it.pkg != CW_PKG }
    val activeCount = availableApps.count { it.pkg !in offSet }
    return listOf(
        page(getString(R.string.setting_tmdb_enrich), if (!hasKey) getString(R.string.tmdb_api_key_empty) else toggle(tmdbEnrich())) {
            if (!hasKey) return@page configureTmdbApiKey()
            prefs.edit().putBoolean(K.TMDB_ENRICH, !tmdbEnrich()).apply()
            addonsChanged()
            configureCinemeta(tmdbRow(0))
        },
        page(getString(R.string.setting_tmdb_api_key), if (hasKey) getString(R.string.tmdb_api_key_set) else getString(R.string.tmdb_api_key_empty)) {
            configureTmdbApiKey()
        },
        page(getString(R.string.setting_tmdb_language), lang) {
            val langs = TmdbClient.LANGUAGES
            showPage(getString(R.string.setting_tmdb_language), langs.map { l ->
                page(l, if (l == lang) CHOSEN else "") {
                    prefs.edit().putString(K.TMDB_LANG, l).apply()
                    tmdbTrending = emptyList()
                    loadWallpaper()
                    addonsChanged() // Nuvio's catalog and the titles completed by TMDB, in the new language
                    configureCinemeta(tmdbRow(2))
                }
            }, langs.indexOf(lang)) { configureCinemeta(tmdbRow(2)) }
        },
        page(getString(R.string.setting_tmdb_providers), toggle(providersOn())) {
            prefs.edit().putBoolean(K.TMDB_PROVIDERS, !providersOn()).apply()
            spotlightItem()?.let { showProviders(it) }
            configureCinemeta(tmdbRow(3))
        },
        page(getString(R.string.setting_tmdb_default_apps), if (offSet.isEmpty()) getString(R.string.cw_apps_all_on) else "$activeCount") {
            val appChoices = availableApps.map { it.label to it.pkg }
            toggles(getString(R.string.setting_tmdb_default_apps), appChoices, K.TMDB_DEFAULT_APPS, getString(R.string.state_on), getString(R.string.state_off)) {
                spotlightApps = null
                renderSpotlight()
                configureCinemeta(tmdbRow(4))
            }
        },
    )
}

/** A preferred quality's name: "Highest", or its height ("1080p"). */
internal fun MainActivity.qualityName(height: Int) = if (height == 0) getString(R.string.stream_quality_max) else "${height}p"

internal fun MainActivity.cycleName(seconds: Int) = if (seconds == 0) getString(R.string.tmdb_cycle_off) else getString(R.string.tmdb_cycle_seconds, seconds)

/** A new TMDB key turns Spotlight on, as both wallpapers. */
internal fun MainActivity.saveTmdbKey(key: String) {
    prefs.edit().putString(K.TMDB_API_KEY, key).apply()
    tmdbTrending = emptyList()
    setWallpaper(false, "tmdb:trending")
    setWallpaper(true, "tmdb:trending")
}

/**
 * The TV guide's address (an XMLTV file, see Epg): typed with the remote or sent from a phone. Saving fetches it at
 * once; Clear turns the guide off.
 */
internal fun MainActivity.configureEpg() =
    textPage(getString(R.string.setting_epg), epgUrl() ?: "", getString(R.string.epg_hint), getString(R.string.epg_intro), back = { renderSettings(EPG_ROW) }) { typed ->
        listOf(
            page(getString(R.string.from_phone)) { epgFromPhone() },
            page(getString(R.string.dialog_save)) {
                saveEpgUrl(typed())
                renderSettings(EPG_ROW)
            },
            page(getString(R.string.dialog_clear)) {
                saveEpgUrl(null)
                renderSettings(EPG_ROW)
            },
        )
    }

/** A box for a web address, typed with the remote: framed like the other settings boxes. */
internal fun MainActivity.urlField(value: String, hint: String): EditText =
    EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(fg)
        setHintTextColor(if (dark) 0x66FFFFFF else 0x66000000)
        isSingleLine = true
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        filters = arrayOf(android.text.InputFilter.LengthFilter(1024 * 1024))
        setPadding(dp(14), dp(10), dp(14), dp(10))
        val bg = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(field)
            setStroke(dp(1), fieldEdge())
        }
        background = bg
        setOnFocusChangeListener { _, focused -> bg.setStroke(if (focused) dp(2) else dp(1), if (focused) fg else fieldEdge()) }
    }

/** What a page's box (see textPage) is for: a web address, a name (capitals for each word or the first), or a key. */
internal enum class Typing { ADDRESS, NAME, SENTENCE, KEY }

/**
 * A page with a box to type in with the remote: [value] in it ([hint] while it's empty), [intro] under it, then the
 * rows [rows] makes, which read what's typed (trimmed) when picked: Save, Clear, on the phone… The box has the focus;
 * Down goes on to the rows.
 */
internal fun MainActivity.textPage(title: String, value: String, hint: String, intro: String?, typing: Typing = Typing.ADDRESS,
                                   back: () -> Unit, rows: (typed: () -> String) -> List<Triple<String, String, () -> Unit>>) {
    val input = urlField(value, hint).apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT or when (typing) {
            Typing.ADDRESS -> android.text.InputType.TYPE_TEXT_VARIATION_URI
            Typing.NAME -> android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            Typing.SENTENCE -> android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            Typing.KEY -> android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        filters = arrayOf(android.text.InputFilter.LengthFilter(1024 * 1024))
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(input)
        intro?.let { addView(label(it, 12f).apply { alpha = 0.6f; maxLines = 4; setPadding(dp(4), dp(10), dp(4), 0) }) }
    }
    showPage(title, rows { input.text?.toString()?.trim().orEmpty() }, top = box, back = back)
    remoteField = input // a phone on remote access can type into this box
    downFrom(input)
}

/** Focuses [input], the box at the top of the page, with Down going on to the first row under it. */
internal fun MainActivity.downFrom(input: EditText) {
    // The TV keyboard keeps Down for itself while the box has focus, even with the keyboard put away.
    val first = settingsList.getChildAt(1)
    input.setOnKeyListener { _, key, e -> (key == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN).also { if (it) first.requestFocus() } }
    input.requestFocus()
}

/** Saves the guide's address (null or blank: no guide) and fetches it, telling how it went. */
internal fun MainActivity.saveEpgUrl(url: String?) {
    val clean = url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    prefs.edit().apply { if (clean == null) remove(K.EPG_URL) else putString(K.EPG_URL, clean) }.apply()
    watchKey = null
    if (clean == null) return
    Toast.makeText(this, getString(R.string.epg_downloading), Toast.LENGTH_SHORT).show()
    updateEpg(tmdbLastFocusApp) { n ->
        Toast.makeText(this, getString(when (n) {
            null -> R.string.toast_epg_failed
            0 -> R.string.toast_epg_old
            else -> R.string.toast_epg_updated
        }, n ?: 0), Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.configureTmdbApiKey() =
    textPage(getString(R.string.setting_tmdb_api_key), prefs.getString(K.TMDB_API_KEY, "") ?: "", getString(R.string.tmdb_api_key_hint), null,
        Typing.KEY, back = { configureCinemeta(tmdbRow(1)) }) { typed ->
        listOf(
            // Typing a 32-character key (or a 200-character token) with a remote is no fun: paste it on a phone.
            page(getString(R.string.from_phone)) { tmdbKeyFromPhone() },
            page(getString(R.string.dialog_save)) {
                typed().takeIf { it.isNotBlank() }?.let { saveTmdbKey(it) }
                configureCinemeta(tmdbRow(1))
            },
            page(getString(R.string.dialog_clear)) {
                prefs.edit().remove(K.TMDB_API_KEY).apply()
                tmdbTrending = emptyList()
                spotlightIo.execute { Images.clear(Images.Kind.BACKDROP) } // TMDB's backdrops: Spotlight goes on with Cinemeta's
                if (currentWallpaper() == "tmdb:trending") loadWallpaper()
                configureCinemeta(tmdbRow(1))
            },
        )
    }

internal fun MainActivity.page(title: String, value: String = "", action: () -> Unit) = Triple(title, value, action)

/**
 * The row of the page in the panel the focus is on (0 for its first row, after the box on top), or null when it's
 * elsewhere: a page built again by itself (an answer came in) keeps the focus where it was.
 */
/** Whether the page titled [title] is still the one in the panel (an answer for it may show it again). */
internal fun MainActivity.pageShowing(title: String) =
    settingsScrim.visibility == View.VISIBLE && findViewById<TextView>(R.id.settingsTitle).text.toString() == title

/** Focuses the page's row [i] (0: its first, after the box on top). */
internal fun MainActivity.focusRow(i: Int) {
    val first = (0 until settingsList.childCount).firstOrNull { settingsList.getChildAt(it).hasOnClickListeners() } ?: return
    settingsList.getChildAt(first + i)?.requestFocus()
}

internal fun MainActivity.focusedRow(): Int? {
    val focused = currentFocus ?: return null
    val i = settingsList.indexOfChild(focused).takeIf { it >= 0 } ?: return null
    val first = (0 until settingsList.childCount).firstOrNull { settingsList.getChildAt(it).hasOnClickListeners() } ?: return null
    return (i - first).takeIf { it >= 0 }
}

/** [panes]: [top] and the rows as panes of glass (a title's or a channel's page in the Glass theme), see [glassPane]. */
internal fun MainActivity.showPage(title: String, allRows: List<Triple<String, String, () -> Unit>>, focus: Int = 0, top: View? = null,
                                  panes: Boolean = false, back: (() -> Unit)?) {
    val rows = handheldRows(allRows) // a phone or tablet: no rows for doing it on a phone
    // The page left keeps the row it had focus on; Back to a page puts focus there again, wherever it was.
    findViewById<TextView>(R.id.settingsTitle).text?.toString()?.let { left -> focusedRow()?.let { pageFocus[left] = it } }
    val focus = if (goingBack) pageFocus[title]?.takeIf { it in rows.indices } ?: focus else focus
    pageExit?.invoke()
    pageExit = null
    pageSides = null
    pageBack = back
    shownSection = -1 // renderSettings sets it again for section pages
    findViewById<TextView>(R.id.settingsTitle).text = title
    settingsList.removeAllViews()
    // [top] stands on the panel as the page's title does, with no pane round it, even when the rows are panes.
    top?.let { settingsList.addView(it) }
    val first = settingsList.childCount
    rows.forEach { (name, value, action) ->
        settingsList.addView(settingsRow(name, value, panes, action), LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply {
            topMargin = if (panes) dp(6) else dp(4)
        })
    }
    // Keep focus inside the panel: Up from the first row and Down from the last go nowhere.
    (settingsList.parent as View).scrollTo(0, 0)
    if (rows.isEmpty()) return noteRemotePage(title, rows, back) // a page that's all [top], like the PIN boxes, focuses itself
    if (top == null) settingsList.getChildAt(first).let { it.nextFocusUpId = it.id }
    settingsList.getChildAt(first + rows.size - 1).let { it.nextFocusDownId = it.id }
    if (focus >= 0) settingsList.getChildAt(first + focus.coerceIn(0, rows.size - 1)).requestFocus()
    noteRemotePage(title, rows, back)
}

/** The panel's page, for a phone on remote access (see Remote). A new page clears the box and the PIN of the last one. */
internal fun MainActivity.noteRemotePage(title: String, rows: List<Triple<String, String, () -> Unit>>, back: (() -> Unit)?) {
    remoteTitle = title
    remoteRows = rows
    remoteBack = back
    remoteField = null
    remotePinSubmit = null
}

// ---------- layout lock ----------
// A PIN in front of Zephyr TV settings and everything that changes the home screen; apps still open as usual.
// It keeps the layout safe from small hands, it doesn't keep apps from kids: voice search and Android
// settings get past it, so it's called a layout lock, not parental controls.

internal fun MainActivity.lockOn() = prefs.contains(K.LOCK_PIN)

internal fun MainActivity.locked() = lockOn() && SystemClock.elapsedRealtime() > unlockedUntil

/** Runs [then] at once when unlocked, otherwise after the right PIN. */
internal fun MainActivity.unlock(then: () -> Unit) {
    if (!locked()) return then()
    showPanel()
    pinPage(getString(R.string.pin_enter_title), back = null) { pin ->
        val now = SystemClock.elapsedRealtime()
        if (now < pinWaitUntil) return@pinPage getString(R.string.pin_too_many_tries_wait, (pinWaitUntil - now) / 1000 + 1)
        if (!pinMatches(prefs.getString(K.LOCK_PIN, null)!!, pin)) {
            if (++pinFails < PIN_TRIES) return@pinPage getString(R.string.pin_wrong)
            pinFails = 0
            pinWaitUntil = now + 30_000L
            return@pinPage getString(R.string.pin_too_many_tries_wait, 30)
        }
        pinFails = 0
        unlockedUntil = now + UNLOCKED_MS
        closeLauncherSettings()
        then()
        null
    }
}

internal fun MainActivity.setLock(): Unit = pinPage(getString(R.string.pin_choose_title), { renderSettings(LOCK_ROW) }) { first ->
    pinPage(getString(R.string.pin_enter_again_title), { renderSettings(LOCK_ROW) }) { again ->
        if (again != first) {
            Toast.makeText(this, getString(R.string.pin_mismatch), Toast.LENGTH_LONG).show()
            setLock()
            return@pinPage null
        }
        prefs.edit().putString(K.LOCK_PIN, hashPin(first)).apply()
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCKED_MS // no PIN straight away for the one who set it
        renderSettings(LOCK_ROW)
        Toast.makeText(this, getString(R.string.layout_locked_toast), Toast.LENGTH_LONG).show()
        null
    }
    null
}

/**
 * Four digit boxes: ▲ ▼ change a digit, ◀ ▶ move between boxes, number keys type, OK enters.
 * [done] gets the PIN and returns null when it's accepted, or a message to show before trying again.
 */
internal fun MainActivity.pinPage(title: String, back: (() -> Unit)?, done: (String) -> String?) {
    val digits = arrayOfNulls<Int>(4)
    var at = 0
    val boxes = List(4) { label("", 24f).apply { gravity = Gravity.CENTER } }
    val status = label("", 13f).apply { gravity = Gravity.CENTER; alpha = 0.6f }
    val row = LinearLayout(this).apply {
        id = View.generateViewId()
        gravity = Gravity.CENTER
        isFocusable = true
        boxes.forEach { addView(it, LinearLayout.LayoutParams(dp(48), dp(56)).apply { marginStart = dp(6); marginEnd = dp(6) }) }
    }
    fun draw() = boxes.forEachIndexed { i, b ->
        // Only the box being changed shows its digit.
        b.text = if (digits[i] == null) "" else if (i == at) digits[i].toString() else "•"
        b.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(field)
            setStroke(if (i == at) dp(2) else dp(1), if (i == at) fg else fieldEdge())
        }
    }
    fun submit() {
        val error = done(digits.joinToString("")) ?: return
        digits.fill(null)
        at = 0
        draw()
        status.text = error
        ObjectAnimator.ofFloat(row, View.TRANSLATION_X, 0f, -dp(12).toFloat(), dp(12).toFloat(), -dp(8).toFloat(), dp(8).toFloat(), 0f).setDuration(300).start()
    }
    row.setOnKeyListener { _, code, e ->
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }
        if (digit == null && code !in PIN_KEYS) return@setOnKeyListener false // Back still leaves
        if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> digits[at] = ((digits[at] ?: 0) + 1) % 10
            KeyEvent.KEYCODE_DPAD_DOWN -> digits[at] = ((digits[at] ?: 0) + 9) % 10
            KeyEvent.KEYCODE_DPAD_LEFT -> at = maxOf(0, at - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (digits[at] != null) at = minOf(3, at + 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (digits.all { it != null }) return@setOnKeyListener true.also { submit() }
                else if (digits[at] != null) at = minOf(3, at + 1)
            else -> {
                digits[at] = digit
                if (at == 3) return@setOnKeyListener true.also { draw(); submit() }
                at++
            }
        }
        draw()
        true
    }
    draw()
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(16), 0, dp(8))
        addView(row)
        addView(status.apply { setPadding(0, dp(12), 0, 0) })
        addView(label(getString(R.string.pin_instructions), 12f).apply {
            maxLines = 2
            gravity = Gravity.CENTER
            alpha = 0.6f
            setPadding(0, dp(8), 0, 0)
        })
        if (lockOn()) addView(label(getString(R.string.pin_forgot_notice), 12f).apply {
            gravity = Gravity.CENTER
            alpha = 0.6f
            maxLines = 3
            setPadding(0, dp(24), 0, 0)
        })
    }
    // A phone or tablet: its number keyboard types the digits, in a box out of sight; a tap on the boxes brings it up.
    val typed = if (!handheld()) null else EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        alpha = 0f
        isCursorVisible = false
        background = null
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val t = s?.toString().orEmpty()
                for (i in 0 until 4) digits[i] = t.getOrNull(i)?.digitToIntOrNull()
                at = minOf(t.length, 3)
                draw()
                if (t.length == 4) post { submit(); setText("") }
            }
        })
    }
    typed?.let { t ->
        box.addView(t, LinearLayout.LayoutParams(dp(1), dp(1)))
        row.isClickable = true
        row.setOnClickListener { showKeyboard(t) }
    }
    showPage(title, emptyList(), top = box, back = back)
    remotePinSubmit = done // a phone on remote access can enter this PIN
    pinBox = row
    if (typed != null) showKeyboard(typed) else row.requestFocus()
}

/** [field] focused, with the device's keyboard up (a phone or tablet). */
internal fun MainActivity.showKeyboard(field: EditText) {
    field.requestFocus()
    field.post { getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(field, 0) }
}

/** A faint outline for input boxes: their fill alone sits too close to the panel's colour to find at a glance. */
internal fun MainActivity.fieldEdge() = (fg and 0xFFFFFF) or 0x40000000

/**
 * Glass: [v] as a pane over the photograph, like the cards on the home screen: the blurred photo (or the cards' tint,
 * when there is no photo), a gloss on its top half and a hairline rim. A band of light crosses it once as it
 * takes focus. Returns what focus does to it: the glass lightens and the rim brightens; the content stays white.
 */
internal fun MainActivity.glassPane(v: View, radius: Int): (Boolean) -> Unit {
    val slice = GlassSlice(this, radius.toFloat())
    val gloss = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x24FFFFFF, 0x08FFFFFF, 0x00FFFFFF)).apply {
        cornerRadius = radius.toFloat()
    }
    val rim = GradientDrawable().apply { cornerRadius = radius.toFloat(); setStroke(1, 0x1FFFFFFF) }
    val glint = GlintDrawable(radius.toFloat())
    v.background = LayerDrawable(arrayOf(slice, gloss))
    v.foreground = LayerDrawable(arrayOf(rim, glint))
    return { lit ->
        slice.lit = lit
        rim.setStroke(if (lit) dp(2) else 1, if (lit) 0x99FFFFFF.toInt() else 0x1FFFFFFF)
        if (lit) glint.play() else glint.cancel()
    }
}

/** [pane]: a pane of glass (see [glassPane]) instead of a plain row that inverts with focus. */
internal fun MainActivity.settingsRow(title: String, value: String, pane: Boolean = false, action: () -> Unit): View {
    val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0)).apply { cornerRadius = dp(8).toFloat() }
    val lit = if (pane) fg else onFg // the content's colour with focus
    return LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        background = bg
        val light = if (pane) glassPane(this, dp(8)) else null
        isFocusable = true
        isClickable = true
        // A name too long for the row scrolls while the row has focus (a stream's, an add-on's).
        val name = label(title, 14f).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
        }
        val toggled = value == TOGGLE_ON || value == TOGGLE_OFF
        val submenu = value == SUBMENU
        // The value gives way to the name: capped, so a long one ("Not set: press to enter") is shortened, not the name.
        val state = label(if (toggled || submenu) "" else value, 13f).apply {
            alpha = 0.6f
            maxWidth = dp(170)
            setPadding(dp(12), 0, 0, 0)
        }
        addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fun showSwitch(lit: Boolean) {
            if (childCount > 1) removeViewAt(1)
            addView(switch(value == TOGGLE_ON, lit), LinearLayout.LayoutParams(dp(36), dp(20)))
        }
        val arrow = ImageView(context).apply { setImageResource(R.drawable.ic_chevron_right); setColorFilter(fg) }
        when {
            toggled -> showSwitch(false)
            submenu -> addView(arrow, LinearLayout.LayoutParams(dp(20), dp(20)))
            else -> addView(state)
        }
        setOnClickListener { action() }
        setOnFocusChangeListener { _, focused ->
            if (light != null) light(focused) else bg.colors = if (focused) intArrayOf(fg, fg) else intArrayOf(0, 0)
            name.setTextColor(if (focused) lit else fg)
            name.isSelected = focused
            state.setTextColor(if (focused) lit else fg)
            state.alpha = if (focused) 0.75f else 0.6f
            if (toggled) showSwitch(focused && light == null) // redrawn in the inverted colours on the lit row
            arrow.setColorFilter(if (focused) lit else fg)
            for (idx in 0 until childCount) {
                val child = getChildAt(idx)
                if (child is ImageView && child !== arrow) {
                    if (child.drawable !is android.graphics.drawable.BitmapDrawable) {
                        child.setColorFilter(if (focused) lit else fg)
                    }
                }
            }
        }
    }
}

internal fun MainActivity.toggle(on: Boolean) = if (on) TOGGLE_ON else TOGGLE_OFF

/**
 * A mono switch for on/off rows: filled track with the knob right when on, outlined and left when off.
 * [lit] draws it for a focused row, whose fill is fg: then onFg is the ink and the knob takes the row's colour.
 */
internal fun MainActivity.switch(on: Boolean, lit: Boolean = false) = FrameLayout(this).apply {
    val ink = if (lit) onFg else fg
    val faint = ink and 0xFFFFFF or 0x66000000
    background = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        if (on) setColor(ink) else setStroke(dp(2), faint)
    }
    addView(View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (!on) faint else if (lit) fg else if (dark) 0xFF09090B.toInt() else 0xFFF7F8FA.toInt())
        }
    }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
    })
}

internal fun MainActivity.clockName(v: String) = getString(when (v) {
    "24" -> R.string.clock_24
    "12" -> R.string.clock_12
    else -> R.string.automatic
})

/** Whole hours, for when night mode starts or ends. */
internal fun MainActivity.pickNightHour(key: String, title: Int, row: Int) {
    val current = if (key == K.NIGHT_FROM) nightFrom() else nightTo()
    showPage(getString(title), (0 until 24).map { h ->
        page(hourName(h * 60), if (h * 60 == current) CHOSEN else "") {
            prefs.edit().putInt(key, h * 60).apply()
            applyNight()
            renderSettings(row)
        }
    }, current / 60) { renderSettings(row) }
}

/**
 * What Spotlight shows: "cinemeta" (Cinemeta's popular titles, the default, and the only one without a TMDB key), a
 * key of TmdbClient.CATEGORIES, or "list" (only once a list is set: see tmdbListFromPhone).
 */
internal fun MainActivity.spotlightSource(): String {
    if (prefs.getString(K.TMDB_API_KEY, null).isNullOrBlank()) return "cinemeta"
    val s = prefs.getString(K.TMDB_SOURCE, "cinemeta") ?: "cinemeta"
    return when {
        s == "list" && prefs.getString(K.TMDB_LIST, null) != null -> "list"
        s in TmdbClient.CATEGORIES -> s
        else -> "cinemeta"
    }
}

internal fun MainActivity.sourceName(source: String) = getString(when (source) {
    "cinemeta" -> R.string.tmdb_source_cinemeta
    "list" -> R.string.tmdb_source_list
    "trending_week" -> R.string.tmdb_source_trending_week
    "popular" -> R.string.tmdb_source_popular
    "top_rated" -> R.string.tmdb_source_top_rated
    "now_playing" -> R.string.tmdb_source_now_playing
    "upcoming" -> R.string.tmdb_source_upcoming
    "on_the_air" -> R.string.tmdb_source_on_the_air
    else -> R.string.tmdb_source_trending
})

/**
 * Spotlight's source: TMDB's lists, then the user's own. That one is chosen only once a list is set: picking it
 * without one asks for it first (on the phone), and the source changes when it arrives.
 */
internal fun MainActivity.pickSpotlightSource() {
    val current = spotlightSource()
    // TMDB's lists need its key: without one, Cinemeta alone.
    val sources = listOf("cinemeta") + if (prefs.getString(K.TMDB_API_KEY, null).isNullOrBlank()) emptyList() else TmdbClient.CATEGORIES.keys.toList() + "list"
    showPage(getString(R.string.setting_tmdb_source), sources.map { s ->
        page(sourceName(s), if (s == current) CHOSEN else "") {
            if (s == "list" && prefs.getString(K.TMDB_LIST, null) == null) return@page tmdbListFromPhone()
            prefs.edit().putString(K.TMDB_SOURCE, s).apply()
            tmdbTrending = emptyList()
            loadWallpaper()
            renderSettings(TMDB_SOURCE_ROW)
        }
    }, sources.indexOf(current)) { renderSettings(TMDB_SOURCE_ROW) }
}


/**
 * A row of two lines, for a stream: [info] (its name, quality, size) on top, scrolling when it's too long, and
 * [source] (the add-on, plugin or extension it's from) under it, in italics. Lit with focus as a settings row is.
 */
internal fun MainActivity.twoLineRow(info: String, source: String, action: () -> Unit): View {
    val bg = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
    val top = label(info, 14f).apply {
        ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSingleLine = true
        isSelected = handheld() // a finger gives no focus: every row scrolls
    }
    val under = label(source, 12f).apply { setTypeface(typeface, android.graphics.Typeface.ITALIC); alpha = 0.75f; setPadding(0, dp(2), 0, 0) }
    return LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = bg
        isFocusable = true
        isClickable = true
        addView(top)
        addView(under)
        setOnClickListener { action() }
        setOnFocusChangeListener { _, focused ->
            bg.setColor(if (focused) fg else 0)
            top.setTextColor(if (focused) onFg else fg)
            top.isSelected = focused || handheld()
            under.setTextColor(if (focused) onFg else fg)
        }
    }
}
