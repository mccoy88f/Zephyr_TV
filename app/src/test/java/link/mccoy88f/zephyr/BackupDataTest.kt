package link.mccoy88f.zephyr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupDataTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun usersDataGoesAndComesBack() {
        val tv = tmp.newFolder()
        File(tv, "vod_watched.json").writeText("""{"tt1":{"at":1}}""")
        File(tv, "user_kid").mkdirs()
        File(tv, "user_kid/vod_progress.json").writeText("""{"tt2":{"position":5}}""")
        File(tv, "user_kid/trakt.json").writeText("""{"token":"secret"}""")
        val data = Backup.exportData(tv, listOf("", "kid", "../evil"))
        assertEquals(setOf("", "kid"), data.keys().asSequence().toSet())
        assertFalse("Trakt's keys stay on the TV", data.toString().contains("secret"))

        val other = tmp.newFolder()
        data.put("../evil", data.getJSONObject("kid")) // a file that would write outside: left out
        assertEquals(2, Backup.restoreData(other, data))
        assertEquals("""{"tt1":{"at":1}}""", File(other, "vod_watched.json").readText())
        assertEquals("""{"tt2":{"position":5}}""", File(other, "user_kid/vod_progress.json").readText())
        assertFalse(File(other, "user_kid/trakt.json").exists())
        assertFalse(File(other.parentFile, "evil").exists())
    }

    private fun manifest(id: String) = """{"zephyr":1,"id":"$id","version":"1.0.0","name":"Hello","main":"main.js",
        "types":["tv"],"resources":["catalog",{"name":"stream","types":["tv"]}],"idPrefixes":["hello:"]}"""

    private fun install(files: File, id: String) {
        val zip = tmp.newFile()
        ZipOutputStream(zip.outputStream()).use { z ->
            listOf("manifest.json" to manifest(id), "main.js" to "module.exports = {};").forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
            }
        }
        Extensions.install(files, zip)
    }

    @Test fun whatExtensionsAndPluginsKeepGoesAndComesBack() {
        val tv = tmp.newFolder()
        File(tv, "extension-data/org.example.a").mkdirs()
        File(tv, "extension-data/org.example.a/storage.json").writeText("""{"token":"t1"}""")
        File(tv, "extension-data/org.example.a/guides.json").writeText("""{"cache":1}""") // passing state: left out
        File(tv, "nuvio/0123456789abcdef").mkdirs()
        File(tv, "nuvio/0123456789abcdef/my_plugin.storage.json").writeText("""{"login":"x"}""")
        File(tv, "nuvio/0123456789abcdef/my_plugin.js").writeText("code") // downloaded again from its repository
        val extras = Backup.exportExtras(tv, emptySet())
        val storage = extras.getJSONObject("storage")
        assertEquals(setOf("extension-data/org.example.a/storage.json", "nuvio/0123456789abcdef/my_plugin.storage.json"),
            storage.keys().asSequence().toSet())

        storage.put("../evil/storage.json", "{}") // a path outside the folders: left out
        storage.put("extension-data/org.example.b/storage.json", "not json") // not what it should be: left out
        val other = tmp.newFolder()
        assertEquals(2, Backup.restoreExtras(other, extras))
        assertEquals("""{"token":"t1"}""", File(other, "extension-data/org.example.a/storage.json").readText())
        assertEquals("""{"login":"x"}""", File(other, "nuvio/0123456789abcdef/my_plugin.storage.json").readText())
        assertFalse(File(other, "extension-data/org.example.a/guides.json").exists())
        assertFalse(File(other, "extension-data/org.example.b/storage.json").exists())
        assertFalse(File(other.parentFile, "evil").exists())
    }

    @Test fun anExtensionFromAFileComesBackWithItsPackage() {
        val tv = tmp.newFolder()
        install(tv, "org.example.file")
        install(tv, "org.example.online")
        val extras = Backup.exportExtras(tv, setOf("org.example.online"))
        // The one with an address is downloaded again after a restore: only the other one's package is in the backup.
        assertEquals(setOf("org.example.file"), extras.getJSONObject("packages").keys().asSequence().toSet())

        val other = tmp.newFolder()
        assertEquals(1, Backup.restoreExtras(other, JSONObject(extras.toString())))
        assertEquals("org.example.file", Extensions.installed(other, "org.example.file")?.id)
        assertTrue(File(other, "extensions/org.example.file/main.js").isFile)
        assertTrue(File(other, "extensions").listFiles().orEmpty().none { it.name.startsWith(".restore-") })
    }

    @Test fun aPackageThatIsNotAnExtensionIsLeftOut() {
        val other = tmp.newFolder()
        val extras = JSONObject().put("packages", JSONObject().put("org.example.bad", java.util.Base64.getEncoder().encodeToString("not a zip".toByteArray())))
        assertEquals(0, Backup.restoreExtras(other, extras))
        assertEquals(null, Extensions.installed(other, "org.example.bad"))
    }
}

