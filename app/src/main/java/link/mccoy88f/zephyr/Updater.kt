package link.mccoy88f.zephyr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** The action MainActivity gets an install's outcome under (from InstallResult). */
internal const val UPDATE_STATUS = "link.mccoy88f.zephyr.UPDATE_STATUS"
internal fun officialUpdatesAllowed(pkg: String) = pkg == "link.mccoy88f.zephyr"

/**
 * "1.10" > "1.9" > "1.2.1" > "1.2": compared number by number, missing parts count as 0. A beta comes before its
 * release, and betas in their order: "4.0" > "4.0.0-beta.2" > "4.0.0-beta.1" > "3.1".
 */
internal fun newerVersion(a: String, b: String): Boolean {
    fun numbers(s: String) = s.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    fun compare(x: List<Int>, y: List<Int>): Int {
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }
    val d = compare(numbers(a.substringBefore('-')), numbers(b.substringBefore('-')))
    if (d != 0) return d > 0
    val (pa, pb) = a.substringAfter('-', "") to b.substringAfter('-', "")
    return when {
        pa == pb -> false
        pa.isEmpty() -> true // the release, after its betas
        pb.isEmpty() -> false
        else -> compare(numbers(pa), numbers(pb)) > 0
    }
}

/**
 * Updates from this fork's GitHub releases: [latest] finds the newest version, [install] downloads its ZephyrTV.apk
 * into an install session. Android checks the APK is signed like the installed app, so only Zephyr TV's own key can
 * update it. Network: call both off the UI thread.
 */
internal object Updater {
    /**
     * The newest release's version ("1.4"), from its tag ("v1.4"); with [beta], the newest of the betas too
     * (pre-releases, which releases/latest leaves out). Throws when GitHub can't be asked.
     */
    fun latest(repo: String, beta: Boolean = false): String {
        if (!beta) return Http.json("https://api.github.com/repos/$repo/releases/latest").getString("tag_name").removePrefix("v")
        val list = org.json.JSONArray(Http.text("https://api.github.com/repos/$repo/releases?per_page=20", mapOf("Accept" to "application/json")))
        return newest((0 until list.length()).map { list.getJSONObject(it) }.filter { !it.optBoolean("draft") }.map { it.getString("tag_name").removePrefix("v") })
            ?: throw java.io.IOException("no releases")
    }

    /** The newest of [versions], or null when there are none. */
    internal fun newest(versions: List<String>): String? = versions.fold(null as String?) { best, v -> if (best == null || newerVersion(v, best)) v else best }

    /** Downloads release [ver] and hands it to Android, which reports to InstallResult. Throws if the download fails. */
    fun install(context: Context, repo: String, ver: String) {
        require(officialUpdatesAllowed(context.packageName)) { "A test build cannot install an APK for a different package" }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        // Android 12+ can skip the confirm box when we're the app's installer; otherwise Android asks.
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                Http.open("https://github.com/$repo/releases/download/v$ver/ZephyrTV.apk").use { input ->
                    session.openWrite("ZephyrTV.apk", 0, -1).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
            // Mutable: the installer adds the status to it. Explicit, to a receiver only Android can reach.
            val done = PendingIntent.getBroadcast(context, 0, Intent(context, InstallResult::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(done.intentSender)
        }
    }
}

/**
 * Where the update's install session reports back. It isn't exported, so only Android (through the PendingIntent
 * Zephyr TV handed it) can call it. That matters: the report can carry an Intent to start (Android's confirm box), and
 * MainActivity, which every app may start, must never launch an Intent someone else put in.
 */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        // The outcome only (no Intent): MainActivity resets its "Updating…" row and says if it failed.
        c.startActivity(Intent(c, MainActivity::class.java).setAction(UPDATE_STATUS)
            .putExtra(PackageInstaller.EXTRA_STATUS, status)
            .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class Updated : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
