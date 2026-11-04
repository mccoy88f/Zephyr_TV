package link.mccoy88f.zephyr

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray

/**
 * Zephyr MPV: the mpv player, an app of its own (the project's mpv module) installed from the settings, for what
 * Media3 can't play (FFmpeg reads nearly every file: Blu-ray .m2ts, AVI, DTS-HD, TrueHD…). Zephyr TV's own APK stays
 * small. Each app checks the other is signed with the same key before a stream (and its headers) passes.
 * Used, as the user sets it ([mode]): when the player can't play a stream (the default), always, or never.
 */
internal object Mpv {
    const val PACKAGE = "link.mccoy88f.zephyr.mpv"
    private const val ACTIVITY = "$PACKAGE.MpvActivity"

    /** Its file in the GitHub releases, next to ZephyrTV.apk. */
    const val APK = "ZephyrMPV.apk"

    // What it's handed and hands back: the same names as MpvActivity's.
    const val URL = "url"
    const val TITLE = "title"
    const val HEADERS = "headers"
    const val AUDIO = "audio"
    const val POSITION = "position"
    const val DURATION = "duration"
    const val ENDED = "ended"
    const val ERROR = "error"
    const val FAILED = android.app.Activity.RESULT_FIRST_USER

    const val FALLBACK = "fallback"
    const val ALWAYS = "always"
    const val OFF = "off"
    val MODES = listOf(FALLBACK, ALWAYS, OFF)

    fun mode(p: SharedPreferences): String = p.getString(K.MPV_MODE, FALLBACK)?.takeIf { it in MODES } ?: FALLBACK

    /** Android 8 at least: libmpv's. */
    fun supported() = Build.VERSION.SDK_INT >= 26

    fun installed(c: Context): PackageInfo? = runCatching { c.packageManager.getPackageInfo(PACKAGE, 0) }.getOrNull()

    /** Installed and signed as this app is: only then does a stream go to it. */
    @Suppress("DEPRECATION")
    fun trusted(c: Context) = installed(c) != null && c.packageManager.checkSignatures(c.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH

    fun usable(c: Context) = supported() && trusted(c)

    /** A stream it can take: the web's, RTSP, RTMP and UDP; not another app's, nor a playlist in its address, nor ClearKey's DASH. */
    fun takes(s: Stremio.Stream): Boolean = s.app == null && s.clearkey.isEmpty() &&
        (s.url.startsWith("http://", true) || s.url.startsWith("https://", true) || Stremio.OTHER_SCHEMES.any { s.url.startsWith(it, true) })

    /** [headers] as mpv takes them: "Name: value", one each. */
    fun headerLines(headers: Map<String, String>): Array<String> = headers.map { (k, v) -> "$k: $v" }.toTypedArray()

    /** What opens [s] in it, named [title], from [positionMs]. */
    fun intent(s: Stremio.Stream, title: String, positionMs: Long): Intent = Intent().setClassName(PACKAGE, ACTIVITY)
        .putExtra(URL, s.url)
        .putExtra(TITLE, title)
        .putExtra(HEADERS, headerLines(s.headers))
        .putExtra(POSITION, positionMs.coerceAtLeast(0L))
        .apply { s.audio?.let { putExtra(AUDIO, it) } }

    /** Where to download it: the newest release (betas too) that has it. Network; throws when none has. */
    fun latestApk(repo: String): String =
        apkIn(JSONArray(Http.text("https://api.github.com/repos/$repo/releases?per_page=30", mapOf("Accept" to "application/json"))))
            ?: throw java.io.IOException("no release has $APK")

    /** The download address of [APK] in the first of [releases] (GitHub's list, newest first) that has it. */
    internal fun apkIn(releases: JSONArray): String? {
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            if (r.optBoolean("draft")) continue
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                if (a.optString("name") == APK) return a.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: continue
            }
        }
        return null
    }
}
