package link.mccoy88f.zephyr
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
class ExternalResumeTest {
 @get:Rule val temp=TemporaryFolder()
 @Test fun onlyUnfinishedNuvioAndSmartTubeVideosBelongInContinueWatching() {
  assertTrue(ExternalResume.eligible("com.nuvio.tv",60_000,600_000))
  assertTrue(ExternalResume.eligible("org.smarttube.stable",15_000,100_000))
  assertFalse(ExternalResume.eligible("link.mccoy88f.zephyr.debug",60_000,600_000))
  assertFalse(ExternalResume.eligible("org.smarttube.stable",60_000,0))
  assertFalse(ExternalResume.eligible("org.smarttube.stable",98_000,100_000))
 }
 @Test fun progressSurvivesRestartAndCompletedVideosAreRemoved() {
  val d=temp.newFolder()
  val e=ExternalResume.Entry("org.smarttube.stable","Video","",60_000,600_000,10,null,"https://www.youtube.com/watch?v=abcdefghijk")
  ExternalResume.put(d,e);assertEquals(60_000L,ExternalResume.all(d).single().position)
  ExternalResume.put(d,e.copy(position=599_000,at=20));assertTrue(ExternalResume.all(d).isEmpty())
 }
 @Test fun theWatchButtonTargetsTheExactNuvioTitle() {
  assertEquals("nuvio://detail/movie/tt1375666",PreferredWatch.link("movie","tt1375666"))
  assertEquals("nuvio://detail/series/tt0944947",PreferredWatch.link("series","tt0944947"))
 }
 @Test fun aBlankSmartTubeTitleCanBeRecoveredOnlyForTheExactKnownVideo() {
  val d=temp.newFolder();ExternalResume.rememberLink(d,"Video di prova","https://www.youtube.com/watch?v=abcdefghijk")
  assertEquals("video di prova",ExternalResume.knownTitle(d,"https://www.youtube.com/watch?v=abcdefghijk"))
  assertNull(ExternalResume.knownTitle(d,"https://www.youtube.com/watch?v=otherId0000"))
 }
 @Test fun completedVideosRemainInAppHistoryWithoutReturningToContinueWatching() {
  val dir=temp.newFolder();val e=ExternalResume.Entry("org.smarttube.stable","Finished","",98_000,100_000,10,null,"https://www.youtube.com/watch?v=abcdefghijk")
  ExternalResume.put(dir,e)
  assertTrue(ExternalResume.all(dir).isEmpty())
  assertEquals("Finished",ExternalResume.history(dir).single().title)
 }
}
