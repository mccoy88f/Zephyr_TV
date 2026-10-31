package link.mccoy88f.zephyr
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
internal object NotificationArtwork {
 @Suppress("DEPRECATION")
 fun bitmap(context:Context,n:Notification):Bitmap? {
  fun image(value:Any?):Bitmap? = when(value){
   is Bitmap -> value
   is Icon -> runCatching {val d=value.loadDrawable(context) ?: return@runCatching null
    if(d is BitmapDrawable)d.bitmap else Bitmap.createBitmap(d.intrinsicWidth.coerceIn(1,1280),d.intrinsicHeight.coerceIn(1,720),Bitmap.Config.ARGB_8888).also{d.setBounds(0,0,it.width,it.height);d.draw(Canvas(it))}
   }.getOrNull()
   else -> null
  }
  return image(n.extras.get(Notification.EXTRA_PICTURE)) ?: image(n.extras.get(Notification.EXTRA_LARGE_ICON)) ?: image(n.getLargeIcon())
 }
 fun findThumbnail(cursor:android.database.Cursor,title:String):String? {
  val name=cursor.getColumnIndex("suggest_text_1");val art=cursor.getColumnIndex("suggest_result_card_image")
  if(name<0||art<0)return null
  val matches=linkedMapOf<String,String>()
  while(cursor.moveToNext())if(cursor.getString(name)?.trim()?.equals(title.trim(),true)==true){val url=cursor.getString(art);val id=SmartTubeResultPolicy.videoId(url);if(id!=null)matches[id]=url}
  return matches.values.singleOrNull()
 }
 fun lookup(context:Context,pkg:String,title:String):String? {
  if(!pkg.startsWith("org.smarttube")||!ExternalResume.allowed(pkg))return null
  val signal=android.os.CancellationSignal();val handler=android.os.Handler(android.os.Looper.getMainLooper());val cancel=Runnable{signal.cancel()};handler.postDelayed(cancel,8000)
  return try {val uri=android.net.Uri.Builder().scheme("content").authority(pkg).appendPath("search").appendPath("search_suggest_query").appendPath(title.take(300)).build()
   context.contentResolver.query(uri,null,null,null,null,signal)?.use {findThumbnail(it,title)}
  }catch(e:Exception){null}finally{handler.removeCallbacks(cancel)}
 }
}
