package link.mccoy88f.zephyr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFilterTest {
    @Test fun acceptsAllTypesByDefault() {
        val f = SearchFilter()
        assertTrue(f.accepts(Stremio.Title("movie", "m1", "Film", null, null, null, "2020", listOf("Action"))))
        assertTrue(f.accepts(Stremio.Title("series", "s1", "Series", null, null, null, "2020", listOf("Action"))))
        assertTrue(f.accepts(Stremio.Title("adult", "a1", "Adult", null, null, null, "2020", listOf("Action"))))
        assertTrue(f.accepts(Stremio.Title("porn", "p1", "Porn", null, null, null, "2020", listOf("Action"))))
        assertTrue(f.accepts(Stremio.Title("video", "v1", "Video", null, null, null, "2020", listOf("Action"))))
        assertTrue(f.accepts(Stremio.Title("podcast", "pc1", "Podcast", null, null, null, "2020", listOf("Action"))))
    }

    @Test fun filtersByMoviesCollector() {
        val f = SearchFilter(type = "movie")
        assertTrue(f.accepts(Stremio.Title("movie", "m1", "Film", null, null, null, "2020", listOf("Action"))))
        assertTrue(f.accepts(Stremio.Title("adult", "a1", "Adult", null, null, null, "2020", listOf("Action"))))
        assertTrue(f.accepts(Stremio.Title("porn", "p1", "Porn", null, null, null, "2020", listOf("Action"))))
        assertFalse(f.accepts(Stremio.Title("series", "s1", "Series", null, null, null, "2020", listOf("Action"))))
        assertFalse(f.accepts(Stremio.Title("video", "v1", "Video", null, null, null, "2020", listOf("Action"))))
        assertFalse(f.accepts(Stremio.Title("podcast", "pc1", "Podcast", null, null, null, "2020", listOf("Action"))))
    }

    @Test fun filtersBySeriesCollector() {
        val f = SearchFilter(type = "series")
        assertTrue(f.accepts(Stremio.Title("series", "s1", "Series", null, null, null, "2020", listOf("Action"))))
        assertFalse(f.accepts(Stremio.Title("movie", "m1", "Film", null, null, null, "2020", listOf("Action"))))
        assertFalse(f.accepts(Stremio.Title("adult", "a1", "Adult", null, null, null, "2020", listOf("Action"))))
        assertFalse(f.accepts(Stremio.Title("video", "v1", "Video", null, null, null, "2020", listOf("Action"))))
    }

    @Test fun filtersByVideosCollector() {
        val f = SearchFilter(type = "videos")
        assertTrue(f.accepts(Stremio.Title("video", "v1", "Video", null, null, null, "2020", listOf("Action"))))
        assertTrue(f.accepts(Stremio.Title("channel", "c1", "Channel", null, null, null, "2020", listOf("Action"))))
        assertFalse(f.accepts(Stremio.Title("movie", "m1", "Film", null, null, null, "2020", listOf("Action"))))
        assertFalse(f.accepts(Stremio.Title("podcast", "p1", "Podcast", null, null, null, "2020", listOf("Action"))))
    }

    @Test fun filtersByPodcastsCollector() {
        val f = SearchFilter(type = "podcasts")
        assertTrue(f.accepts(Stremio.Title("podcast", "p1", "Podcast", null, null, null, "2020", listOf("Action"))))
        assertFalse(f.accepts(Stremio.Title("video", "v1", "Video", null, null, null, "2020", listOf("Action"))))
    }

    @Test fun directorAndActorFiltersActivateAny() {
        assertFalse(SearchFilter().any)
        assertTrue(SearchFilter(director = "Christopher Nolan").any)
        assertTrue(SearchFilter(actor = "Leonardo DiCaprio").any)
    }
}
