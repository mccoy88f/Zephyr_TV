package link.mccoy88f.zephyr
import android.widget.Toast
import org.json.JSONObject
private val cloudJobs=java.util.Collections.synchronizedSet(HashSet<String>())
private val cloudAttempts=java.util.concurrent.ConcurrentHashMap<String,Long>()
internal fun MainActivity.syncNuvioCloudNow(force:Boolean=false,tell:Boolean=false){
 val user=currentUser().id;val dir=vodDir(user);if(NuvioCloud.account(dir)==null)return
 val now=System.currentTimeMillis();if(!force&&now-(cloudAttempts[dir.path]?:0)<30000)return
 if(!cloudJobs.add(dir.path))return;cloudAttempts[dir.path]=now
 longIo.execute{val result=runCatching{NuvioCloud.sync(dir)};cloudJobs.remove(dir.path)
  ui.post{if(user==currentUser().id){refreshWatchNext();if(watchApp?.startsWith("com.nuvio")==true)queryAppChannels(watchApp)}
   if(tell)Toast.makeText(this,if(result.isSuccess)"Nuvio aggiornato" else "Sincronizzazione Nuvio non riuscita: mantengo i dati precedenti",Toast.LENGTH_LONG).show()
  }
 }
}
internal fun MainActivity.showNuvioCloud(){
 if(settingsScrim.visibility!=android.view.View.VISIBLE)showPanel()
 val dir=vodDir();val a=NuvioCloud.account(dir);val snapshot=NuvioCloud.snapshot(dir)
 val status=if(a==null)"Non collegato" else snapshot?.optLong("at")?.takeIf{it>0}?.let{"Aggiornato alle "+java.text.SimpleDateFormat("HH:mm",java.util.Locale.ITALIAN).format(java.util.Date(it))}?:"Collegato; primo aggiornamento in corso"
 showPage("Nuvio · sincronizzazione",listOfNotNull(
  if(a==null)page("Collega account Nuvio",status){nuvioCloudSignIn()} else page("Aggiorna adesso",status){syncNuvioCloudNow(true,true)},
  if(a!=null)page("Profilo Nuvio", "Profilo "+a.optInt("profile",1)){nuvioCloudProfiles()}else null,
  if(a!=null)page("Scollega Nuvio","Rimuove solo il collegamento dal launcher"){NuvioCloud.forget(dir);showNuvioCloud();refreshWatchNext()}else null,
 ),back={closeLauncherSettings()})
}
internal fun MainActivity.nuvioCloudProfiles(){
 val dir=vodDir();netIo.execute{val result=runCatching{NuvioCloud.profiles(dir)};ui.post{
  val a=result.getOrNull();if(a==null||a.length()==0){syncNuvioCloudNow(true,true);showNuvioCloud();return@post}
  showPage("Scegli lo stesso profilo Nuvio del PC",(0 until a.length()).map{i->val p=a.getJSONObject(i);val index=p.optInt("profile_index",1);page(p.optString("name").ifBlank{"Profilo $index"}){NuvioCloud.profile(dir,index);syncNuvioCloudNow(true,true);showNuvioCloud()}},back={showNuvioCloud()})
 }}
}
internal fun MainActivity.nuvioCloudSignIn(){
 val title="Collega Nuvio al launcher";val dir=vodDir()
 showPage(title,listOf(page("Annulla"){showNuvioCloud()}),top=label("Creo il codice di collegamento…",15f),back={showNuvioCloud()})
 netIo.execute{val attempt=runCatching{NuvioCloud.begin()};ui.post{
  if(!pageShowing(title))return@post
  val state=attempt.getOrNull()?:run{showPage(title,listOf(page("Riprova"){nuvioCloudSignIn()}),top=label("Nuvio non raggiungibile. Riprova senza inserire password nel launcher.",15f),back={showNuvioCloud()});return@post}
  val url=state.getString("verification_uri_complete");val status=label("Codice: "+state.getString("user_code")+"\nIn attesa del collegamento",16f)
  val box=qrBox(url,null,"Scansiona il QR dal telefono e accedi allo stesso account Nuvio del PC. Il launcher leggerà progressi e rimozioni.",status)
  showPage(title,listOf(page("Annulla"){showNuvioCloud()}),top=box,back={showNuvioCloud()})
  val until=System.currentTimeMillis()+10*60000
  fun poll(){if(!pageShowing(title))return;if(System.currentTimeMillis()>until){status.text="Codice scaduto: riapri Collega Nuvio";return}
   netIo.execute{val done=runCatching{NuvioCloud.poll(dir,state)}.getOrDefault(false);ui.post{
    if(done){nuvioCloudProfiles()}else if(pageShowing(title))ui.postDelayed({poll()},3000)
   }}
  };ui.postDelayed({poll()},3000)
 }}
}
internal fun MainActivity.nuvioCloudCards(hidden:Set<String>):List<Pair<Long,MainActivity.Prog>> = NuvioCloudState.visible(NuvioCloud.snapshot(vodDir())).mapNotNull{e->
 val id=e.optString("content_id");val type=if(e.optString("content_type")in setOf("tv","series"))"series"else "movie"
 val name=e.optString("name").ifBlank{Local.get(id)?.title?:id};if(hideKey(VOD_PKG,name)in hidden)return@mapNotNull null
 val video=e.optString("video_id").ifBlank{if(type=="series")"$id:${e.optInt("season")}:${e.optInt("episode")}"else id}
 val pos=e.optLong("position");val dur=e.optLong("duration");val sub=if(type=="series")"S${e.optInt("season")} E${e.optInt("episode")} · "else ""
 val art=if(id.startsWith("tt"))Stremio.art(id,"background/medium")else e.optString("poster").ifBlank{null}
 e.optLong("last_watched") to MainActivity.Prog(name,sub+"${((dur-pos)/60000).coerceAtLeast(1)} min rimanenti",(pos*100/dur).toInt().coerceIn(1,94),art,null,VOD_PKG,
  vod=TitleRef(type,id,name,e.optString("poster").ifBlank{null},art,video=video))
}
