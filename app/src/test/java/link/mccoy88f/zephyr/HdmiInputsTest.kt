package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdmiInputsTest {
    @Test fun icons() {
        assertEquals("playstation", hdmiIcon("PlayStation 4", true))
        assertEquals("xbox", hdmiIcon("Xbox Series X", true))
        assertEquals("nintendoswitch", hdmiIcon("NintendoSwitch", true))
        assertEquals("appletv", hdmiIcon("Apple TV", true))
        assertEquals("settop", hdmiIcon("Sky Q", true))
        assertEquals("playback", hdmiIcon("BD Player", true))
        assertEquals("hdmi", hdmiIcon("HDMI 2", false))
        assertEquals("xbox", hdmiIcon("xbox", false)) // a port renamed in TV settings
        assertEquals("settop", hdmiIcon("Cable box", false))
        assertTrue(isAudioDevice("Sonos Arc"))
        assertFalse(isAudioDevice("PlayStation 5"))
    }

    @Test fun row() {
        val none = emptySet<String>()
        assertTrue(inRow("hdmi:2", offline = false, none, none))
        assertFalse(inRow("hdmi:2", offline = true, none, none))
        assertTrue(inRow("hdmi:2", offline = true, none, setOf("hdmi:2")))
        assertFalse(inRow("hdmi:2", offline = false, setOf("hdmi:2"), setOf("hdmi:2")))
    }

    @Test fun hdmiPkg() {
        val port = "HDMI 2"
        val pkg = MainActivity.HDMI + port
        assertTrue(pkg.startsWith(MainActivity.HDMI))
        assertEquals("hdmi:HDMI 2", pkg)
    }

    @Test fun predefinedLogos() {
        assertTrue(isPredefinedHdmiLogo("playstation"))
        assertTrue(isPredefinedHdmiLogo("xbox"))
        assertTrue(isPredefinedHdmiLogo("nintendoswitch"))
        assertTrue(isPredefinedHdmiLogo("appletv"))
        assertFalse(isPredefinedHdmiLogo("hdmi"))
        assertFalse(isPredefinedHdmiLogo("playback"))
        assertFalse(isPredefinedHdmiLogo("settop"))
    }
}
