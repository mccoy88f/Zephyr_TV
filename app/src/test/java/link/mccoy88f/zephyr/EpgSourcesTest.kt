package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgSourcesTest {
    @Test fun eachCountryHasItsGuide() {
        assertEquals("https://iptv-epg.org/files/epg-it.xml", epgCountryUrl("IT"))
        assertEquals("https://iptv-epg.org/files/epg-us.xml", epgCountryUrl("us"))
    }

    @Test fun theCountriesComeFromTheGuidesPage() {
        val html = """<a href="/files/epg-it.xml">Italy</a> <a href="https://iptv-epg.org/files/epg-de.xml">Germany</a>
            <a href="/files/epg-it.xml">again</a> <a href="/files/other.xml">no</a> <a href="/files/epg-usa.xml">no</a>"""
        assertEquals(listOf("de", "it"), epgCountriesIn(html))
        assertEquals(listOf("fr"), epgCountriesIn(epgCountryUrl("FR"))) // the address saved names its country
        assertEquals(emptyList<String>(), epgCountriesIn("https://example.com/guide.xml.gz"))
    }
}
