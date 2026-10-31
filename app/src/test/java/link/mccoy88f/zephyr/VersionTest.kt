package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test fun compares() {
        assertTrue(newerVersion("1.2", "1.1"))
        assertTrue(newerVersion("1.10", "1.9"))
        assertTrue(newerVersion("1.2.1", "1.2"))
        assertTrue(newerVersion("2.0", "1.9.9"))
        assertFalse(newerVersion("1.2", "1.2"))
        assertFalse(newerVersion("1.2", "1.2.0"))
        assertFalse(newerVersion("1.1", "1.2"))
    }

    @Test fun putsBetasBeforeTheirRelease() {
        assertTrue(newerVersion("4.0.0-beta.1", "3.1"))
        assertTrue(newerVersion("4.0.0-beta.2", "4.0.0-beta.1"))
        assertTrue(newerVersion("4.0", "4.0.0-beta.2"))
        assertTrue(newerVersion("4.0.0", "4.0.0-beta.1"))
        assertFalse(newerVersion("4.0.0-beta.1", "4.0.0"))
        assertFalse(newerVersion("4.0.0-beta.1", "4.0.0-beta.1"))
        assertFalse(newerVersion("3.1", "4.0.0-beta.1"))
    }

    @Test fun findsTheNewestRelease() {
        assertEquals("4.0.0-beta.2", Updater.newest(listOf("3.1.1", "4.0.0-beta.2", "4.0.0-beta.1", "3.1")))
        assertEquals("4.0", Updater.newest(listOf("4.0.0-beta.2", "4.0")))
        assertNull(Updater.newest(emptyList()))
    }

    @Test fun offersLatestReleaseWhenExitingBeta() {
        // Normal upgrade
        assertTrue(isUpdateAvailable("3.6.0", "3.5.6", receiveBetas = false))
        assertTrue(isUpdateAvailable("3.6.0", "3.6.0-beta.3", receiveBetas = false))

        // Exiting beta: user is on a beta, receiveBetas is false, offers latest non-beta stable release
        assertTrue(isUpdateAvailable("3.5.6", "3.6.0-beta.3", receiveBetas = false))
        assertFalse(isUpdateAvailable("3.6.0-beta.4", "3.6.0-beta.3", receiveBetas = false))
        assertFalse(isUpdateAvailable("3.5.6", "3.5.6", receiveBetas = false))
    }
}
