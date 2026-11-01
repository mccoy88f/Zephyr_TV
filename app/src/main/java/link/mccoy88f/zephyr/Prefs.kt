package link.mccoy88f.zephyr

/**
 * Every key Zephyr TV keeps settings and state under, in one place: a mistyped key is a setting that silently never
 * saves. They live in the "home" preferences file (Plume's name, kept so the code stays a clean fork of it).
 * Per-app entries add the package to a prefix: "t_<package>" (when an app was last opened from a card),
 * "n_<package>" (how often it was opened from the row).
 */
internal object K {
    const val FILE = "home"

    // Look
    const val THEME = "theme"
    const val WP_LIGHT = "wp_light"
    const val WP_DARK = "wp_dark"
    const val WP_FADE = "wp_fade"
    const val WP_EVERY = "wp_every"
    const val WP_PHOTO_LIGHT = "wp_photo_light"
    const val WP_PHOTO_DARK = "wp_photo_dark"
    const val WP_PHOTO_AT_LIGHT = "wp_photo_at_light"
    const val WP_PHOTO_AT_DARK = "wp_photo_at_dark"
    const val COLOUR_ICONS = "colour_icons"
    const val CLOCK_24 = "clock_24" // "auto" (the TV's setting), "24" or "12"
    const val NIGHT = "night" // night mode on
    const val CRASHES = "crashes" // times the process died, so a run of them can turn extras off (see Safety)
    const val WARP = "warp" // Cloudflare WARP on, as this app's VPN (see Warp); not in backups
    const val WARP_ACCOUNT = "warp_account" // its WARP account, JSON with its private key: never in backups
    const val REMOTE = "remote" // remote access on: a phone on the home network can change settings (see Remote)
    const val REMOTE_PIN = "remote_pin" // that phone's PIN, hashed; kept when remote access is turned off; not in backups
    const val REMOTE_TOKEN = "remote_token" // the secret piece of its address, so a guess on the network misses it; not in backups
    const val ROWLESS = "rowless" // apps that showed no rows under them: one tap opens them on a phone (see rowless)
    const val STATUS_BAR = "status_bar" // a phone or tablet: its status bar (time, network) shown; hidden by default
    const val NIGHT_FROM = "night_from" // night mode window, minutes after midnight
    const val NIGHT_TO = "night_to"

    // Apps row
    const val SORT = "sort"
    const val ORDER = "order"
    const val ROW_HIDDEN = "row_hidden"
    const val ROW_ALIGN = "row_align"
    const val NAMES = "names"
    const val LOCK_PIN = "lock_pin"
    const val TIP_MOVE = "tip_move"
    const val INPUTS_MODE = "inputs_mode" // "menu" (default) or "apps"
    const val INPUTS_PINNED = "inputs_pinned" // HDMI ports pinned by hand
    const val INPUTS_IN_APPS = "inputs_in_apps" // show connected HDMI inputs in apps row even in menu mode

    // Continue Watching
    const val HIDDEN_WATCH = "hidden_watch"
    const val HIDDEN_PLAYING = "hidden_playing" // paused tracks closed from the row, "<package>/<title>"; shown again once they play
    const val CW_OFF = "cw_off"
    const val SECTIONS_OFF = "sections_off" // "pkg/section" of the app sections turned off
    const val CHANNEL_LOGOS = "channel_logos" // logos from iptv-org for channels the TV has none for
    const val PLAY_CHANNELS = "play_channels" // open an add-on TV channel straight into play (off: its page first)
    const val EPG_URL = "epg_url" // an XMLTV guide's address, for what's on (see Epg)

