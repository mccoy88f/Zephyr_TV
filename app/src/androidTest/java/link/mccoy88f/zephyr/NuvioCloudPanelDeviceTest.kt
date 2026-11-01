package link.mccoy88f.zephyr
import android.content.Intent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
class NuvioCloudPanelDeviceTest {
 @Test fun appMenuEntryOpensAVisiblePanel(){
  val i=InstrumentationRegistry.getInstrumentation();val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  i.runOnMainSync{a.closeLauncherSettings();a.showNuvioCloud();assertEquals(View.VISIBLE,a.settingsScrim.visibility)}
 }
}
