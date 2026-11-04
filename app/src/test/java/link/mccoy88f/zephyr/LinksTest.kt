package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Test

class LinksTest {
    private val film = TmdbItem(603, "The Matrix", "", null, 8.2, "movie", emptyList(), "1999-03-31")
    private val show = TmdbItem(1399, "Game of Thrones", "", null, 8.4, "tv", emptyList(), "2011-04-17")

    @Test fun nuvioOpensByTmdbId() {
        assertEquals(listOf("nuvio://tmdb/movie/603"), TmdbClient.titleLinks("com.nuvio.tv", film, null))
        assertEquals(listOf("nuvio://tmdb/series/1399"), TmdbClient.titleLinks("com.nuvio.app", show, null))
    }

    @Test fun stremioOpensByImdbId() {
        assertEquals(listOf("stremio:///detail/movie/tt0133093/tt0133093"), TmdbClient.titleLinks("com.stremio.one", film, "tt0133093"))
        assertEquals(listOf("stremio:///detail/series/tt0944947"), TmdbClient.titleLinks("com.stremio.one", show, "tt0944947"))
        assertEquals(emptyList<String>(), TmdbClient.titleLinks("com.stremio.one", film, null)) // no IMDb id: it searches
    }

    @Test fun youtubePlaysTheWatchLinkFirst() {
        assertEquals(listOf("https://www.youtube.com/watch?v=abc", "vnd.youtube:abc"), TmdbClient.titleLinks("com.google.android.youtube.tv", film, "abc"))
        assertEquals(emptyList<String>(), TmdbClient.titleLinks("com.netflix.ninja", film, "x"))
    }

    @Test fun wallpaperChoice() {
        assertEquals("file:Photo 1.jpg", chooseWallpaper("file:Photo 1.jpg", true, false))
        assertEquals("tmdb:trending", chooseWallpaper(null, true, true))
        assertEquals("res:Rockies (day)", chooseWallpaper(null, false, false))
        assertEquals("res:Rockies (dusk)", chooseWallpaper(null, false, true))
    }

    // The site's links stand for ztv: links: a title, an extension; anything else on the site isn't one.
    @Test fun siteLinks() {
        assertEquals("ztv://title/movie/tt0118799?n=La%20vita", siteToZtv("https://ztv.mccoy88f.link/t/movie/tt0118799?n=La%20vita"))
        assertEquals("ztv://title/series/tt0903747?n=X&s=1&e=3", siteToZtv("https://ztv.mccoy88f.link/t/series/tt0903747?n=X&s=1&e=3"))
        assertEquals("ztv://ext?u=https%3A%2F%2Fa.b%2Fm.json", siteToZtv("https://ztv.mccoy88f.link/o/ext?u=https%3A%2F%2Fa.b%2Fm.json"))
        assertEquals(null, siteToZtv("https://ztv.mccoy88f.link/it/download"))
        assertEquals(null, siteToZtv("https://ztv.mccoy88f.link/t/"))
        assertEquals(null, siteToZtv("https://example.com/t/movie/tt1"))
    }
}
