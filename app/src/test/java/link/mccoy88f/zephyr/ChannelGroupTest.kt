package link.mccoy88f.zephyr
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class ChannelGroupTest {
 @Test fun aVisibleGroupRetainsTheUnderlyingChannelsAfterCacheRoundTrip() {
  val group=Stremio.items(JSONObject("""{"metas":[{"id":"group","name":"Bundle","behaviorHints":{"channelGroup":[{"id":"one","name":"One"},{"id":"two","name":"Two"}]}}]}""")).single()
  assertEquals(listOf("one","two"),group.channels.map { it.id })
  val saved=Stremio.cachedItem(Stremio.channelJson(group))
  assertEquals(listOf("one","two"),saved.channels.map { it.id })
 }
 @Test fun quietLiveZappingNeverShowsControlsButVodAndExplicitControlsStillWork() {
  assertFalse(LivePlaybackPolicy.showControls(false,true))
  assertTrue(LivePlaybackPolicy.showControls(false,false))
  assertTrue(LivePlaybackPolicy.showControls(true,true))
 }
}
