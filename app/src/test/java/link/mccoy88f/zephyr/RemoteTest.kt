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

    @Test fun idsStayInThePath() {
        assertEquals("tmdb-key", remoteId("tmdb-key"))
        assertEquals("api-key", remoteId("api key"))
        assertTrue(remoteId("a".repeat(80)).length <= 48)
        assertEquals("---", remoteId("///"))
        assertEquals("x", remoteId(""))
    }
}
