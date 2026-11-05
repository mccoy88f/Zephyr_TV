package link.mccoy88f.zephyr

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import link.mccoy88f.zephyr.MainActivity.Prog

/**
 * Present only so Android lets Zephyr TV see the media sessions (what's playing in Spotify, YouTube…): reading them
 * needs an enabled notification listener. It reads no notifications.
 */
class PlayingListener : NotificationListenerService()

// What's playing now, from Android's media sessions: the first cards of the Continue Watching row.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

internal fun MainActivity.listenerComponent() = ComponentName(this, PlayingListener::class.java)

/** The adb command that lets Zephyr TV see what's playing (Android lets only the system grant it; no app can). */
internal const val LISTENER_GRANT = "cmd notification allow_listener link.mccoy88f.zephyr/link.mccoy88f.zephyr.PlayingListener"

/** Whether Zephyr TV may see the media sessions (its listener is allowed). Android 8.0 has no call for it: its setting then. */
internal fun MainActivity.listenerAllowed(): Boolean =
    if (android.os.Build.VERSION.SDK_INT >= 27) {
        getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(listenerComponent())
    } else {
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty().split(':')
            .any { ComponentName.unflattenFromString(it) == listenerComponent() }
    }

/**
 * Low-RAM TVs (Android's own flag, set on most 1 GB boxes): Android turns notification listeners off there, so what's
 * playing may stay hidden whatever is allowed.
 */
internal fun MainActivity.lowRamTv() = getSystemService(android.app.ActivityManager::class.java).isLowRamDevice

/**
 * Where to allow it: Android's notification access list, where the TV has a real screen for it (most hide it);
 * otherwise the adb command, on the panel's page (see showListenerHelp). A low-RAM TV gets the page straight away,
 * saying so.
 */
internal fun MainActivity.allowListener(back: () -> Unit) {
    if (lowRamTv()) return showListenerHelp(back)
    if (!tryScreen(android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) showListenerHelp(back)
}

/** The adb command to allow it, in a box to copy, with a row back; on a low-RAM TV, why it may not work. */
internal fun MainActivity.showListenerHelp(back: () -> Unit) {
    val box = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(14), dp(4), dp(14), dp(12))
        addView(label(getString(if (lowRamTv()) R.string.now_playing_low_ram else R.string.now_playing_help), 13f).apply { maxLines = 8; setLineSpacing(0f, 1.15f) })
        addView(label("adb shell $LISTENER_GRANT", 12f).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            maxLines = 3
            ellipsize = null
            setPadding(0, dp(12), 0, 0)
        })
    }
    showPage(getString(R.string.setting_now_playing), listOf(page(getString(R.string.dialog_done)) { back() }), top = box, back = back)
}

/** The media sessions, or none when Zephyr TV may not see them (its listener is off). */
internal fun MainActivity.mediaSessions(): List<MediaController> = runCatching {
    getSystemService(MediaSessionManager::class.java).getActiveSessions(listenerComponent())
}.getOrDefault(emptyList())

/**
 * What's playing or paused in the media sessions, playing first, as cards: the title, the artist (or album, or
 * app) and whether it's paused, the art, and how far in it is. OK opens the app. Sessions with nothing loaded
 * are left out.
 */
internal fun MainActivity.nowPlaying(): List<Prog> {
    val closed = prefs.getStringSet(K.HIDDEN_PLAYING, emptySet())!!
    val playingAgain = HashSet<String>()
    val list = mediaSessions()
        .filter { it.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING) }
        .filterNot { pausedLong(it) }
        .sortedByDescending { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        .mapNotNull { c ->
            val meta = c.metadata ?: return@mapNotNull null
            val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Closed from the row: left out while it stays paused; playing it again brings it back.
            val key = hideKey(c.packageName, title)
            if (key in closed) {
                if (c.playbackState?.state != PlaybackState.STATE_PLAYING) return@mapNotNull null
                playingAgain += key
            }
            val who = listOf(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM)
                .firstNotNullOfOrNull { meta.getString(it)?.takeIf { s -> s.isNotBlank() } } ?: appLabel(c.packageName)
            val paused = c.playbackState?.state == PlaybackState.STATE_PAUSED
            val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
            val position = c.playbackState?.position ?: 0
            val progress = if (duration > 0 && position > 0) (position * 100 / duration).toInt().coerceIn(1, 100) else 0
            // The art as a bitmap the session holds, kept under a key of its own for the card (see loadPoster); else its address.
            val bitmap = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                .firstNotNullOfOrNull { meta.getBitmap(it) }
            val image = if (bitmap != null) "session:${c.packageName}:${title.hashCode()}".also { posters.put(it, bitmap) }
                else listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                    .firstNotNullOfOrNull { meta.getString(it)?.takeIf { s -> s.isNotBlank() } }
            val sub = if (paused) getString(R.string.playing_paused, who) else who
            val launch = packageManager.getLeanbackLaunchIntentForPackage(c.packageName) ?: packageManager.getLaunchIntentForPackage(c.packageName)
            Prog(title, sub, progress, image, launch?.toUri(android.content.Intent.URI_INTENT_SCHEME), c.packageName, playing = true)
        }
    if (playingAgain.isNotEmpty()) prefs.edit().putStringSet(K.HIDDEN_PLAYING, closed - playingAgain).apply()
    return list
}

