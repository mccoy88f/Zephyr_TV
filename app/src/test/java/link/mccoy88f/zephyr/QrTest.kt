package link.mccoy88f.zephyr

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class QrTest {
    /** The worked example from the QR spec (ISO 18004 annex I): "01234567", version 1-M. */
    @Test fun reedSolomon() {
        val data = intArrayOf(0x10, 0x20, 0x0C, 0x56, 0x61, 0x80, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11)
        val ec = intArrayOf(0xA5, 0x24, 0xD4, 0xC1, 0xED, 0x36, 0xC7, 0x87, 0x2C, 0x55)
        assertArrayEquals(ec, Qr.rs(data, 10))
    }

    private fun sha256(m: Array<BooleanArray>) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(m.joinToString("") { r -> r.joinToString("") { if (it) "1" else "0" } }.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /**
     * Trakt's sign-in address with its PKCE challenge (121 bytes) needs version 7. The modules match
     * the same code (version 7-M, byte mode, mask 0) as python-qrcode 7.4.2 draws it: its six alignment patterns and its version information too.
     */
    @Test fun version7MatchesAReferenceEncoder() {
        val url = "https://ztv.mccoy88f.link/traktauth?start=AbCdEfGhIjKlMnOpQrStUvWxYz012345&cc=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        val m = Qr.encode(url)
        org.junit.Assert.assertEquals(45, m.size)
        org.junit.Assert.assertEquals("8755762dcc60807265c3c45e8385fdcf1fc3d510a546ccbf582a0b5c1dcd735d", sha256(m))
    }

    /** A short address stays in version 2, as before, the same modules as python-qrcode 7.4.2 draws. */
    @Test fun version2StaysTheSame() {
        val m = Qr.encode("http://192.168.1.20:8080/")
        org.junit.Assert.assertEquals(25, m.size)
        org.junit.Assert.assertEquals("3e65db30ab2f1a518c8b1ac85d889378a964524245ea794b2c2f326de6368ecc", sha256(m))
    }
}
