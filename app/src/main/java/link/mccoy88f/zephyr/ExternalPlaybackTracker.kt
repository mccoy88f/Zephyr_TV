package link.mccoy88f.zephyr

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File

/** Runs while the notification-listener permission is enabled; reads media sessions, never notifications. */
internal class ExternalPlaybackTracker(private val context: Context) {
    private val thread=HandlerThread("ZephyrResume").apply { start() }
    private val worker=Handler(thread.looper)
    private val manager=context.getSystemService(MediaSessionManager::class.java)
    private val component=ComponentName(context,PlayingListener::class.java)
    private val followed=HashMap<android.media.session.MediaSession.Token,Pair<MediaController,MediaController.Callback>>()
    private val changed=MediaSessionManager.OnActiveSessionsChangedListener { follow(it.orEmpty()) }
    private val tick=object:Runnable {
        override fun run() {
            followed.values.forEach { (controller,_) -> runCatching { capture(context,controller) } }
            worker.postDelayed(this,10_000)
        }
    }
    fun start() = worker.post {
        runCatching {
            manager.addOnActiveSessionsChangedListener(changed,component,worker)
            follow(manager.getActiveSessions(component))
            worker.post(tick)
        }
    }
    private fun follow(list: List<MediaController>) {
        val wanted=list.filter { ExternalResume.allowed(it.packageName) }
        val tokens=wanted.map { it.sessionToken }.toSet()
        followed.keys.filter { it !in tokens }.forEach { token ->
            followed.remove(token)?.let { (c,cb) -> runCatching { c.unregisterCallback(cb) } }
        }
        wanted.forEach { c ->
            if(c.sessionToken !in followed) {
                val cb=object:MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) { worker.postDelayed({ runCatching { capture(context,c) } },500) }
                    override fun onPlaybackStateChanged(state: PlaybackState?) { runCatching { capture(context,c) } }
                }
                c.registerCallback(cb,worker);followed[c.sessionToken]=c to cb
            }
            runCatching { capture(context,c) }
        }
    }
    fun stop() {
        worker.removeCallbacksAndMessages(null)
        runCatching { manager.removeOnActiveSessionsChangedListener(changed) }
        worker.post {
            followed.values.forEach { (c,cb) -> runCatching { c.unregisterCallback(cb) } }
            followed.clear();thread.quitSafely()
        }
    }
    companion object {
        fun capture(context: Context,c: MediaController) {
            if(!ExternalResume.allowed(c.packageName))return
            val m=c.metadata ?: return
            val state=c.playbackState ?: return
            val duration=m.getLong(MediaMetadata.METADATA_KEY_DURATION)
            if(duration<=0 || state.state !in setOf(PlaybackState.STATE_PLAYING,PlaybackState.STATE_PAUSED,PlaybackState.STATE_STOPPED))return
            val elapsed=if(state.state==PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime>0)
                ((SystemClock.elapsedRealtime()-state.lastPositionUpdateTime).coerceAtLeast(0)*state.playbackSpeed).toLong() else 0L
            val position=(state.position+elapsed).coerceIn(0,duration)
            val dir=context.filesDir
            val mediaId=m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            var image=listOf(MediaMetadata.METADATA_KEY_ART_URI,MediaMetadata.METADATA_KEY_ALBUM_ART_URI,MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                .firstNotNullOfOrNull { m.getString(it)?.takeIf { s -> s.startsWith("https://") } }
            val video=mediaId?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: SmartTubeResultPolicy.videoId(image)
            if(image==null && video!=null) image="https://i.ytimg.com/vi/$video/hqdefault.jpg"
            val videoUri=video?.let { "https://www.youtube.com/watch?v=$it" }
            val title=m.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
                ?: videoUri?.takeIf { c.packageName.startsWith("org.smarttube") }?.let { ExternalResume.knownTitle(dir,it) }
                ?: return
            val sub=m.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            if(image==null) {
                val bitmap=listOf(MediaMetadata.METADATA_KEY_ART,MediaMetadata.METADATA_KEY_ALBUM_ART,MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    .firstNotNullOfOrNull { m.getBitmap(it) }
                if(bitmap!=null) {
                    val folder=File(dir,"external_resume_art").apply { mkdirs() }
                    val f=File(folder,BackdropCache.artName(c.packageName+title+sub)+".png")
                    if(!f.exists())f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    image=Uri.fromFile(f).toString()
                    folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(50)?.forEach { it.delete() }
                }
            }
            val uri=if(c.packageName.startsWith("org.smarttube") && video!=null) videoUri
                else ExternalResume.knownLink(dir,title)
            ExternalResume.put(dir,ExternalResume.Entry(c.packageName,title,sub,position,duration,System.currentTimeMillis(),image,uri))
        }
    }
}
