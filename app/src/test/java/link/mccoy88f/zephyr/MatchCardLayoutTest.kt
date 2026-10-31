package link.mccoy88f.zephyr
import org.junit.Assert.*
import org.junit.Test
class MatchCardLayoutTest {
 private val image="file:///data/user/0/app/files/extension-data/org.example/match-posters/a0123456789abcdef01234567"
 @Test fun fixturesUseAtMostThreeLargeCardsAcrossAndOtherRowsKeepTheirSetting() {
  assertEquals(3,MatchCardLayout.columns(image,4))
  assertEquals(1,MatchCardLayout.columns(image,1))
  assertEquals(4,MatchCardLayout.columns("https://example.org/logo.png",4))
 }
 @Test fun onlyLocallyComposedFixturesReceiveFullFrameArtwork() {
  assertTrue(MatchCardLayout.isFixture(image))
  assertFalse(MatchCardLayout.isFixture("https://example.org/match-posters/a.png"))
  assertFalse(MatchCardLayout.isFixture(null))
 }
}
