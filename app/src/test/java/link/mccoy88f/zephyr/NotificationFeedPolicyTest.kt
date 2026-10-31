package link.mccoy88f.zephyr
import org.junit.Assert.*
import org.junit.Test
class NotificationFeedPolicyTest {
 @Test fun onlyRequestedTvRecommendationCardsFromTheirOwnCreatorAreAccepted() {
  assertTrue(NotificationFeedPolicy.accept("org.smarttube.stable","recommendation","org.smarttube.stable"))
  assertTrue(NotificationFeedPolicy.accept("com.nuvio.tv","recommendation","com.nuvio.tv"))
  assertFalse(NotificationFeedPolicy.accept("org.smarttube.stable","msg","org.smarttube.stable"))
  assertFalse(NotificationFeedPolicy.accept("other.app","recommendation","other.app"))
  assertFalse(NotificationFeedPolicy.accept("org.smarttube.stable","recommendation","other.app"))
 }
}
