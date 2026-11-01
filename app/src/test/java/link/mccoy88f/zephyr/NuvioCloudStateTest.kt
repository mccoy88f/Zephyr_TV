package link.mccoy88f.zephyr
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class NuvioCloudStateTest {
 private fun row(id:String,at:Long=100)=JSONObject().put("content_id",id).put("content_type","series").put("video_id","$id:1:2").put("season",1).put("episode",2).put("position",60000).put("duration",1200000).put("last_watched",at)
 @Test fun aSuccessfulSnapshotReplacesRowsAndRemembersRemoteRemovals(){
  val first=NuvioCloudState.replace(null,JSONArray().put(row("tt0460649")),1000)
  val empty=NuvioCloudState.replace(first,JSONArray(),2000)
  assertEquals(0,empty.getJSONArray("items").length());assertTrue(NuvioCloudState.known(empty).contains("tt0460649"))
  assertFalse(NuvioCloudState.keepLegacy(empty,"tt0460649",true))
  assertFalse(NuvioCloudState.keepLegacy(empty,"tt9999999",true))
  assertFalse(NuvioCloudState.keepLegacy(empty,"tt9999999",false))
 }
 @Test fun newestEpisodeAndAuthoritativePositionWin(){
  val snapshot=NuvioCloudState.replace(null,JSONArray().put(row("tt0460649",100)).put(row("tt0460649",200).put("episode",3)),1000)
  val rows=NuvioCloudState.visible(snapshot);assertEquals(1,rows.size);assertEquals(3,rows[0].getInt("episode"))
 }
 @Test fun completedTitlesDoNotRemainInContinueWatching(){
  val snapshot=NuvioCloudState.replace(null,JSONArray().put(row("tt0460649").put("position",1200000)),1000)
  assertTrue(NuvioCloudState.visible(snapshot).isEmpty())
 }
 @Test fun aCompletedNewEpisodeDoesNotResurrectAnOlderPausedEpisode(){
  val rows=JSONArray().put(row("tt0460649",100)).put(row("tt0460649",200).put("position",1200000))
  assertTrue(NuvioCloudState.visible(NuvioCloudState.replace(null,rows,1000)).isEmpty())
 }
 @Test fun desktopHiddenItemsAndItsDaysLimitAreRespected(){
  val now=20*86400000L
  val rows=JSONArray().put(row("tt0460649",now)).put(row("tt0000001",1))
  val snapshot=NuvioCloudState.replace(null,rows,now).put("settings",JSONObject().put("daysCap",14).put("dismissed",JSONArray().put("tt0460649|1|2")))
  assertTrue(NuvioCloudState.visible(snapshot).isEmpty())
 }
}
