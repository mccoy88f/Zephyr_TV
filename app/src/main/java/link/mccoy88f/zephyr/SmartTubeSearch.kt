package link.mccoy88f.zephyr

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import link.mccoy88f.zephyr.MainActivity.Prog

private val smartTubePackages = listOf("org.smarttube.stable", "org.smarttube.beta", "app.smarttube.fdroid")
internal data class YouTubeVideo(val id: String, val title: String, val subtitle: String)

internal fun MainActivity.smartTubeSearchPackage(): String? = smartTubePackages.firstOrNull { pkg ->
    packageManager.resolveContentProvider(pkg, 0)?.let { it.packageName == pkg && it.exported } == true
}

internal fun smartTubeIntent(pkg: String, uri: Uri) = Intent(Intent.ACTION_VIEW, uri)
    .setComponent(ComponentName(pkg, "com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"))
    .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Queries SmartTube's exported global-search provider, off the film/series worker. */
internal fun MainActivity.startYouTubeSearch(query: String, generation: Int) {
    youtubeCancellation?.cancel()
    youtubeQuery = null
    youtubePackage = null
    youtubeVideos = emptyList()
    youtubeLoading = false
    youtubeFailed = false
    val pkg = smartTubeSearchPackage()
    if (!SmartTubeResultPolicy.shouldQuery(query, searchFilter.type, adultSearch() == SearchFilter.ADULT_ONLY, pkg != null)) return
    youtubePackage = pkg
    youtubeQuery = query
    youtubeLoading = true
    val cancellation = CancellationSignal()
    youtubeCancellation = cancellation
    val resolver = applicationContext.contentResolver
    ui.postDelayed({
        if (searchCount == generation && searchQuery != null && youtubeLoading) {
            youtubeLoading = false
            youtubeFailed = true
            cancellation.cancel()
            refreshYouTubeResults()
        }
    }, 10000)
    youtubeIo.execute {
        if (cancellation.isCanceled) return@execute
        val result = runCatching {
            val uri = Uri.Builder().scheme("content").authority(pkg).appendPath("search")
                .appendPath("search_suggest_query").appendPath(query.take(500)).build()
            val found = linkedMapOf<String, YouTubeVideo>()
            resolver.query(uri, null, null, null, null, cancellation)?.use { cursor ->
                fun value(column: String): String? = cursor.getColumnIndex(column).takeIf { it >= 0 }
                    ?.let { if (cursor.isNull(it)) null else cursor.getString(it) }
                while (cursor.moveToNext() && found.size < 20) {
                    val id = SmartTubeResultPolicy.videoId(value("suggest_result_card_image")) ?: continue
                    val duration = value("suggest_duration")?.toLongOrNull() ?: -1
                    val live = value("suggest_is_live").let { it == "1" || it.equals("true", true) }
                    if (!SmartTubeResultPolicy.playable(duration, live)) continue
                    val title = value("suggest_text_1")?.trim()?.take(300)?.takeIf { it.isNotEmpty() && it != "NULL" } ?: continue
                    found.putIfAbsent(id, YouTubeVideo(id, title, value("suggest_text_2").orEmpty().take(500)))
                }
            }
            found.values.toList()
        }
        ui.post {
            if (searchCount != generation || searchQuery == null || cancellation.isCanceled) return@post
            youtubeVideos = result.getOrDefault(emptyList())
            youtubeFailed = result.isFailure
            youtubeLoading = false
            refreshYouTubeResults()
        }
    }
}

private fun MainActivity.refreshYouTubeResults() {
    if (watchApp == SEARCH_PKG || currentFocus?.tag == SEARCH_PKG) {
        watchKey = null
        showSearchResults()
    }
}

internal fun MainActivity.youTubeSearchSection(): Section? {
    val query = youtubeQuery ?: return null
    val pkg = youtubePackage ?: return null
    val items = youtubeVideos.map { video ->
        val uri = Uri.parse("https://www.youtube.com/watch").buildUpon().appendQueryParameter("v", video.id).build()
        Prog(video.title, video.subtitle, 0, "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg",
            smartTubeIntent(pkg, uri).toUri(Intent.URI_INTENT_SCHEME), pkg)
    } + Prog(getString(R.string.youtube_search_more), getString(when {
        youtubeLoading -> R.string.youtube_search_loading
        youtubeFailed -> R.string.youtube_search_unavailable
        youtubeVideos.isEmpty() -> R.string.youtube_search_empty
        else -> R.string.youtube_search_open
    }), 0, null, smartTubeIntent(pkg, Uri.parse("youtube://search").buildUpon()
        .appendQueryParameter("query", query).build()).toUri(Intent.URI_INTENT_SCHEME), pkg)
    return Section("youtube-search", query, getString(R.string.youtube_search_heading, query), items)
}
