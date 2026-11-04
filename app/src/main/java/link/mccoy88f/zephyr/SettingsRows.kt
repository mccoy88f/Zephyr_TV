package link.mccoy88f.zephyr

/**
 * The settings panel's sections, and each row's address in them: section * SECTION + the row's place in it
 * (see renderSettings). A page drawn again goes back to the row it names.
 */
internal object SettingsRows {
    const val SECTION = 100
    const val LOOK_SECTION = 0
    const val APPS_SECTION = 1
    const val WATCH_SECTION = 2
    const val SPOTLIGHT_SECTION = 3
    const val SCREENSAVER_SECTION = 4
    const val SYSTEM_SECTION = 5
    const val TV_SECTION = 6 // listed after Continue Watching (see renderSettingsHome)
    const val ADDONS_SECTION = 7 // listed after TV
    const val THEME_ROW = LOOK_SECTION * SECTION
    const val LIGHT_WP_ROW = THEME_ROW + 1
    const val DARK_WP_ROW = THEME_ROW + 2
    const val FADE_ROW = THEME_ROW + 3
    const val PHOTO_EVERY_ROW = THEME_ROW + 4
    const val WEATHER_ROW = THEME_ROW + 5
    const val WEATHER_UNITS_ROW = THEME_ROW + 6
    const val CITY_ROW = THEME_ROW + 7
    const val CLOCK_ROW = THEME_ROW + 8
    const val NIGHT_ROW = THEME_ROW + 9
    const val NIGHT_FROM_ROW = THEME_ROW + 10
    const val NIGHT_TO_ROW = THEME_ROW + 11
    const val STATUS_BAR_ROW = THEME_ROW + 12 // phones and tablets only
    const val SORT_ROW = APPS_SECTION * SECTION + 1
    const val ROW_APPS_ROW = APPS_SECTION * SECTION + 2
    const val ALIGN_ROW = APPS_SECTION * SECTION + 3
    const val NAMES_ROW = APPS_SECTION * SECTION + 4
    const val ICONS_ROW = APPS_SECTION * SECTION + 5
    const val LOCK_ROW = APPS_SECTION * SECTION + 6
    const val PIN_ROW = APPS_SECTION * SECTION + 7 // only while a PIN is set
    const val CW_APPS_ROW = WATCH_SECTION * SECTION
    const val HIDDEN_ROW = WATCH_SECTION * SECTION + 1
    const val INPUTS_MODE_ROW = TV_SECTION * SECTION
    const val INPUTS_IN_APPS_ROW = TV_SECTION * SECTION + 1
    const val CHANNEL_LOGOS_ROW = TV_SECTION * SECTION + 2
    const val EPG_ROW = TV_SECTION * SECTION + 4
    const val PLAY_CHANNELS_ROW = TV_SECTION * SECTION + 5
    const val ADDON_ADD_ROW = ADDONS_SECTION * SECTION // the Stremio add-ons' section: Add, then one row per add-on
    const val ADDON_ROWS = ADDON_ADD_ROW + 1
    const val CINEMETA_ROW = WATCH_SECTION * SECTION + 3 // the content catalog, last of Content
    const val TMDB_SOURCE_ROW = SPOTLIGHT_SECTION * SECTION
    const val TMDB_LIST_ROW = TMDB_SOURCE_ROW + 1
    const val TMDB_TYPE_ROW = TMDB_SOURCE_ROW + 2
    const val TMDB_CYCLE_ROW = TMDB_SOURCE_ROW + 3
    const val SCREENSAVER_ROW = SCREENSAVER_SECTION * SECTION
    const val HOME_BUTTON_ROW = SYSTEM_SECTION * SECTION + 1 // after "Set up Home button"
    const val DEFAULT_HOME_ROW = HOME_BUTTON_ROW + 1
    const val UPDATE_ROW = HOME_BUTTON_ROW + 2
    const val REMOTE_ROW = UPDATE_ROW + 1 // Remote access, before Backup and restore (see backupRow)
}
