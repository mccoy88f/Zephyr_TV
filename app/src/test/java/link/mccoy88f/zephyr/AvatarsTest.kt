package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarsTest {
    // A style DiceBear has (one of those offered) and a seed of letters and digits; anything else isn't an avatar.
    @Test fun readsAnAvatar() {
        assertEquals("lorelei" to "k3J9xQ2mWb", Avatars.parse("lorelei/k3J9xQ2mWb"))
        assertNull(Avatars.parse(null))
        assertNull(Avatars.parse(""))
        assertNull(Avatars.parse("lorelei/"))
        assertNull(Avatars.parse("unknown/abc"))
        assertNull(Avatars.parse("lorelei/Mario Rossi")) // a name, a space: not a seed of ours
        assertNull(Avatars.parse("lorelei/../../x"))
    }

    // DiceBear's PNG, with the seed alone: no name goes there.
    @Test fun itsAddress() {
        val url = Avatars.url("pixel-art/abc123")!!
        assertTrue(url.startsWith("https://api.dicebear.com/9.x/pixel-art/png?seed=abc123&"))
        assertNull(Avatars.url("pixel-art/a b"))
    }

    @Test fun seedsAreRandomAndValid() {
        val a = Avatars.newSeed()
        val b = Avatars.newSeed()
        assertNotEquals(a, b)
        for (style in Avatars.STYLES) assertEquals(style to a, Avatars.parse("$style/$a"))
    }
}
