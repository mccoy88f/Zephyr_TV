package link.mccoy88f.zephyr

import java.util.Locale
import link.mccoy88f.zephyr.SettingsRows.EPG_ROW

// Where the TV guide comes from. With nothing set, Zephyr TV takes the guide of the TV's country from iptv-epg.org,
// which has one gzipped XMLTV file for each country (epg-<country>.xml.gz). Settings → TV and EPG → TV guide picks another
// country, an address of one's own (typed, or from the phone), or no guide.

/** iptv-epg.org's home page: it links each country's guide. */
internal const val EPG_GUIDES_PAGE = "https://iptv-epg.org/"

/** The value of K.EPG_URL that turns the guide off. Nothing saved means the guide of the TV's country. */
internal const val EPG_OFF = "off"

/** iptv-epg.org's guide for the country [code] (ISO 3166, two letters), gzipped. */
internal fun epgCountryUrl(code: String) = "https://iptv-epg.org/files/epg-${code.lowercase(Locale.ROOT)}.xml.gz"

private val EPG_LINK = Regex("""(?:href=["']([^"']*?))?epg-([a-z]{2})\.xml(?:\.gz)?\b""")

/** The countries whose guides [html] (iptv-epg.org's page, or an address) names, by code, in order. */
internal fun epgCountriesIn(html: String): List<String> = epgLinksIn(html, EPG_GUIDES_PAGE).keys.sorted()

/**
 * Each country's guide as [html] links it (its code to the link, made absolute against [base]): the page's own
 * address, so a guide that moves is still found.
 */
internal fun epgLinksIn(html: String, base: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (m in EPG_LINK.findAll(html)) {
        val code = m.groupValues[2]
        if (code in out) continue
        val href = m.groupValues[1].takeIf { it.isNotEmpty() }?.let { it + m.value.substringAfter(it) }
        out[code] = href?.let { runCatching { java.net.URL(java.net.URL(base), it).toString() }.getOrNull() } ?: epgCountryUrl(code)
    }
    return out
}

/** The country [code] in the TV's language ("it": Italia). */
internal fun countryName(code: String): String =
    Locale("", code.uppercase(Locale.ROOT)).getDisplayCountry(Locale.getDefault()).ifBlank { code.uppercase(Locale.ROOT) }

/** The guide of the TV's country (see channelCountry). */
internal fun MainActivity.autoEpgUrl() = epgCountryUrl(channelCountry())

/** The guide in use: the address set, else the TV's country's; null when it's off. */
internal fun MainActivity.epgUrl(): String? {
    val saved = prefs.getString(K.EPG_URL, null)?.takeIf { it.isNotBlank() }
    if (saved == EPG_OFF) return null
    return saved ?: autoEpgUrl()
}

/** What the TV guide row shows: Automatic (the country), the country or the address host set, or Off. */
internal fun MainActivity.epgValue(): String {
    val saved = prefs.getString(K.EPG_URL, null)?.takeIf { it.isNotBlank() }
    return when {
        saved == null -> getString(R.string.epg_auto, countryName(channelCountry()))
        saved == EPG_OFF -> getString(R.string.state_off)
        else -> epgCountriesIn(saved).firstOrNull()?.let { countryName(it) } ?: (android.net.Uri.parse(saved).host ?: saved)
    }
}

/** Settings → TV and EPG → TV guide: Automatic, another country, an address of one's own, or off. */
internal fun MainActivity.configureEpg() {
    val saved = prefs.getString(K.EPG_URL, null)?.takeIf { it.isNotBlank() }
    val country = saved?.let { epgCountriesIn(it).firstOrNull() }
    val custom = saved != null && saved != EPG_OFF && country == null
    showPage(getString(R.string.setting_epg), listOf(
        page(getString(R.string.epg_auto, countryName(channelCountry())), if (saved == null) MainActivity.CHOSEN else "") {
            saveEpgUrl(null)
            renderSettings(EPG_ROW)
        },
        page(getString(R.string.epg_country), country?.let { countryName(it) } ?: MainActivity.SUBMENU) { pickEpgCountry() },
        page(getString(R.string.epg_custom), if (custom) android.net.Uri.parse(saved).host.orEmpty() else MainActivity.SUBMENU) { customEpg() },
        page(getString(R.string.state_off), if (saved == EPG_OFF) MainActivity.CHOSEN else "") {
            prefs.edit().putString(K.EPG_URL, EPG_OFF).apply()
            watchKey = null
            renderSettings(EPG_ROW)
        },
    ), back = { renderSettings(EPG_ROW) })
}

/** The countries iptv-epg.org has a guide for, read from its page (off the UI thread), to pick one. */
private fun MainActivity.pickEpgCountry() {
    toast(R.string.searching, long = false)
    netIo.execute {
        val links = runCatching { epgLinksIn(Http.text(EPG_GUIDES_PAGE), EPG_GUIDES_PAGE) }.onFailure { warn("TV guides list failed ($it)") }.getOrDefault(emptyMap())
        ui.post {
            if (links.isEmpty()) return@post toast(R.string.toast_epg_failed_list)
            val now = prefs.getString(K.EPG_URL, null)
            val sorted = links.keys.sortedBy { countryName(it).lowercase() }
            val own = channelCountry().lowercase(Locale.ROOT)
            showPage(getString(R.string.epg_country), sorted.map { code ->
                val url = links.getValue(code)
                page(countryName(code), if (now == url) MainActivity.CHOSEN else if (code == own) getString(R.string.epg_this_tv) else "") {
                    saveEpgUrl(url)
                    renderSettings(EPG_ROW)
                }
            }, (sorted.indexOf(own)).coerceAtLeast(0), back = { configureEpg() })
        }
    }
}

/** An address of one's own, typed with the remote or sent from the phone. */
private fun MainActivity.customEpg() {
    val saved = prefs.getString(K.EPG_URL, null)?.takeIf { it.startsWith("http") && epgCountriesIn(it).isEmpty() } ?: ""
    textPage(getString(R.string.epg_custom), saved, getString(R.string.epg_hint), getString(R.string.epg_intro), back = { configureEpg() }) { typed ->
        listOf(
            page(getString(R.string.from_phone)) { epgFromPhone() },
            page(getString(R.string.dialog_save)) {
                saveEpgUrl(typed())
                renderSettings(EPG_ROW)
            },
        )
    }
}
