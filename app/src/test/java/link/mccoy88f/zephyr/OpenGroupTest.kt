package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenGroupTest {
    private val groups = listOf(2160, 1080, 720, 0)

    @Test fun thePreferredQualitysGroupOpens() {
        assertEquals(1080, openGroup(groups, 1080))
        assertEquals(720, openGroup(groups, 720))
        assertEquals(2160, openGroup(groups, 2160))
    }

    @Test fun theHighestOpensForTheHighest() = assertEquals(2160, openGroup(groups, 0))

    @Test fun elseTheBestBelowItThenTheNearestAbove() {
        assertEquals(1080, openGroup(groups, 1440))
        assertEquals(720, openGroup(listOf(2160, 720), 1080))
        assertEquals(1080, openGroup(listOf(2160, 1080), 480))
    }

    @Test fun streamsWithoutAQualityOnlyWhenAlone() {
        assertEquals(1080, openGroup(listOf(1080, 0), 720))
        assertEquals(0, openGroup(listOf(0), 1080))
        assertNull(openGroup(emptyList(), 1080))
    }
}
