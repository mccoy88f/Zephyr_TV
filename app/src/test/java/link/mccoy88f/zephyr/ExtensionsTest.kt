package link.mccoy88f.zephyr

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtensionsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val manifest = """{"zephyr":1,"id":"org.example.hello","version":"1.0.0","name":"Hello","main":"main.js",
        "types":["tv"],"resources":["catalog",{"name":"stream","types":["tv"]}],"idPrefixes":["hello:"],
        "collectors":["tv","nonsense"],"behaviorHints":{"adult":true}}"""

    private fun zip(vararg files: Pair<String, String>): File {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream()).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
        return f
    }

    private fun refused(why: Extensions.Why, block: () -> Unit) {
        try { block(); fail("expected $why") } catch (e: Extensions.Refused) { assertEquals(why, e.why) }
    }

    @Test fun installsReadsUpdatesAndRemoves() {
        val files = tmp.newFolder()
        val e = Extensions.install(files, zip("manifest.json" to manifest, "main.js" to "module.exports = {};"))
        assertEquals("org.example.hello", e.id)
        assertEquals(listOf("catalog", "stream"), e.resources)
        assertEquals(listOf("tv"), e.collectors)
        assertTrue(e.adult)
        File(Extensions.dataDir(files, e.id), "storage.json").writeText("{}")

        val newer = manifest.replace("1.0.0", "1.1.0")
        val updated = Extensions.install(files, zip("hello/manifest.json" to newer, "hello/main.js" to "//")) // zipped with its folder
        assertEquals("1.1.0", updated.version)
        assertEquals(listOf("org.example.hello"), Extensions.installed(files).map { it.id })
        assertTrue("its data survives an update", File(files, "extension-data/org.example.hello/storage.json").exists())

        Extensions.remove(files, e.id)
        assertTrue(Extensions.installed(files).isEmpty())
        assertFalse(File(files, "extension-data/org.example.hello").exists())
    }

    @Test fun refusesWhatIsntOrCantBe() {
        val files = tmp.newFolder()
        refused(Extensions.Why.DAMAGED) { Extensions.install(files, zip("../evil.js" to "x", "manifest.json" to manifest)) }
        refused(Extensions.Why.NOT_AN_EXTENSION) { Extensions.install(files, zip("manifest.json" to manifest)) } // no main.js
        refused(Extensions.Why.NEWER_STANDARD) { Extensions.install(files, zip("manifest.json" to manifest.replace("\"zephyr\":1", "\"zephyr\":2"), "main.js" to "")) }
        refused(Extensions.Why.NEEDS_FEATURES) {
            Extensions.install(files, zip("manifest.json" to manifest.replace("\"zephyr\":1", "\"zephyr\":1,\"requires\":[\"subtitles\"]"), "main.js" to ""))
        }
        refused(Extensions.Why.NOT_AN_EXTENSION) { Extensions.install(files, zip("manifest.json" to manifest.replace("org.example.hello", "../x"), "main.js" to "")) }
        val published = Extensions.published("https://example.org/x/manifest-zephyrtv.json", JSONObject(
            """{"zephyr":1,"id":"org.example.hello","version":"2.0.0","name":"Hello","package":"hello.zip","sha256":"${"a".repeat(64)}"}"""))
        refused(Extensions.Why.DIFFERENT) { Extensions.install(files, zip("manifest.json" to manifest, "main.js" to ""), published) }
        assertTrue("nothing left behind", File(files, "extensions").listFiles().orEmpty().isEmpty())
    }

    @Test fun publishedAddressesAndPackage() {
        assertEquals("https://example.org/ext/manifest-zephyrtv.json", Extensions.publishedUrl("https://example.org/ext/"))
        assertEquals("https://example.org/ext/manifest-zephyrtv.json", Extensions.publishedUrl(" https://example.org/ext/manifest-zephyrtv.json "))
        assertEquals(null, Extensions.publishedUrl("example.org/ext"))
        val p = Extensions.published("https://example.org/ext/manifest-zephyrtv.json", JSONObject(
            """{"zephyr":1,"id":"a.b","version":"1.0.0","name":"A","package":"a-1.0.0.zip","icon":"icon.png","sha256":"${"F".repeat(64)}"}"""))
        assertEquals("https://example.org/ext/a-1.0.0.zip", p.packageUrl)
        assertEquals("https://example.org/ext/icon.png", p.icon)
        assertEquals("f".repeat(64), p.sha256)
        refused(Extensions.Why.NOT_AN_EXTENSION) {
            Extensions.published("https://example.org/m.json", JSONObject("""{"zephyr":1,"id":"a","version":"1","name":"A","package":"a.zip","sha256":"no"}"""))
        }
    }

    @Test fun versions() {
        assertTrue(Extensions.newer("1.10.0", "1.9.2"))
        assertTrue(Extensions.newer("2.0.0", "2.0.0-beta.3"))
        assertTrue(Extensions.newer("2.0.0-beta.10", "2.0.0-beta.9"))
        assertFalse(Extensions.newer("1.0", "1.0.0"))
        assertFalse(Extensions.newer("1.0.0-beta.1", "1.0.0"))
    }

    @Test fun librariesAreParsedAndKeptAcrossAZipUpdate() {
        val files = tmp.newFolder()
        val libManifest = manifest.replace("\"collectors\"",
            "\"requires\":[\"libraries\"],\"libraries\":[{\"name\":\"toy\",\"version\":\"1.0.0\",\"url\":\"https://example.org/toy.js\",\"sha256\":\"${"b".repeat(64)}\"}],\"collectors\"")
        val e = Extensions.install(files, zip("manifest.json" to libManifest, "main.js" to "module.exports = {};"))
        val libs = Extensions.librariesOf(e.manifest)
        assertEquals(1, libs.size)
        assertEquals("toy", libs[0].name)
        File(e.dir, "lib").mkdirs()
        File(e.dir, "lib/toy.js").writeText("module.exports = { n: 1 };")
        File(e.dir, "lib/index.json").writeText("""[{"name":"toy","version":"1.0.0","url":"https://example.org/toy.js","sha256":"${"b".repeat(64)}","file":"toy.js"}]""")
        assertEquals("toy", Extensions.installedLibraries(e).single().name)
        assertEquals(mapOf("toy" to "module.exports = { n: 1 };"), Extensions.extraModules(e.dir))

        // only the extension version bumps; the library on disk must survive the new zip
        val bumped = libManifest.replace("\"version\":\"1.0.0\",\"name\"", "\"version\":\"1.0.1\",\"name\"")
        val updated = Extensions.install(files, zip("manifest.json" to bumped, "main.js" to "//"))
        assertEquals("1.0.1", updated.version)
        assertTrue(File(updated.dir, "lib/toy.js").isFile)
        assertEquals("toy", Extensions.installedLibraries(updated).single().name)
        assertTrue(Extensions.librariesDue(updated, libs).isEmpty())
        val newerLib = Extensions.Library("toy", "1.1.0", "https://example.org/toy.js", "c".repeat(64))
        assertEquals(listOf("toy"), Extensions.librariesDue(updated, listOf(newerLib)).map { it.name })
    }

    @Test fun shaOfAFile() {
        val f = tmp.newFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Extensions.sha256(f))
    }

    @Test fun aRepositoryListsItsExtensionsAddresses() {
        val url = "https://example.org/repo/repository-zephyrtv.json"
        assertEquals(url, Extensions.publishedUrl(url)) // kept: not taken for a folder
        val m = JSONObject("""{ "zephyr": 1, "name": "Mine", "extensions": ["webcams/manifest-zephyrtv.json",
            "https://other.example/x/manifest-zephyrtv.json", "https://other.example/x/manifest-zephyrtv.json", "ftp://no"] }""")
        assertTrue(Extensions.isRepository(m))
        val r = Extensions.repository(url, m)
        assertEquals("Mine", r.name)
        assertEquals(listOf("https://example.org/repo/webcams/manifest-zephyrtv.json", "https://other.example/x/manifest-zephyrtv.json"), r.extensions)
        // One extension's published manifest isn't a repository; a newer standard's repository is refused.
        assertFalse(Extensions.isRepository(JSONObject("""{ "zephyr": 1, "id": "a.b", "package": "a.zip", "extensions": [] }""")))
        try {
            Extensions.repository(url, JSONObject("""{ "zephyr": 99, "name": "Later", "extensions": [] }"""))
            fail()
        } catch (e: Extensions.Refused) {
            assertEquals(Extensions.Why.NEWER_STANDARD, e.why)
        }
    }
}
