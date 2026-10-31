package link.mccoy88f.zephyr
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
class NotificationArtworkDeviceTest {
 @Test fun readsModernLargeIconAndPrefersBigPicture(){
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val blue=Bitmap.createBitmap(16,9,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
  val red=Bitmap.createBitmap(16,9,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
  val n=Notification.Builder(c).setSmallIcon(android.R.drawable.ic_media_play).setLargeIcon(Icon.createWithBitmap(blue)).build()
  assertEquals(Color.BLUE,NotificationArtwork.bitmap(c,n)!!.getPixel(0,0))
  n.extras.putParcelable(Notification.EXTRA_PICTURE,red)
  assertEquals(Color.RED,NotificationArtwork.bitmap(c,n)!!.getPixel(0,0))
 }
 @Test fun onlyAnUnambiguousExactProviderTitleSuppliesArtwork(){
  val cols=arrayOf("suggest_text_1","suggest_result_card_image")
  val cursor=android.database.MatrixCursor(cols).apply{addRow(arrayOf("Wanted","https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg"));addRow(arrayOf("Different","https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg"))}
  assertEquals("https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg",NotificationArtwork.findThumbnail(cursor,"Wanted"))
  val duplicate=android.database.MatrixCursor(cols).apply{addRow(arrayOf("Wanted","https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg"));addRow(arrayOf("Wanted","https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg"))}
  assertNull(NotificationArtwork.findThumbnail(duplicate,"Wanted"))
 }
}
