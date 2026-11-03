package link.mccoy88f.zephyr

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTest {
    @Test fun pathStaysInsideTheToken() {
        assertEquals("", remoteRest("/abc", "abc"))
        assertEquals("", remoteRest("/abc/", "abc"))
        assertEquals("go/tmdb-key", remoteRest("/abc/go/tmdb-key", "abc"))
        assertEquals("api/page", remoteRest("/abc/api/page", "abc"))
        assertNull(remoteRest("/abcd", "abc"))
        assertNull(remoteRest("/abcX/go", "abc"))
        assertNull(remoteRest("/", "abc"))
    }

    @Test fun onlyTheHomeNetwork() {
        fun ip(host: String) = InetAddress.getByName(host)
        assertTrue(isPrivateLan(ip("127.0.0.1")))
        assertTrue(isPrivateLan(ip("10.0.0.2")))
        assertTrue(isPrivateLan(ip("192.168.1.20")))
        assertTrue(isPrivateLan(ip("172.16.0.1")))
        assertTrue(isPrivateLan(ip("172.31.255.255")))
        assertTrue(isPrivateLan(ip("169.254.1.1")))
        assertFalse(isPrivateLan(ip("172.32.0.1")))
        assertFalse(isPrivateLan(ip("8.8.8.8")))
        assertFalse(isPrivateLan(ip("1.1.1.1")))
    }

    @Test fun thePhoneSendsOnlyKnownKeys() {
        assertEquals(android.view.KeyEvent.KEYCODE_DPAD_UP, remoteKeyCode("up"))
        assertEquals(android.view.KeyEvent.KEYCODE_CHANNEL_UP, remoteKeyCode("ch+"))
        assertEquals(null, remoteKeyCode("home"))
        assertEquals(null, remoteKeyCode("vol+"))
        assertTrue("ok" in REMOTE_COMMANDS && "home" in REMOTE_COMMANDS && "vol-" in REMOTE_COMMANDS)
        assertEquals("users", remotePanelName("users"))
        assertEquals(null, remotePanelName("home"))
    }

    @Test fun aRowStepsAndStopsAtItsEnds() {
        assertEquals(1, remoteRowIndex(0, 4, true))
        assertEquals(2, remoteRowIndex(1, 4, true))
        assertEquals(3, remoteRowIndex(3, 4, true))
        assertEquals(0, remoteRowIndex(0, 4, false))
        assertEquals(2, remoteRowIndex(3, 4, false))
        assertEquals(0, remoteRowIndex(null, 4, false))
        assertEquals(1, remoteRowIndex(null, 4, true))
        assertEquals(0, remoteRowIndex(null, 1, true))
        assertEquals(0, remoteRowIndex(5, 0, true))
    }

    @Test fun aGridStaysInsideItsRows() {
        assertEquals(0, remoteGridIndex(null, 8, 6, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(1, remoteGridIndex(null, 8, 6, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(0, remoteGridIndex(0, 8, 6, android.view.KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(6, remoteGridIndex(0, 8, 6, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(5, remoteGridIndex(5, 8, 6, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(6, remoteGridIndex(6, 8, 6, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(7, remoteGridIndex(7, 8, 6, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(1, remoteGridIndex(7, 8, 6, android.view.KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(7, remoteGridIndex(7, 8, 6, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(0, remoteGridIndex(null, 0, 6, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test fun thePhoneUsesTheAdultPin() {
        assertEquals("adult", remotePinStored("adult", "remote"))
        assertEquals("remote", remotePinStored(null, "remote"))
        assertNull(remotePinStored(null, null))
    }

    @Test fun showAddressMovesTheRowsUnderIt() {
        assertEquals(1, rowsAfterRemote(false))
        assertEquals(2, rowsAfterRemote(true))
    }

    @Test fun idsStayInThePath() {
        assertEquals("tmdb-key", remoteId("tmdb-key"))
        assertEquals("api-key", remoteId("api key"))
        assertTrue(remoteId("a".repeat(80)).length <= 48)
        assertEquals("---", remoteId("///"))
        assertEquals("x", remoteId(""))
    }

    @Test fun aPinFromThePhoneHasFourDigits() {
        assertTrue(isPinDigits("0427"))
        assertFalse(isPinDigits("12"))
        assertFalse(isPinDigits("12345"))
        assertFalse(isPinDigits("12a4"))
        assertFalse(isPinDigits("12 4"))
        assertFalse(isPinDigits("١٢٣٤")) // other scripts' digits: the TV can't type them
        assertFalse(isPinDigits(""))
    }

    @Test fun aPressFromAnOlderPageIsRefused() {
        val page = remotePageKey("Settings", listOf("Theme", "Remote access"))
        assertEquals(1, remotePressIndex("$page:1", page))
        assertEquals(0, remotePressIndex(" $page:0\n", page))
        assertNull(remotePressIndex("${page + 1}:1", page))
        assertNull(remotePressIndex("1", page))
        assertNull(remotePressIndex("$page:-1", page))
        assertNull(remotePressIndex("$page:x", page))
        assertNull(remotePressIndex("", page))
    }

    @Test fun thePageKeyFollowsTheRowsNotTheirValues() {
        assertEquals(remotePageKey("Settings", listOf("Theme")), remotePageKey("Settings", listOf("Theme")))
        assertFalse(remotePageKey("Settings", listOf("Theme")) == remotePageKey("Users", listOf("Theme")))
        assertFalse(remotePageKey("Settings", listOf("Theme", "Clock")) == remotePageKey("Settings", listOf("Clock", "Theme")))
        assertFalse(remotePageKey("A", listOf("B")) == remotePageKey("AB", emptyList()))
    }

    @Test fun eachRowSaysHowThePhoneDrawsIt() {
        val on = remoteRowJson("Clock", MainActivity.TOGGLE_ON, "On", "Off")
        assertEquals("switch", on.getString("type"))
        assertTrue(on.getBoolean("on"))
        assertFalse(remoteRowJson("Clock", MainActivity.TOGGLE_OFF, "On", "Off").getBoolean("on"))
        assertEquals("page", remoteRowJson("Theme", MainActivity.SUBMENU, "On", "Off").getString("type"))
        // A value that reads like the word for On is still a value, not a switch.
        val word = remoteRowJson("Mode", "On", "On", "Off")
        assertEquals("value", word.getString("type"))
        assertEquals("On", word.getString("value"))
    }

    @Test fun textIsSafeInThePage() {
        assertEquals("a &lt;b&gt; &amp; &quot;c&quot;", escHtml("a <b> & \"c\""))
        assertEquals("it\\'s \\x3c/script> a b c d", escJs("it's </script>\na\rb\u2028c\u2029d"))
    }
}

