package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Test

class UpcomingNotesTest {
    // The versions after the one installed, up to the update found, newest first; their "- " lines without ** marks.
    @Test fun notesBetweenThisVersionAndTheUpdate() {
        val releases = listOf(
            "4.0.0-beta.17" to "- Not offered yet.",
            "4.0.0-beta.14" to "- Old one.",
            "4.0.0-beta.15" to "- Select **Users** to pick.\r\n- Second.\r\nNot a note.",
            "4.0.0-beta.16" to "- Avatars.",
        )
        val got = upcomingNotes(releases, mine = "4.0.0-beta.14", ver = "4.0.0-beta.16")
        assertEquals(listOf("4.0.0-beta.16", "4.0.0-beta.15"), got.map { it.first })
        assertEquals(listOf("Avatars."), got[0].second)
        assertEquals(listOf("Select Users to pick.", "Second."), got[1].second)
    }

    @Test fun nothingNewer() {
        assertEquals(emptyList<Pair<String, List<String>>>(), upcomingNotes(listOf("4.0" to "- x"), "4.0", "4.0"))
    }
}
