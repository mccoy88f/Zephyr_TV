package link.mccoy88f.zephyr
import org.junit.Assert.assertEquals
import org.junit.Test

class FireTvUpdateSafetyTest {
    private fun allowed(pkg: String): Boolean = try {
        Class.forName("link.mccoy88f.zephyr.UpdaterKt")
            .getDeclaredMethod("officialUpdatesAllowed", String::class.java).invoke(null,pkg) as Boolean
    } catch (_: NoSuchMethodException) { false }
    @Test fun onlyOfficialPackageMayInstallOfficialUpdates() {
        assertEquals(true,allowed("link.mccoy88f.zephyr"))
        assertEquals(false,allowed("it.ilcovodinello.zephyr.firetv6"))
    }
}
