package link.mccoy88f.zephyr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private fun e(at: Long, name: String = "x") = JSONObject().put("at", at).put("name", name)
    private fun merge(base: Map<String, JSONObject>, local: Map<String, JSONObject>, remote: Map<String, JSONObject>) =
        SyncMerge.merge(base, local, remote, SyncMerge::newer)

    @Test fun firstSyncIsTheUnion() {
        val out = merge(emptyMap(), mapOf("a" to e(1)), mapOf("b" to e(2)))
        assertEquals(setOf("a", "b"), out.keys)
    }

    @Test fun removedOnOneSideStaysRemoved() {
        val base = mapOf("a" to e(1), "b" to e(2))
        // "a" unmarked here, "b" unmarked on another TV: neither comes back.
        val out = merge(base, mapOf("b" to e(2)), mapOf("a" to e(1)))
        assertTrue(out.isEmpty())
    }

    @Test fun changedOnOneSideWins() {
        val base = mapOf("a" to e(1))
        assertEquals(5L, merge(base, mapOf("a" to e(1)), mapOf("a" to e(5))).getValue("a").getLong("at"))
        assertEquals(7L, merge(base, mapOf("a" to e(7)), mapOf("a" to e(1))).getValue("a").getLong("at"))
    }

    @Test fun changedOnBothSidesTheNewerWins() {
        val base = mapOf("a" to e(1))
        assertEquals(9L, merge(base, mapOf("a" to e(9)), mapOf("a" to e(4))).getValue("a").getLong("at"))
        assertEquals(9L, merge(base, mapOf("a" to e(4)), mapOf("a" to e(9))).getValue("a").getLong("at"))
    }

    @Test fun removedThereButChangedHereIsKept() {
        val base = mapOf("a" to e(1))
        assertEquals(setOf("a"), merge(base, mapOf("a" to e(3)), emptyMap()).keys)
    }

    @Test fun sameContentWhateverTheOrder() {
        val a = JSONObject("""{"x":1,"y":{"b":2,"a":"s"}}""")
        val b = JSONObject("""{"y":{"a":"s","b":2},"x":1}""")
        assertTrue(SyncMerge.same(a, b))
    }

    @Test fun settingsFirstSyncTakesTheAccounts() {
        val out = SyncMerge.merge(emptyMap(), mapOf("theme" to JSONObject().put("s", "light")), mapOf("theme" to JSONObject().put("s", "dark"))) { _, r -> r }
        assertEquals("dark", out.getValue("theme").getString("s"))
    }

    @Test fun mergedConfigurationsJoinTheirLists() {
        val local = mapOf(
            K.ADDONS to JSONObject().put("s", "https://a/manifest.json\nhttps://b/manifest.json"),
            K.ADDONS_OFF to JSONObject().put("set", org.json.JSONArray(listOf("x"))),
            "theme" to JSONObject().put("s", "light"),
            "only_here" to JSONObject().put("b", true),
        )
        val remote = mapOf(
            K.ADDONS to JSONObject().put("s", "https://c/manifest.json\nhttps://a/manifest.json"),
            K.ADDONS_OFF to JSONObject().put("set", org.json.JSONArray(listOf("y"))),
            "theme" to JSONObject().put("s", "dark"),
        )
        val out = SyncMerge.mergeSettings(local, remote)
        assertEquals("https://c/manifest.json\nhttps://a/manifest.json\nhttps://b/manifest.json", out.getValue(K.ADDONS).getString("s"))
        assertEquals(2, out.getValue(K.ADDONS_OFF).getJSONArray("set").length())
        assertEquals("dark", out.getValue("theme").getString("s")) // the account's wins
        assertTrue(out.getValue("only_here").getBoolean("b")) // this TV's own stays
    }
}
