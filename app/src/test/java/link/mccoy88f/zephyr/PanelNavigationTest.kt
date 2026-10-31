package link.mccoy88f.zephyr
import org.junit.Assert.*
import org.junit.Test
class PanelNavigationTest {
 @Test fun detailsCanReachWatchEvenOnAPageWithSeasonSwitching() {
  assertEquals(PanelNavigation.Action.FOCUS,PanelNavigation.route(true,true))
  assertEquals(PanelNavigation.Action.FOCUS,PanelNavigation.route(true,false))
 }
 @Test fun aPageWithoutSideActionsDoesNotSwallowTheNormalRemoteEvent() {
  assertEquals(PanelNavigation.Action.DEFAULT,PanelNavigation.route(false,false))
  assertEquals(PanelNavigation.Action.PAGE,PanelNavigation.route(false,true))
 }
}
