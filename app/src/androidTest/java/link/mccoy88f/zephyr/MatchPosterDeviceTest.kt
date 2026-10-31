package link.mccoy88f.zephyr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MatchPosterDeviceTest {
    @Test fun twoBadgesShareOneCardAndTimeChangesInvalidateTheImage() {
        val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"match-probe-"+UUID.randomUUID()).apply { mkdirs() }
        try {
            val args=JSONObject().put("home","Home team").put("away","Away team").put("time","20:30")
                .put("homeBadge","https://example.invalid/red.png").put("awayBadge","https://example.invalid/blue.png")
            val make:(String)->Bitmap?={ url -> Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(if(url.contains("red"))Color.RED else Color.BLUE) } }
            val first=MatchPoster.create(dir,args,make)
            val file=File(Uri.parse(first).path!!)
            val bmp=BitmapFactory.decodeFile(file.path)
            assertEquals(960,bmp.width);assertEquals(540,bmp.height)
            assertEquals(Color.RED,bmp.getPixel(250,220));assertEquals(Color.BLUE,bmp.getPixel(710,220))
            assertEquals(first,MatchPoster.create(dir,args){error("Cached artwork must not redownload")})
            assertNotEquals(first,MatchPoster.create(dir,args.put("time","21:00"),make))
            bmp.recycle()
        } finally { dir.deleteRecursively() }
    }
}