    // Stremio add-ons
    const val ADDONS = "addons" // the add-ons' manifest addresses, in order, one per line (see Addons)
    const val ADDONS_VOD = "addons_vod" // where their films and series show: "tiles" (Films and Series), "apps" (an app each), "off"
    const val CINEMETA_URL = "cinemeta_url" // the catalog source's manifest, when not Cinemeta (see MainActivity.cinemetaUrl)
    const val CATALOG_SOURCES = "catalog_sources" // the catalog sources in use, in order, one per line (see catalogSources)
    const val CATALOGS = "catalogs" // catalogs added as alternatives to Cinemeta, manifest addresses one per line (see catalogUrls)
    const val SHELVES_OFF = "shelves_off" // "vod:<add-on id>:<type>:<catalog id>" of the catalogs' rows turned off, everywhere
    const val SECTION_NAMES = "section_names" // JSON: "pkg/section" to the name the user gave it
    const val USERS = "users" // JSON: Zephyr TV's users, [{id, name}] (see Users)
    const val USER = "user" // the id of the user in use ("": the first)
    const val ADDONS_TV = "addons_tv" // where their TV channels show: "tv" (the TV app), "iptv" (one IPTV app), "apps" (an app each)
    const val ADULT_ADDONS = "adult_addons" // set of manifest addresses of add-ons marked as adult (18+)
    const val ADULT_PIN = "adult_pin" // the 18+ lock's PIN, hashed (see AdultLock); none: the lock is off
    const val ADULT_UNTIL = "adult_until" // when the 18+ unlock runs out (wall clock ms; Long.MAX_VALUE: until locked again)
    const val ADULT_BOOT = "adult_boot" // when the TV had started at the 18+ unlock (see adultUnlocked)
    const val ADULT_FOREVER = "adult_forever" // an 18+ unlock lasts until locked again, not two hours
    const val HIDE_UNRELEASED = "hide_unreleased" // films not out at home yet (in cinemas, to come) left out of Spotlight and the apps' rows
    const val SUBTITLES_ON = "subtitles_on" // subtitles on in every stream (the last picked language, else the TV's); off: forced ones only
    const val HIDE_UNKNOWN_LANG = "hide_unknown_lang" // streams that say no language hidden, not last (see languageRank)
    const val FAST_START = "fast_start" // a film starts with the first working stream in the preferred quality (see quickStreams)
    const val STREAM_QUALITY = "stream_quality" // the quality streams are preferred in, by height; none: the highest
    const val STREAM_LANG_ONLY = "stream_lang_only" // films' and episodes' streams in the TV's language only (see streamLanguage)
    const val STREAM_MANUAL = "stream_manual" // films' and episodes' streams picked by hand from one list (see pickStream)
    const val ADDONS_OFF = "addons_off" // add-ons switched off: kept, with their settings, but not shown nor asked
    const val ADDONS_MOVIES = "addons_movies" // where films show (VodPlace); none: as ADDONS_VOD, set before films and series were apart
    const val ADDONS_SERIES = "addons_series" // where series show, the same way
    const val ADDONS_VIDEOS = "addons_videos" // where videos show (VodPlace)
    const val ADDONS_COURSES = "addons_courses" // where courses show (VodPlace)
    const val ADDONS_PODCASTS = "addons_podcasts" // where podcasts show (VodPlace)
    const val ADDONS_AUDIOBOOKS = "addons_audiobooks" // where audiobooks show (VodPlace)
    const val ADDONS_EVENTS = "addons_events" // where events show (VodPlace)
    const val RADIO_PLACE = "radio_place" // where the extensions' radio stations show (RadioPlace)
    const val NUVIO_REPOS_OFF = "nuvio_repos_off" // Nuvio repositories switched off, the same way
    const val NUVIO_REPOS = "nuvio_repos" // Nuvio plugin repositories, manifest addresses one per line (see Plugins)
    const val NUVIO_ON = "nuvio_on" // plugins switched on that their manifest has off ("<manifest>#<id>")
    const val NUVIO_OFF = "nuvio_off" // plugins switched off that their manifest has on
    const val NUVIO_ADULT = "nuvio_adult" // repositories marked 18+
    const val EXT_ORDER = "ext_order" // extensions installed, by id, in the user's order, one per line
    const val EXT_OFF = "ext_off" // extensions switched off, by id
    const val EXT_ADULT = "ext_adult" // extensions marked 18+ by the user, by id (one saying so itself is 18+ anyway)
    const val EXT_REPOS = "ext_repos" // the repositories' addresses, one a line; none set: Zephyr TV's own (see extensionRepos)
    const val EXT_SOURCES = "ext_sources" // where each was added from, JSON {id: its manifest-zephyrtv.json address}
    const val EXT_COLLECT = "ext_collect" // unused: kept so a backup still restores
    const val EXT_OWN = "ext_own" // unused: kept so a backup still restores
    const val EXT_APP = "ext_app" // an extension's virtual app: JSON {id: "show"|"hide"}; missing: follow the system
    const val EXT_OPTIONS = "ext_options" // extensions' settings chosen, JSON: {id: {key: value}}
    const val NUVIO_ORDER = "nuvio_order" // plugins in the order the user put them, "<manifest>#<id>" one per line
    const val NUVIO_OPTIONS = "nuvio_options" // plugins' options chosen, JSON: {"<manifest>#<id>": {key: value}}
    const val ADULT_PLACE = "adult_place" // where the adult add-ons show: virtual, apps, mixed (see AdultPlace)
    const val ADULT_APP = "adult_app" // the adult add-ons in an app of their own, Adult content (see AdultLock)

    // Spotlight
    const val TMDB_API_KEY = "tmdb_api_key"
    const val TMDB_LANG = "tmdb_lang"
    const val TMDB_TYPE = "tmdb_type"
    const val TMDB_DEFAULT_APPS = "tmdb_default_apps"
    const val TMDB_ENRICH = "tmdb_enrich" // Complete with TMDB: catalog titles in the TV's language (see tmdbEnrich)
    const val TMDB_CYCLE = "tmdb_cycle"
    const val TMDB_PROVIDERS = "tmdb_providers"
    const val TMDB_SOURCE = "tmdb_source" // "trending" or "list"
    const val TMDB_LIST = "tmdb_list" // a public TMDB list's id

    // Weather
    const val WEATHER = "weather"
    const val WX_UNIT = "wx_unit"
    const val WX_TEMP = "wx_temp"
    const val WX_CODE = "wx_code"
    const val WX_TIME = "wx_time"
    const val GEO_MANUAL = "geo_manual"
    const val GEO_CITY = "geo_city"
    const val GEO_LAT = "geo_lat"
    const val GEO_LON = "geo_lon"
    const val GEO_TIME = "geo_time"

    // Screensaver
    const val SS_FOLDER = "ss_folder"
    const val PREV_SCREENSAVER = "prev_screensaver"

    // System
    const val HOME_OFF = "home_off"
    const val SETUP_SHOWN = "setup_shown"
    const val REOPEN_ROW = "reopen_row"
    const val UPD_VER = "upd_ver"
    const val UPD_TIME = "upd_time"
    const val UPD_TOLD = "upd_told"
    const val UPD_BETA = "upd_beta" // betas (pre-releases) are offered too
    const val SEEN_NOTES = "seen_notes"
    const val IMPORTED = "imported" // Piume's settings and history were taken, or there was no Piume (see FromPiume)
    const val PIUME_TOLD = "piume_told" // the page to uninstall Piume was shown
}
