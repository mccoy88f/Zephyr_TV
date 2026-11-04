package link.mccoy88f.zephyr

import link.mccoy88f.zephyr.TmdbClient.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerTest {
    @Test fun namesTheLanguageInItself() {
        assertEquals("Dune trailer italiano", TmdbClient.trailerQuery("Dune", "it-IT"))
        assertEquals("Dune trailer deutsch", TmdbClient.trailerQuery("Dune", "de-DE"))
        assertEquals("Dune trailer english", TmdbClient.trailerQuery("Dune", "en-US"))
        assertEquals("Dune trailer português", TmdbClient.trailerQuery("Dune", "pt-BR"))
        assertEquals("Dune trailer 中文", TmdbClient.trailerQuery("Dune", "zh-CN"))
        assertEquals("Dune trailer english", TmdbClient.trailerQuery("Dune", "ja-JP")) // unknown: English
    }

    private fun v(key: String, type: String = "Trailer", lang: String = "en", official: Boolean = true, site: String = "YouTube", at: String = "2026-01-01") =
        Video(key, site, type, official, lang, at)

    @Test fun picksTheBestTrailer() {
        // The chosen language wins over English, a trailer over a teaser, official over fan-made, newer over older.
        assertEquals("it", TmdbClient.bestTrailer(listOf(v("en"), v("it", lang = "it")), "it-IT")?.key)
        assertEquals("en", TmdbClient.bestTrailer(listOf(v("en"), v("fr", lang = "fr")), "it-IT")?.key)
        assertEquals("trailer", TmdbClient.bestTrailer(listOf(v("teaser", type = "Teaser", lang = "it"), v("trailer")), "it-IT")?.key)
        assertEquals("official", TmdbClient.bestTrailer(listOf(v("fan", official = false), v("official")), "en-US")?.key)
        assertEquals("new", TmdbClient.bestTrailer(listOf(v("old", at = "2025-01-01"), v("new", at = "2026-05-01")), "en-US")?.key)
    }

    @Test fun skipsWhatTheTvCantPlay() {
        assertNull(TmdbClient.bestTrailer(listOf(v("vimeo", site = "Vimeo"), v("clip", type = "Clip"), v("")), "en-US"))
        assertNull(TmdbClient.bestTrailer(emptyList(), "en-US"))
    }
}

class ListIdTest {
    @Test fun findsTheListInWhatWasPasted() {
        assertEquals("8123456", TmdbClient.listId("https://www.themoviedb.org/list/8123456-best-films?language=it"))
        assertEquals("42", TmdbClient.listId(" 42 "))
        assertEquals(null, TmdbClient.listId("not a list"))
    }
}
