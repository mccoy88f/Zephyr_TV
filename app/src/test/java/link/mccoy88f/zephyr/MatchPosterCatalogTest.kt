package link.mccoy88f.zephyr
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class MatchPosterCatalogTest {
 @Test fun onlyTheProducingExtensionMayUseItsGeneratedLocalPoster() {
  val uri="file:///data/user/0/link.mccoy88f.zephyr.debug/files/extension-data/org.example.fixture/match-posters/a0123456789abcdef01234567"
  val page=JSONObject().put("metas",org.json.JSONArray().put(JSONObject().put("id","fixture").put("name","Home vs Away").put("poster",uri)))
  assertEquals(uri,Stremio.items(page,"org.example.fixture")[0].logo)
  assertNull(Stremio.items(page)[0].logo)
  assertNull(Stremio.items(page,"org.example.other")[0].logo)
 }
}
