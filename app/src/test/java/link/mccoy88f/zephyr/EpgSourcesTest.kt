package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgSourcesTest {
    @Test fun eachCountryHasItsGuide() {
        assertEquals("https://iptv-epg.org/files/epg-it.xml.gz", epgCountryUrl("IT"))
        assertEquals("https://iptv-epg.org/files/epg-us.xml.gz", epgCountryUrl("us"))
    }

    @Test fun theCountriesComeFromTheHomePage() {
        val html = """<a href="/files/epg-al.xml.gz">Albania</a> <a href="https://iptv-epg.org/files/epg-de.xml.gz">Germany</a>
            <a href="files/epg-it.xml">Italy</a> <a href="/files/epg-al.xml.gz">again</a> <a href="/files/other.xml">no</a>"""
        val links = epgLinksIn(html, "https://iptv-epg.org/")
        assertEquals(listOf("al", "de", "it"), links.keys.sorted())
        assertEquals("https://iptv-epg.org/files/epg-al.xml.gz", links["al"]) // the page's own link, made absolute
        assertEquals("https://iptv-epg.org/files/epg-de.xml.gz", links["de"])
        assertEquals("https://iptv-epg.org/files/epg-it.xml", links["it"])
        assertEquals(listOf("al", "de", "it"), epgCountriesIn(html))
    }

    @Test fun theAddressSavedNamesItsCountry() {
        assertEquals(listOf("fr"), epgCountriesIn(epgCountryUrl("FR")))
        assertEquals(emptyList<String>(), epgCountriesIn("https://example.com/guide.xml.gz"))
    }
}
