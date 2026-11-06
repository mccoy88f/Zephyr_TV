package link.mccoy88f.zephyr

/**
 * The settings panel's pages, and each row's address in them: page * SECTION + the row's place in it (see
 * renderSettings). A page drawn again goes back to the row it names. The first page lists HOME_SECTIONS; the other
 * pages are groups inside one of them, and Back from a group goes to the row that opened it (see PARENT_ROW).
 */
internal object SettingsRows {
    const val SECTION = 100

    // ---------- the first page's sections ----------
    const val HOME_SCREEN_SECTION = 8 // Home screen: Favourites, Continue Playing, Spotlight, TV inputs
    const val LOOK_SECTION = 0 // Look: theme, wallpapers, clock and weather, night mode, screensaver
    const val WATCH_SECTION = 2 // Films and series: catalogs, streams, playback, downloads, torrents
    const val TV_SECTION = 6 // TV channels and their guide
    const val ADDONS_SECTION = 7 // Stremio's add-ons (a page of Add-ons and plugins, see addonsAndPlugins)
    const val ACCOUNT_SECTION = 16 // Account and privacy: the account, WARP, the PIN's locks, remote access
    const val SYSTEM_SECTION = 5 // the remote and Home, updates, backup, problems, the guide, about

    // ---------- groups ----------
    const val APPS_SECTION = 1 // Home screen → Favourites
    const val CW_SECTION = 9 // Home screen → Continue Playing
    const val SPOTLIGHT_SECTION = 3 // Home screen → Spotlight
    const val INPUTS_SECTION = 10 // Home screen → TV inputs
    const val WALLPAPER_SECTION = 11 // Look → Wallpapers
    const val CLOCK_SECTION = 12 // Look → Clock and weather
    const val NIGHT_SECTION = 13 // Look → Night mode
    const val SCREENSAVER_SECTION = 4 // Look → Screensaver
    const val STREAMS_SECTION = 14 // Films and series → Streams
    const val PLAYBACK_SECTION = 15 // Films and series → Playback

    // Home screen
    const val HS_APPS_ROW = HOME_SCREEN_SECTION * SECTION
    const val HS_CW_ROW = HS_APPS_ROW + 1
    const val HS_SPOTLIGHT_ROW = HS_APPS_ROW + 2
    const val HS_INPUTS_ROW = HS_APPS_ROW + 3

    // Home screen → Favourites
    const val EDIT_ROW = APPS_SECTION * SECTION
    const val SORT_ROW = EDIT_ROW + 1
    const val ROW_APPS_ROW = EDIT_ROW + 2
    const val ALIGN_ROW = EDIT_ROW + 3
    const val NAMES_ROW = EDIT_ROW + 4
    const val ICONS_ROW = EDIT_ROW + 5

    // Home screen → Continue Playing
    const val CW_ROW = CW_SECTION * SECTION // Continue Playing on/off
    const val CW_APPS_ROW = CW_ROW + 1
    const val HIDDEN_ROW = CW_ROW + 2
    const val NOW_PLAYING_ROW = CW_ROW + 3

    // Home screen → Spotlight
    const val TMDB_SOURCE_ROW = SPOTLIGHT_SECTION * SECTION
    const val TMDB_LIST_ROW = TMDB_SOURCE_ROW + 1
    const val TMDB_TYPE_ROW = TMDB_SOURCE_ROW + 2
    const val TMDB_CYCLE_ROW = TMDB_SOURCE_ROW + 3

    // Home screen → TV inputs
    const val INPUTS_MODE_ROW = INPUTS_SECTION * SECTION
    const val INPUTS_IN_APPS_ROW = INPUTS_MODE_ROW + 1

    // Look
    const val THEME_ROW = LOOK_SECTION * SECTION
    const val LOOK_WALLPAPERS_ROW = THEME_ROW + 1
    const val LOOK_CLOCK_ROW = THEME_ROW + 2
    const val LOOK_NIGHT_ROW = THEME_ROW + 3
    const val LOOK_SCREENSAVER_ROW = THEME_ROW + 4
    const val STATUS_BAR_ROW = THEME_ROW + 5 // phones and tablets only

    // Look → Wallpapers
    const val LIGHT_WP_ROW = WALLPAPER_SECTION * SECTION
    const val DARK_WP_ROW = LIGHT_WP_ROW + 1
    const val FADE_ROW = LIGHT_WP_ROW + 2
    const val DRIFT_ROW = LIGHT_WP_ROW + 3
    const val PHOTO_EVERY_ROW = LIGHT_WP_ROW + 4

