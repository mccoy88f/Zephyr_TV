package link.mccoy88f.zephyr
import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.media.MediaMetadata
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import java.io.File
import java.util.UUID
class ExternalPlaybackDeviceTest {
 @Test fun progressReplacementWorksOnAndroid7WithoutTouchingUserHistory(){
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val d=File(c.cacheDir,"resume-probe-"+UUID.randomUUID()).apply { mkdirs() }
  try{val e=ExternalResume.Entry("org.smarttube.stable","Probe","",30_000,300_000,1,null,null)
   ExternalResume.put(d,e);ExternalResume.put(d,e.copy(position=60_000));assertEquals(60_000L,ExternalResume.all(d).single().position)
   ExternalResume.put(d,e.copy(position=300_000));assertTrue(ExternalResume.all(d).isEmpty())
  }finally{d.deleteRecursively()}
 }
 @Test fun inspectAnExplicitlyRequestedExternalPlaybackSession(){
  assumeTrue(InstrumentationRegistry.getArguments().getString("resumeAudit")=="true")
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  val manager=c.getSystemService(MediaSessionManager::class.java)
  val list=manager.getActiveSessions(ComponentName(c,PlayingListener::class.java)).filter { ExternalResume.allowed(it.packageName) }
  for(controller in list){
   val m=controller.metadata;val state=controller.playbackState
   i.sendStatus(0,Bundle().apply{putString("resume_audit","pkg=${controller.packageName};duration=${m?.getLong(MediaMetadata.METADATA_KEY_DURATION)};state=${state?.state};position=${state?.position};keys=${m?.keySet()?.joinToString(",")}")})
   ExternalPlaybackTracker.capture(c,controller)
   i.sendStatus(0,Bundle().apply{putString("resume_result","target=${c.packageName};title=${!m?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()};display=${!m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNullOrBlank()};file=${File(c.filesDir,"external_resume.json").exists()};entries=${ExternalResume.all(c.filesDir).size}")})
  }
 }
}
