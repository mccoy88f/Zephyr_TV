package link.mccoy88f.zephyr

import android.content.Intent
import android.net.Uri

internal fun MainActivity.preferredWatchApp(): MainActivity.App? =
    apps.firstOrNull { it.pkg=="com.nuvio.tv" } ?: resolveAppByPkg("com.nuvio.tv")

internal fun MainActivity.watchPreferred(ref: TitleRef) {
    val app=preferredWatchApp() ?: return
    val uri=PreferredWatch.link(ref.type,ref.id)
    ExternalResume.rememberLink(filesDir,ref.name,uri)
    closeLauncherSettings()
    runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(uri)).setPackage(app.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { launch(app) }
}

/** A saved external session is resumable; opening an app alone never adds it to the row. */
internal fun MainActivity.externalResumeCards(hidden: Set<String>): List<Pair<Long,MainActivity.Prog>> {
    mediaSessions().filter { ExternalResume.allowed(it.packageName) }.forEach { runCatching { ExternalPlaybackTracker.capture(this,it) } }
    return ExternalResume.all(filesDir).filter { hideKey(it.pkg,it.title) !in hidden }.mapNotNull { e ->
        val intent=when {
            e.uri?.startsWith("https://www.youtube.com/watch?")==true && e.pkg.startsWith("org.smarttube") ->
                smartTubeIntent(e.pkg,Uri.parse(e.uri).buildUpon().appendQueryParameter("t",(e.position/1000).toString()).build())
            e.uri?.startsWith("nuvio://")==true && e.pkg.startsWith("com.nuvio") -> Intent(Intent.ACTION_VIEW,Uri.parse(e.uri)).setPackage(e.pkg)
            else -> packageManager.getLeanbackLaunchIntentForPackage(e.pkg) ?: packageManager.getLaunchIntentForPackage(e.pkg)
        } ?: return@mapNotNull null
        val line=listOf(e.sub,getString(R.string.time_left_min,((e.duration-e.position)/60_000).coerceAtLeast(1).toInt())).filter { it.isNotBlank() }.joinToString(" · ")
        val image=e.image ?: e.uri?.takeIf { e.pkg.startsWith("org.smarttube") }?.let { u ->
            Uri.parse(u).getQueryParameter("v")?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        }
        e.at to MainActivity.Prog(e.title,line,e.percent,image,intent.toUri(Intent.URI_INTENT_SCHEME),e.pkg)
    }
}
