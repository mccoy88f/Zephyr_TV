package link.mccoy88f.zephyr
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class PlaybackRoutingRegressionTest {
 private fun source(name:String)=File("src/main/java/link/mccoy88f/zephyr/$name.kt").readText()
 @Test fun watchNeverBypassesConfiguredPluginsToOpenTheNuvioApp() {
  assertFalse(source("TitlePages").contains("watchPreferred("))
  assertFalse(source("Spotlight").contains("watchPreferred("))
 }
 @Test fun internalProgressIsIncludedAlongsideExternalProgressOnAndroid7() {
  assertTrue(source("WatchRow").contains("titleProgressCards(hidden) + externalResumeCards(hidden)"))
 }
 @Test fun externalArtworkIsNotSkippedWhenThereIsNoVodOrChannelBadge() {
  assertTrue(source("WatchRow").contains("else if(p.vod==null) fillArt("))
 }
 @Test fun fireTvVoiceButtonStartsTheAssistantBeforeOfferingHelp() {
  assertFalse(source("MainActivity").contains("if (needsFireTvVoiceHelp()) { openFireTvVoiceHelp(); return }"))
 }
}
