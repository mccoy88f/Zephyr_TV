package link.mccoy88f.zephyr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbLocalizedTest {
    // As TMDB's /find answers for an IMDb id, in Italian.
    @Test fun findsAFilmInTheTvsLanguage() {
        val l = TmdbClient.localized(JSONObject("""{"movie_results":[{"id":10378,"title":"Big Buck Bunny","overview":"Un coniglio gigante.",
            "poster_path":"/p.jpg","backdrop_path":"/b.jpg","genre_ids":[16,35],"release_date":"2008-05-30"}],"tv_results":[]}"""),
            mapOf(16 to "Animazione", 35 to "Commedia"))!!
        assertEquals(10378L, l.tmdbId)
        assertEquals("Un coniglio gigante.", l.overview)
        assertEquals("https://image.tmdb.org/t/p/w500/p.jpg", l.poster)
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", l.backdrop)
        assertEquals(listOf("Animazione", "Commedia"), l.genres)
        assertEquals("2008", l.year)
    }

    @Test fun findsASeriesAndToleratesMissingArt() {
        val l = TmdbClient.localized(JSONObject("""{"movie_results":[],"tv_results":[{"id":1418,"name":"The Big Bang Theory",
            "overview":"","poster_path":null,"first_air_date":"2007-09-24"}]}"""), emptyMap())!!
        assertTrue(l.tv)
        assertEquals("", l.overview) // none in that language: Cinemeta's stays
        assertNull(l.poster)
        assertNull(l.backdrop)
        assertNull(TmdbClient.localized(JSONObject("""{"movie_results":[],"tv_results":[]}"""), emptyMap()))
    }

    @Test fun readsASeasonsEpisodes() {
        val eps = TmdbClient.seasonEpisodes(JSONObject("""{"episodes":[
            {"episode_number":1,"name":"Episodio pilota","overview":"Leonard e Sheldon.","still_path":"/s1.jpg"},
            {"episode_number":2,"name":"","overview":"","still_path":null}]}"""))
        assertEquals("Episodio pilota", eps[1]!!.name)
        assertEquals("https://image.tmdb.org/t/p/w300/s1.jpg", eps[1]!!.still)
        assertNull(eps[2]!!.still)
    }

    @Test fun parsesCreditsWithDirectorAndCast() {
        val json = JSONObject("""{
            "id": 157336,
            "cast": [
                {"id": 10297, "name": "Matthew McConaughey", "character": "Cooper", "profile_path": "/e.jpg"},
                {"id": 1813, "name": "Anne Hathaway", "character": "Brand", "profile_path": null}
            ],
            "crew": [
                {"id": 525, "name": "Christopher Nolan", "job": "Director", "profile_path": "/nolan.jpg"},
                {"id": 999, "name": "Emma Thomas", "job": "Producer", "profile_path": "/thomas.jpg"}
            ]
        }""")
        val (directors, cast) = TmdbClient.credits(json)
        assertEquals(1, directors.size)
        assertEquals("Christopher Nolan", directors[0].first)
        assertEquals("https://image.tmdb.org/t/p/w185/nolan.jpg", directors[0].second)

        assertEquals(2, cast.size)
        assertEquals("Matthew McConaughey", cast[0].first)
        assertEquals("https://image.tmdb.org/t/p/w185/e.jpg", cast[0].second)
        assertEquals("Anne Hathaway", cast[1].first)
        assertNull(cast[1].second)
    }

    @Test fun searchIncludesKnownForWhenPersonFound() {
        val results = org.json.JSONArray("""[
            {
                "id": 525,
                "name": "Christopher Nolan",
                "media_type": "person",
                "known_for": [
                    {"id": 157336, "title": "Interstellar", "media_type": "movie", "overview": "Space travel.", "genre_ids": [18, 878]}
                ]
            },
            {
                "id": 27205,
                "title": "Inception",
                "media_type": "movie",
                "overview": "Dreams within dreams.",
                "genre_ids": [28, 878]
            }
        ]""")
        val items = TmdbClient.parseSearchResults(results, mapOf(18 to "Drama", 28 to "Action", 878 to "Sci-Fi"))
        assertEquals(2, items.size)
        assertEquals(157336L, items[0].id)
        assertEquals("Interstellar", items[0].title)
        assertEquals(27205L, items[1].id)
        assertEquals("Inception", items[1].title)
    }

    @Test fun parsesPersonCreditsForDirectorAndCast() {
        val json = JSONObject("""{
            "id": 525,
            "cast": [
                {"id": 99901, "title": "Cameo Movie", "media_type": "movie", "overview": "Cameo role.", "release_date": "2010-01-01"}
            ],
            "crew": [
                {"id": 157336, "title": "Interstellar", "media_type": "movie", "overview": "Space.", "job": "Director", "department": "Directing", "release_date": "2014-11-05"},
                {"id": 88801, "title": "Produced Film", "media_type": "movie", "overview": "Producer.", "job": "Producer", "department": "Production", "release_date": "2015-01-01"}
            ]
        }""")
        val directed = TmdbClient.parsePersonCredits(json, emptyMap(), isDirector = true)
        assertEquals(1, directed.size)
        assertEquals(157336L, directed[0].id)
        assertEquals("Interstellar", directed[0].title)

        val acted = TmdbClient.parsePersonCredits(json, emptyMap(), isDirector = false)
        assertEquals(1, acted.size)
        assertEquals(99901L, acted[0].id)
        assertEquals("Cameo Movie", acted[0].title)
    }
}
