package link.mccoy88f.zephyr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject

/** Composes fixture artwork locally. Only public badge URLs are fetched; no external image rendering service. */
internal object MatchPoster {
    private val caches = java.util.concurrent.ConcurrentHashMap<String,BackdropCache>()
    fun create(data: File, args: JSONObject, loadBadge: (String) -> Bitmap? = ::badge): String {
        val home = args.optString("home").trim().take(100)
        val away = args.optString("away").trim().take(100)
        val event=args.optString("event").trim().take(160)
        require(event.isNotEmpty() || (home.isNotEmpty() && away.isNotEmpty())) { "both team names required" }
        val time = args.optString("time").trim().take(32)
        val homeBadge = args.optString("homeBadge")
        val awayBadge = args.optString("awayBadge")
        val channel=args.optString("channel").trim().take(80)
        val key = JSONObject().put("v",5).put("event",event).put("channel",channel).put("home",home).put("away",away).put("time",time)
            .put("homeBadge",homeBadge).put("awayBadge",awayBadge).toString()
        val cache = caches.computeIfAbsent(File(data,"match-posters").path) { BackdropCache(File(it),120,BackdropCache::artName) }
        val file = cache.get(key) {
            val bitmap = Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                paint.shader = LinearGradient(0f,0f,960f,540f,Color.rgb(11,20,36),Color.rgb(29,45,70),Shader.TileMode.CLAMP)
                canvas.drawRect(0f,0f,960f,540f,paint)
                paint.shader = null
                paint.color = Color.rgb(255,201,91)
                canvas.drawRoundRect(RectF(280f,16f,680f,112f),24f,24f,paint)
                paint.typeface = Typeface.create("sans-serif",Typeface.BOLD)
                paint.textAlign = Paint.Align.CENTER
                paint.color = Color.rgb(11,20,36)
                paint.textSize = if(time.length>12 || time.isEmpty()) 32f else 64f
                canvas.drawText(time.ifEmpty { "ORARIO DA DEFINIRE" },480f,87f,paint)
                paint.color = Color.rgb(160,178,206)
                paint.textSize = 48f
                canvas.drawText("VS",480f,241f,paint)
                fun team(name: String, url: String, cx: Float) {
                    paint.color = Color.rgb(246,248,255)
                    canvas.drawRoundRect(RectF(cx-110,122f,cx+110,342f),24f,24f,paint)
                    val logo = if(url.startsWith("https://")) runCatching { loadBadge(url) }.getOrNull() else null
                    if(logo!=null) {
                        val scale = minOf(202f/logo.width,202f/logo.height)
                        val w=logo.width*scale;val h=logo.height*scale
                        canvas.drawBitmap(logo,null,RectF(cx-w/2,232f-h/2,cx+w/2,232f+h/2),paint)
                        logo.recycle()
                    } else {
                        paint.color=Color.rgb(29,45,70);paint.textSize=68f
                        canvas.drawText(name.split(' ').filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString(""),cx,246f,paint)
                    }
                    paint.color=Color.WHITE;paint.textSize=62f
                    fun lines(): List<String> {
                        val out=ArrayList<String>();var current=""
                        val words=name.split(' ').flatMap { word ->
                            if(paint.textSize>40f) return@flatMap listOf(word)
                            val pieces=ArrayList<String>();var piece=""
                            for(ch in word) { if(paint.measureText(piece+ch)>390f && piece.isNotEmpty()) { pieces+=piece;piece="" };piece+=ch }
                            if(piece.isNotEmpty())pieces+=piece;pieces
                        }
                        for(word in words) {
                            val joined=if(current.isEmpty())word else "$current $word"
                            if(current.isNotEmpty() && paint.measureText(joined)>390f) { out+=current;current=word } else current=joined
                        }
                        if(current.isNotEmpty())out+=current
                        return out
                    }
                    while((lines().size>2 || lines().any { paint.measureText(it)>390f }) && paint.textSize>40f) paint.textSize-=2f
                    val text=lines().take(2)
                    text.forEachIndexed { i,line -> canvas.drawText(line,cx,if(text.size==1)432f else 407f+i*70f,paint) }
                }
                if(event.isBlank()) {team(home,homeBadge,250f);team(away,awayBadge,710f)} else {
                    val badge=runCatching { loadBadge(homeBadge) }.getOrNull()
                    badge?.let { canvas.drawBitmap(it,null,RectF(350f,125f,610f,275f),paint) }
                    paint.color=Color.WHITE;paint.textSize=58f
                    val words=event.split(' ');val lines=ArrayList<String>();var line=""
                    for(word in words) {val next=if(line.isBlank())word else "$line $word";if(paint.measureText(next)>850f && line.isNotBlank()){lines+=line;line=word}else line=next}
                    if(line.isNotBlank())lines+=line
                    lines.take(3).forEachIndexed { i,text -> canvas.drawText(text,480f,345f+i*65f,paint) }
                }
                if(channel.isNotBlank()) {
                    paint.color=Color.rgb(255,210,88);paint.textSize=38f
                    while(paint.measureText(channel)>890f && paint.textSize>24f)paint.textSize-=2f
                    canvas.drawText(channel,480f,526f,paint)
                }
                val bytes=ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
                ByteArrayInputStream(bytes)
            } finally { bitmap.recycle() }
        }
        return Uri.fromFile(file).toString()
    }

    private fun badge(url: String): Bitmap? {
        val bytes=Http.open(url).use { it.readCapped(2L*1024*1024) }
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        if(options.outWidth<=0 || options.outHeight<=0)return null
        options.inJustDecodeBounds=false
        options.inSampleSize=1
        while(maxOf(options.outWidth,options.outHeight)/options.inSampleSize>512)options.inSampleSize*=2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
    }
}
