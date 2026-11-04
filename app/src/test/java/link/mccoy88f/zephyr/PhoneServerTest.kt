package link.mccoy88f.zephyr

import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneServerTest {
    private fun call(url: String, body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).use { it.readBytes().decodeToString() }
        return code to text
    }

    /** A server that answers [task] on /t, and 404 everywhere else. */
    private fun serverFor(task: PhoneTask): PhoneServer {
        val temp = Files.createTempDirectory("phone").toFile()
        return PhoneServer.start(0, "test") { r ->
            if (r.path == "/t") r.serve(task, temp) else r.reply(404, TEXT, "Not found.")
        }!!
    }

    @Test fun servesATaskOnItsPath() {
        var got = ""
        val server = serverFor(PhoneTask("<p>page</p>", 100) { f ->
            got = f.readText()
            200 to "thanks"
        })
        try {
            val base = "http://127.0.0.1:${server.port}"
            assertEquals(404, call("$base/1234").first)
            assertEquals(200 to "<p>page</p>", call("$base/t"))
            assertEquals(200 to "thanks", call("$base/t", "my key"))
            assertEquals("my key", got)
            assertEquals(413, call("$base/t", "x".repeat(101)).first)
        } finally {
            server.stop()
        }
        assertTrue(!server.running)
    }

    @Test fun aPageToReadTakesNothing() {
        val server = serverFor(PhoneTask("<p>read</p>", 0) { 200 to "never" })
        try {
            assertEquals(405, call("http://127.0.0.1:${server.port}/t", "x").first)
        } finally {
            server.stop()
        }
    }

    @Test fun acceptsLargePayloadsLikeEncryptedAddonUrls() {
        var received = ""
        val server = serverFor(PhoneTask("<p>addon</p>", 1024L * 1024) { f ->
            received = f.readText()
            200 to "saved"
        })
        try {
            val longUrl = "https://example.com/" + "a".repeat(16384) + "/manifest.json\nadult"
            assertEquals(200 to "saved", call("http://127.0.0.1:${server.port}/t", longUrl))
            assertEquals(longUrl, received)
        } finally {
            server.stop()
        }
    }

    @Test fun aSmallBodyIsReadAsText() {
        var body: String? = "unset"
        val server = PhoneServer.start(0, "test") { r ->
            body = r.body(8)
            r.reply(200)
        }!!
        try {
            call("http://127.0.0.1:${server.port}/x", "12345678")
            assertEquals("12345678", body)
            call("http://127.0.0.1:${server.port}/x", "123456789")
            assertEquals(null, body) // longer than it may be
        } finally {
            server.stop()
        }
    }
}

class PhonePagesTest {
    @Test fun downloadContentCantBreakOutOfThePage() {
        val page = PhonePages.download(PhoneTexts("T", "I", "Save", "", ""), "b.json", "{\"a\":\"</script><b>'x'\\n\"}\nline2").html
        assertTrue(page.contains("\\x3c/script>"))
        assertTrue(!page.contains("</script><b>"))
        assertTrue(page.contains("\\'x\\'"))
        assertTrue(page.contains("\\nline2"))
    }

    @Test fun aPageFollowsTheTvTheme() {
        assertTrue(PhonePages.view(PhoneTexts("T", "", "", "", "", theme = "glass"), "").html.contains("<body class=\"glass\">"))
        assertTrue(PhonePages.view(PhoneTexts("T", "", "", "", ""), "").html.contains("<body class=\"auto\">"))
    }
}
