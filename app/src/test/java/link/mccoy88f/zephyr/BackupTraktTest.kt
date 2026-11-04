package link.mccoy88f.zephyr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupTraktTest {
    private fun signIn(dir: File, user: String) {
        dir.mkdirs()
        File(dir, "trakt.json").writeText(JSONObject().put("access", "SECRET-ACCESS").put("refresh", "SECRET-REFRESH")
            .put("expires", 0L).put("user", user).toString())
    }

    // Who had Trakt, and its name: never its keys.
    @Test fun backupSaysWhoHadTraktNotTheKeys() {
        val files = Files.createTempDirectory("tv").toFile()
        signIn(files, "mario")
        signIn(File(files, "user_abc12345"), "anna")
        File(files, "user_nobody1").mkdirs()
        val trakt = Backup.exportTrakt(files, listOf("", "abc12345", "nobody1"))
        assertEquals(setOf("", "abc12345"), trakt.keys().asSequence().toSet())
        assertEquals("mario", trakt.getString(""))
        assertFalse(trakt.toString().contains("SECRET"))
    }

    // After a restore: those without an account on this TV are asked to sign in again; those that have one aren't.
    @Test fun restoreAsksThoseWithoutAnAccountHere() {
        val other = Files.createTempDirectory("tv2").toFile()
        signIn(File(other, "user_abc12345"), "anna") // already signed in on this TV
        val pending = Backup.traktToConnect(other, JSONObject().put("", "mario").put("abc12345", "anna"))
        assertEquals(setOf(""), pending.keys().asSequence().toSet())
        assertEquals("mario", pending.getString(""))
    }
}
