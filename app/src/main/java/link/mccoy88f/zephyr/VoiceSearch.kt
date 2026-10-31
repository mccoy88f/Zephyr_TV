package link.mccoy88f.zephyr
import android.app.SearchManager
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import android.widget.Toast

internal fun MainActivity.needsFireTvVoiceHelp() = VoiceUiPolicy.needsRemoteHelp(
    Build.MANUFACTURER, Build.MODEL, SpeechRecognizer.isRecognitionAvailable(this))

/** An actionable explanation instead of Android dictation/assistant stubs on Fire OS. */
internal fun MainActivity.openFireTvVoiceHelp(text: String = "") {
    val bridge = ComponentName("com.amazon.firetv.youtube", "link.mccoy88f.zephyr.bridge.SettingsActivity")
    val installed = try { packageManager.getActivityInfo(bridge, 0); true }
        catch (_: PackageManager.NameNotFoundException) { false }
    val rows = buildList {
        if (installed) add(page(getString(R.string.firetv_voice_bridge_settings)) {
            try { startActivity(Intent().setComponent(bridge)) }
            catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this@openFireTvVoiceHelp, R.string.firetv_voice_unavailable, Toast.LENGTH_LONG).show()
            }
        })
        add(page(getString(R.string.firetv_voice_back)) { openSearch(text) })
    }
    showPage(getString(R.string.firetv_voice_title), rows, 0,
        top = label(getString(if (installed) R.string.firetv_voice_help_bridge else R.string.firetv_voice_help_no_bridge), 14f).apply {
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setPadding(0, dp(8), 0, dp(16))
        }) {
        openSearch(text)
    }
}

internal fun MainActivity.acceptVoiceWords(words: String, search: (String) -> Unit = { searchTmdb(it) }) {
    val apps = sortedApps()
    val pkg = VoiceCommand.appForWords(words, apps.map { it.label }.toTypedArray(), apps.map { it.pkg }.toTypedArray())
    val launch = pkg?.let { packageManager.getLeanbackLaunchIntentForPackage(it) ?: packageManager.getLaunchIntentForPackage(it) }
    if (launch != null) { startActivity(launch); return }
    VoiceCommand.contentWords(words).takeIf { it.isNotBlank() }?.let(search)
}

/** A standard ACTION_SEARCH can also be sent by a compatible assistant/recognizer. */
internal fun MainActivity.handleVoiceSearchIntent(i: Intent): Boolean {
    if (i.action != Intent.ACTION_SEARCH) return false
    val query = i.getStringExtra(SearchManager.QUERY)?.take(500) ?: return false
    ui.post {
        if (editing) endEdit()
        if (settingsScrim.visibility == android.view.View.VISIBLE) closeLauncherSettings()
        if (allApps.visibility == android.view.View.VISIBLE) closeAllApps()
        acceptVoiceWords(query)
    }
    return true
}
