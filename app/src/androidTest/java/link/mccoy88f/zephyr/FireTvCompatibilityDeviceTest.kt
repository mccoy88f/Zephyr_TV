package link.mccoy88f.zephyr

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FireTvCompatibilityDeviceTest {
    @Test fun replacesPlaybackProgressOnTheDeviceWithoutTouchingUserHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir,"firetv-compat-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            fun entry(position: Long, at: Long) = VodProgress.Entry("probe", "movie", "tt0000", "Compatibility probe", "", null, position, 3_600_000L, at)
            VodProgress.put(dir,entry(60_000L,1))
            VodProgress.put(dir,entry(120_000L,2))
            assertEquals(120_000L,VodProgress.position(dir,"probe"))
            assertEquals(0L,java.time.Instant.parse("1970-01-01T00:00:00Z").toEpochMilli())
            if (android.os.Build.VERSION.SDK_INT < 26) assertFalse(HomePolicy.supportsTvChannels(android.os.Build.VERSION.SDK_INT))
        } finally { dir.deleteRecursively() }
    }
}