/** How long a paused track stays in the row: after that it's something left behind (Spotify keeps it for days). */
private const val PAUSED_KEPT_MS = 6 * 3600_000L

/**
 * Paused for longer than [PAUSED_KEPT_MS]. The session's last position update is when it paused (its clock is
 * elapsedRealtime); an app that keeps updating it simply stays.
 */
private fun pausedLong(c: MediaController): Boolean {
    val s = c.playbackState ?: return false
    if (s.state != PlaybackState.STATE_PAUSED || s.lastPositionUpdateTime <= 0) return false
    return android.os.SystemClock.elapsedRealtime() - s.lastPositionUpdateTime > PAUSED_KEPT_MS
}

/**
 * Hold OK on a playing card, Close: stops it in its app (most end the session; some only pause) and leaves it out
 * of the row until it plays again.
 */
internal fun MainActivity.closePlaying(p: Prog) {
    mediaSessions().firstOrNull { it.packageName == p.pkg }?.let { runCatching { it.transportControls.stop() } }
    val closed = (prefs.getStringSet(K.HIDDEN_PLAYING, emptySet())!! + hideKey(p.pkg, p.title)).toList().takeLast(20).toSet()
    prefs.edit().putStringSet(K.HIDDEN_PLAYING, closed).apply()
    playingRefresh.run()
}

/** Rebuilds the Play queue rows when what's playing changes (another track, play, pause, stop), while they show. */
internal fun MainActivity.watchSessions() {
    if (watching || !listenerAllowed()) return // already, or not allowed yet: tried again on each return home
    val sessions = getSystemService(MediaSessionManager::class.java) ?: return
    fun follow(list: List<MediaController>?) = list.orEmpty().forEach { c ->
        if (c.sessionToken in followed) return@forEach
        var last = c.playbackState?.state
        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = playingChanged()
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state?.state == last) return // the position moving on: nothing to redraw
                last = state?.state
                playingChanged()
            }
            override fun onSessionDestroyed() {
                followed.remove(c.sessionToken)?.first?.let { runCatching { it.unregisterCallback(this) } }
                playingChanged()
            }
        }
        runCatching {
            c.registerCallback(cb, ui)
            followed[c.sessionToken] = c to cb
        }
    }
    val listener = MediaSessionManager.OnActiveSessionsChangedListener { list -> follow(list); playingChanged() }
    runCatching {
        sessions.addOnActiveSessionsChangedListener(listener, listenerComponent(), ui)
        sessionsListener = listener
        follow(mediaSessions())
        watching = true
    }.onFailure { warn("can't follow the media sessions ($it)") }
}

/** Drop session listeners so a recreate() doesn't keep the old activity alive through media callbacks. */
internal fun MainActivity.unwatchSessions() {
    val sessions = getSystemService(MediaSessionManager::class.java)
    sessionsListener?.let { listener ->
        runCatching { sessions?.removeOnActiveSessionsChangedListener(listener) }
    }
    sessionsListener = null
    followed.values.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
    followed.clear()
    watching = false
}

private var watching = false
private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
private val followed = HashMap<android.media.session.MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

private fun MainActivity.playingChanged() {
    ui.removeCallbacks(playingRefresh)
    ui.postDelayed(playingRefresh, 500) // one rebuild for a burst of changes (a new track: metadata, then state)
}
