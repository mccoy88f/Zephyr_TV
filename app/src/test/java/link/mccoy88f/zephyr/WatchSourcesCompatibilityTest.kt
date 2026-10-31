package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchSourcesCompatibilityTest {
    @Test fun nativeExtensionPlaybackIsEligibleForHistory() {
        assertEquals(true, hasHistorySource("zext://sample/manifest.json", emptyList(), listOf("zext://sample/manifest.json")))
        assertEquals(false, hasHistorySource("zext://removed/manifest.json", emptyList(), listOf("zext://sample/manifest.json")))
    }
    @Test fun android7KeepsLocalHistoryWithoutCallingTheAndroid8Provider() {
        val result = watchHistorySources(25, true, { error("Android 8 provider called") }, { listOf("local progress", "last channel") })
        assertEquals(listOf("local progress", "last channel"), result)
    }
    @Test fun missingSystemPermissionDoesNotHideLocalHistory() {
        assertEquals(listOf("local"), watchHistorySources(36, false, { error("Permission denied") }, { listOf("local") }))
    }
    @Test fun brokenSystemProviderDoesNotHideLocalHistory() {
        assertEquals(listOf("local"), watchHistorySources(36, true, { error("Provider absent") }, { listOf("local") }))
    }
    @Test fun modernDevicesRetainBothSources() {
        assertEquals(listOf("system", "local"), watchHistorySources(36, true, { listOf("system") }, { listOf("local") }))
    }
}
