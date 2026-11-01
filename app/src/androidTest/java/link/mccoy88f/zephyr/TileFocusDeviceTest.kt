package link.mccoy88f.zephyr

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class TileFocusDeviceTest {
 @Test fun glassFocusHasAnOpaqueWideRimAndRestoresOnBlur() {
  val i = InstrumentationRegistry.getInstrumentation()
  val a = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  i.runOnMainSync {
   val oldGlass = a.glass
   try {
    a.glass = true
    val v = View(a)
    a.style(v, 8, true)
    v.layout(0, 0, 200, 120)
    fun edge(): Int {
     val bitmap = Bitmap.createBitmap(200,120,Bitmap.Config.ARGB_8888)
     v.foreground.setBounds(0,0,200,120)
     v.foreground.draw(Canvas(bitmap))
     val pixel = bitmap.getPixel(100,2)
     bitmap.recycle()
     return pixel
    }
    v.onFocusChangeListener.onFocusChange(v,true)
    assertTrue("Focus must have an opaque, wide white rim", Color.alpha(edge()) >= 240)
    v.onFocusChangeListener.onFocusChange(v,false)
    assertTrue("The wide rim must disappear on blur", Color.alpha(edge()) < 80)
   } finally { a.glass = oldGlass }
  }
 }
}