    // Look → Clock and weather
    const val CLOCK_ROW = CLOCK_SECTION * SECTION
    const val WEATHER_ROW = CLOCK_ROW + 1
    const val WEATHER_UNITS_ROW = CLOCK_ROW + 2
    const val CITY_ROW = CLOCK_ROW + 3

    // Look → Night mode
    const val NIGHT_ROW = NIGHT_SECTION * SECTION
    const val NIGHT_FROM_ROW = NIGHT_ROW + 1
    const val NIGHT_TO_ROW = NIGHT_ROW + 2

    // Look → Screensaver
    const val SCREENSAVER_ROW = SCREENSAVER_SECTION * SECTION

    // Films and series
    const val CINEMETA_ROW = WATCH_SECTION * SECTION // where films and series come from
    const val WATCH_STREAMS_ROW = CINEMETA_ROW + 1
    const val WATCH_PLAYBACK_ROW = CINEMETA_ROW + 2
    const val UNRELEASED_ROW = CINEMETA_ROW + 3
    const val DOWNLOAD_FOLDER_ROW = CINEMETA_ROW + 4 // see pickDownloadFolder
    const val TORRENT_ROW = CINEMETA_ROW + 5 // the torrent engine (see torrentPage)

    // Films and series → Streams
    const val STREAM_CHOICE_ROW = STREAMS_SECTION * SECTION
    const val STREAM_QUALITY_ROW = STREAM_CHOICE_ROW + 1
    const val FAST_START_ROW = STREAM_CHOICE_ROW + 2
    const val STREAM_LANGUAGE_ROW = STREAM_CHOICE_ROW + 3
    const val UNKNOWN_LANGUAGE_ROW = STREAM_CHOICE_ROW + 4

    // Films and series → Playback
    const val SUBTITLES_ROW = PLAYBACK_SECTION * SECTION
    const val FRAME_RATE_ROW = SUBTITLES_ROW + 1
    const val ASPECT_ROW = SUBTITLES_ROW + 2

    // TV channels
    const val TV_APP_ROW = TV_SECTION * SECTION
    const val PLAY_CHANNELS_ROW = TV_APP_ROW + 1
    const val CHANNEL_NUMBERS_ROW = TV_APP_ROW + 2
    const val EPG_ROW = TV_APP_ROW + 3
    const val CHANNEL_LOGOS_ROW = TV_APP_ROW + 4
    const val CHANNEL_LOGOS_UPDATE_ROW = TV_APP_ROW + 5
    const val CHANNEL_ORDER_ROW = TV_APP_ROW + 6 // while there is a TV app

    // Stremio's add-ons: Add, then one row per add-on
    const val ADDON_ADD_ROW = ADDONS_SECTION * SECTION
    const val ADDON_ROWS = ADDON_ADD_ROW + 1

    // Account and privacy
    const val ZTV_ACCOUNT_ROW = ACCOUNT_SECTION * SECTION
    const val WARP_ROW = ZTV_ACCOUNT_ROW + 1
    const val LOCK_ROW = ZTV_ACCOUNT_ROW + 2
    const val REMOTE_ROW = ZTV_ACCOUNT_ROW + 3 // Remote access; Show the address under it while it's on
    // Change the PIN, the last, while a PIN is set: after Show the address when that's there (see pinRow)

    // System
    const val HOME_REMOTE_ROW = SYSTEM_SECTION * SECTION // Remote behaviour: Home setup, Home button, keys, launcher (see showHomeRemotePage)
    const val UPDATE_ROW = HOME_REMOTE_ROW + 1
    const val BACKUP_ROW = HOME_REMOTE_ROW + 2
    const val PROBLEMS_ROW = HOME_REMOTE_ROW + 3
    const val GUIDE_ROW = HOME_REMOTE_ROW + 4
    const val ABOUT_ROW = HOME_REMOTE_ROW + 5

    /** Each group's page, and the row that opens it in its section: where Back from the group goes. */
    val PARENT_ROW = mapOf(
        APPS_SECTION to HS_APPS_ROW,
        CW_SECTION to HS_CW_ROW,
        SPOTLIGHT_SECTION to HS_SPOTLIGHT_ROW,
        INPUTS_SECTION to HS_INPUTS_ROW,
        WALLPAPER_SECTION to LOOK_WALLPAPERS_ROW,
        CLOCK_SECTION to LOOK_CLOCK_ROW,
        NIGHT_SECTION to LOOK_NIGHT_ROW,
        SCREENSAVER_SECTION to LOOK_SCREENSAVER_ROW,
        STREAMS_SECTION to WATCH_STREAMS_ROW,
        PLAYBACK_SECTION to WATCH_PLAYBACK_ROW,
    )
}
