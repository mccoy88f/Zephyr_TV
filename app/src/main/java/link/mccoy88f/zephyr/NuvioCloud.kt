package link.mccoy88f.zephyr
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID
/** Account data and snapshots stay in the current user's app-private directory. Reads remote progress only. */
internal object NuvioCloud {
 private const val ACCOUNT="nuvio_cloud_account.json"
 private const val SNAPSHOT="nuvio_cloud_resume.json"
 fun account(dir:File)=read(File(dir,ACCOUNT))
 fun snapshot(dir:File)=if(account(dir)==null)null else read(File(dir,SNAPSHOT))
 private fun read(f:File)=runCatching{JSONObject(f.readText())}.getOrNull()
 private fun write(f:File,o:JSONObject){f.parentFile?.mkdirs();val temp=File(f.path+".part");temp.writeText(o.toString());check(temp.renameTo(f))}
 fun forget(dir:File){File(dir,ACCOUNT).delete();File(dir,SNAPSHOT).delete()}
 private fun request(server:JSONObject,path:String,body:JSONObject,token:String?=null):String {
  val headers=mutableMapOf("apikey" to server.getString("publishable_key"),"Accept" to "application/json")
  token?.let{headers["Authorization"]="Bearer $it"}
  val (code,text)=Http.send(server.getString("backend_url").trimEnd('/')+path,body,headers,"POST")
  if(code !in 200..299)throw java.io.IOException("Nuvio request failed ($code)")
  return text
 }
 fun begin():JSONObject {
  val server=JSONObject(Http.open("https://api.nuvio.tv/.well-known/nuvio").bufferedReader().use{it.readText()})
  require(server.getString("backend_url").startsWith("https://"))
  val nonce=UUID.randomUUID().toString()+UUID.randomUUID().toString()
  val params=JSONObject().put("p_device_nonce",nonce).put("p_device_name","Zephyr Nello - sincronizzazione launcher").put("p_device_type","tv").put("p_redirect_base_url","https://nuvio.tv/link")
  return JSONArray(request(server,"/rest/v1/rpc/start_device_login_session",params)).getJSONObject(0).put("nonce",nonce).put("server",server)
 }
 fun poll(dir:File,state:JSONObject):Boolean {
  val server=state.getJSONObject("server");val code=state.getString("device_code");val nonce=state.getString("nonce")
  val status=JSONArray(request(server,"/rest/v1/rpc/poll_tv_login_session",JSONObject().put("p_code",code).put("p_device_nonce",nonce))).getJSONObject(0).optString("status")
  if(status !in setOf("approved","authorized","completed"))return false
  val token=JSONObject(request(server,"/functions/v1/tv-logins-exchange",JSONObject().put("code",code).put("device_nonce",nonce)))
  token.put("server",server).put("profile",1).put("expires_at",System.currentTimeMillis()+token.optLong("expires_in",3600)*1000)
  write(File(dir,ACCOUNT),token);return true
 }
 @Synchronized private fun token(dir:File):JSONObject {
  var a=account(dir)?:throw java.io.IOException("Nuvio not linked")
  if(a.optLong("expires_at")<System.currentTimeMillis()+60000){
   val renewed=JSONObject(request(a.getJSONObject("server"),"/auth/v1/token?grant_type=refresh_token",JSONObject().put("refresh_token",a.getString("refresh_token"))))
   renewed.put("server",a.getJSONObject("server")).put("profile",a.optInt("profile",1)).put("expires_at",System.currentTimeMillis()+renewed.optLong("expires_in",3600)*1000)
   write(File(dir,ACCOUNT),renewed);a=renewed
  };return a
 }
 fun profiles(dir:File):JSONArray {val a=token(dir);return JSONArray(request(a.getJSONObject("server"),"/rest/v1/rpc/sync_pull_profiles",JSONObject(),a.getString("access_token")))}
 fun profile(dir:File,index:Int){require(index in 1..20);val a=account(dir)?:return;a.put("profile",index);write(File(dir,ACCOUNT),a);File(dir,SNAPSHOT).delete()}
 private fun displaySettings(a:JSONObject):JSONObject {
  for(platform in listOf("desktop","tv")){
   val rows=JSONArray(request(a.getJSONObject("server"),"/rest/v1/rpc/sync_pull_profile_settings_blob",JSONObject().put("p_profile_id",a.optInt("profile",1)).put("p_platform",platform),a.getString("access_token")))
   if(rows.length()>0)return NuvioCloudState.displaySettings(rows.getJSONObject(0).optJSONObject("settings_json"))
  };return NuvioCloudState.displaySettings(null)
 }
 fun sync(dir:File):JSONObject {
  val a=token(dir);val rows=JSONArray(request(a.getJSONObject("server"),"/rest/v1/rpc/sync_pull_watch_progress",JSONObject().put("p_profile_id",a.optInt("profile",1)),a.getString("access_token")))
  val old=snapshot(dir);val next=NuvioCloudState.replace(old,rows,System.currentTimeMillis()).put("settings",displaySettings(a));val needed=NuvioCloudState.visible(next).map{it.optString("content_id")}.toSet();val names=old?.optJSONArray("items")?.let{x->(0 until x.length()).map{x.getJSONObject(it)}.associateBy{it.optString("content_id")}}.orEmpty()
  for(i in 0 until rows.length()){
   val row=rows.getJSONObject(i);val id=row.optString("content_id");if(id !in needed)continue;val prior=names[id]
   if(prior?.optString("name")?.isNotBlank()==true){row.put("name",prior.optString("name"));row.put("poster",prior.optString("poster"))}
   else runCatching{Stremio.titleMeta("https://v3-cinemeta.strem.io",row.optString("content_type"),id)?.title?.let{row.put("name",it.name).put("poster",it.poster)}}
  }
  return next.also{write(File(dir,SNAPSHOT),it)}
 }
}
