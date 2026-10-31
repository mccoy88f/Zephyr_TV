package link.mccoy88f.zephyr
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.service.notification.StatusBarNotification
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Legacy Android TV recommendation cards, restricted to the two requested app families. */
internal object NotificationFeed {
 private data class Entry(val key:String,val pkg:String,val title:String,val sub:String,val image:String?,val intent:PendingIntent,val at:Long)
 private val entries=ConcurrentHashMap<String,Entry>()
 private val worker=Executors.newSingleThreadExecutor()
 private val artWorker=Executors.newSingleThreadExecutor()
 private var activity=WeakReference<MainActivity>(null)
 private val changed=Runnable {activity.get()?.let { a -> a.refreshWatchNext();a.watchApp?.takeIf { ExternalResume.allowed(it) }?.let { a.queryAppChannels(it) } }}
 private fun key(s:StatusBarNotification)=MessageDigest.getInstance("SHA-256").digest(s.key.toByteArray()).joinToString(""){"%02x".format(it)}.take(24)
 private fun notifyChanged(){activity.get()?.let { it.ui.removeCallbacks(changed);it.ui.postDelayed(changed,300) }}
 fun attach(a:MainActivity){activity=WeakReference(a);notifyChanged()}
 fun detach(a:MainActivity){if(activity.get()===a){a.ui.removeCallbacks(changed);activity.clear()}}
 @Suppress("DEPRECATION")
 fun capture(context:Context,s:StatusBarNotification){
  val n=s.notification;val intent=n.contentIntent ?: return
  if(!NotificationFeedPolicy.accept(s.packageName,n.category,intent.creatorPackage))return
  val title=n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: return
  val sub=n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
  val id=key(s)
  val bitmap=NotificationArtwork.bitmap(context,n)
  worker.execute {
   var image:String?=null
   if(bitmap!=null) runCatching {
    val dir=File(context.filesDir,"notification_feed_art").apply{mkdirs()};val file=File(dir,"$id.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };image=Uri.fromFile(file).toString()
    dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(80)?.forEach { it.delete() }
   }
   entries[id]=Entry(id,s.packageName,title,sub,image,intent,s.postTime);notifyChanged()
   if(image==null&&s.packageName.startsWith("org.smarttube"))artWorker.execute {
    val cache=File(context.filesDir,"notification_feed_art").apply{mkdirs()};val saved=File(cache,"$id.url")
    val thumb=runCatching{saved.takeIf{it.exists()}?.readText()}.getOrNull() ?: NotificationArtwork.lookup(context,s.packageName,title)
    if(thumb!=null){runCatching{saved.writeText(thumb)};entries.computeIfPresent(id){_,old ->if(old.title==title)old.copy(image=thumb)else old};notifyChanged()}
   }
  }
 }
 fun remove(s:StatusBarNotification){worker.execute {entries.remove(key(s));notifyChanged()}}
 fun cards(pkg:String)=entries.values.filter { it.pkg==pkg }.sortedByDescending { it.at }.take(30).map {
  MainActivity.Prog(it.title,it.sub,0,it.image,"nello-feed://"+it.key,it.pkg)
 }
 fun open(p:MainActivity.Prog):Boolean {
  val id=p.intent?.takeIf { it.startsWith("nello-feed://") }?.removePrefix("nello-feed://") ?: return false
  val e=entries[id]?.takeIf { it.pkg==p.pkg } ?: return false
  return runCatching { e.intent.send();true }.getOrDefault(false)
 }
}

internal fun MainActivity.homeFeedSections(hidden:Set<String>):List<Section> = apps.filter { ExternalResume.allowed(it.pkg) }.flatMap { app ->
 if(android.os.Build.VERSION.SDK_INT>=26)runCatching { appSections(app.pkg,hidden) }.getOrDefault(emptyList())
 else NotificationFeed.cards(app.pkg).filter { hideKey(app.pkg,it.title) !in hidden }.takeIf { it.isNotEmpty() }?.let { listOf(Section("legacy-tv-feed","TV",app.label+" - Consigliati",it)) }.orEmpty()
}
