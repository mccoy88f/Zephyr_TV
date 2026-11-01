package link.mccoy88f.zephyr
import org.json.JSONArray
import org.json.JSONObject
/** Only successful full snapshots may remove remote progress; failures preserve the last snapshot. */
internal object NuvioCloudState {
 fun known(s:JSONObject?):Set<String> = s?.optJSONArray("known")?.let{a->(0 until a.length()).map{a.optString(it)}.toSet()}.orEmpty()
 fun replace(before:JSONObject?,rows:JSONArray,at:Long):JSONObject {
  val items=(0 until rows.length()).map{rows.getJSONObject(it)}
  require(items.all{it.optString("content_id").isNotBlank()&&it.has("position")&&it.has("duration")})
  val ids=known(before)+items.map{it.getString("content_id")}
  return JSONObject().put("at",at).put("known",JSONArray(ids.toList())).put("items",rows)
 }
 fun displaySettings(blob:JSONObject?):JSONObject {
  val f=blob?.optJSONObject("features")?:JSONObject()
  fun objectValue(v:Any?)=when(v){is JSONObject->v;is String->runCatching{JSONObject(v)}.getOrNull();else->null}
  val cw=objectValue(f.opt("continue_watching_settings_payload"));val desktop=objectValue(f.opt("trakt_settings_payload"));val tv=f.optJSONObject("trakt_settings")
  val days=desktop?.optInt("continueWatchingDaysCap",60)?:tv?.optJSONObject("continue_watching_days_cap")?.optInt("value",60)?:60
  val dismissed=cw?.optJSONArray("dismissedNextUpKeys")?:tv?.optJSONObject("dismissed_next_up_keys")?.optJSONArray("value")?:JSONArray()
  return JSONObject().put("daysCap",days.coerceIn(0,365)).put("dismissed",dismissed)
 }
 fun visible(s:JSONObject?):List<JSONObject>{
  val a=s?.optJSONArray("items")?:return emptyList()
  val settings=s.optJSONObject("settings");val days=settings?.optInt("daysCap",0)?:0;val hidden=settings?.optJSONArray("dismissed")?.let{x->(0 until x.length()).map{x.optString(it)}.toSet()}.orEmpty()
  return (0 until a.length()).map{a.getJSONObject(it)}.sortedByDescending{it.optLong("last_watched")}.distinctBy{it.optString("content_id")}.filter{val id=it.optString("content_id");id !in hidden && "$id|${it.optInt("season")}|${it.optInt("episode")}" !in hidden && (days==0||it.optLong("last_watched")>=s.optLong("at")-days*86400000L)}.filter{it.optLong("duration")>0&&it.optLong("position")>=1000&&it.optLong("position")<it.optLong("duration")*95/100}
   .take(30)
 }
 @Suppress("UNUSED_PARAMETER") fun keepLegacy(snapshot:JSONObject?,id:String,playedHere:Boolean)=snapshot==null
}
