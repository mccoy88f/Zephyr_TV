package link.mccoy88f.zephyr

import android.content.Context
import android.widget.Toast

/**
 * A home screen that keeps crashing leaves the TV with nothing to come back to. Each crash is noted before the
 * process dies. Three within five minutes, and the next start turns WARP and the extensions off and says so.
 * The user turns them on again in settings. The Home button is left as it is.
 */
internal object Safety {
    private var watching = false

    /** Notes each crash (synchronously: the process is on its way out) before Android's own handling. */
    fun watch(ctx: Context) {
        if (watching) return
        watching = true
        val app = ctx.applicationContext
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val p = app.getSharedPreferences(K.FILE, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val kept = (crashTimes(p.getString(K.CRASHES, "").orEmpty(), now - SAFE_WINDOW_MS) + now).takeLast(8)
                p.edit().putString(K.CRASHES, kept.joinToString(",")).commit()
            } catch (_: Throwable) {
            }
            if (before != null) before.uncaughtException(t, e)
        }
    }

    /**
     * Called first thing as Zephyr TV starts. True when this start turned the extras off: the caller says so
     * once the home screen is up.
     */
    fun calm(a: MainActivity): Boolean {
        val p = a.prefs
        val now = System.currentTimeMillis()
        val lately = crashTimes(p.getString(K.CRASHES, "").orEmpty(), now - SAFE_WINDOW_MS)
        if (lately.size < SAFE_CRASHES) return false
        val edit = p.edit().putString(K.CRASHES, "")
        if (p.getBoolean(K.WARP, false)) {
            edit.putBoolean(K.WARP, false)
            Thread { Warp.down(a.applicationContext) }.start()
        }
        val ids = runCatching { Extensions.installed(a.filesDir).map { it.id } }.getOrDefault(emptyList())
        if (ids.isNotEmpty()) {
            val off = (p.getStringSet(K.EXT_OFF, emptySet()) ?: emptySet()) + ids
            edit.putStringSet(K.EXT_OFF, HashSet(off))
        }
        edit.commit()
        return true
    }

    fun tell(a: MainActivity) {
        Toast.makeText(a, a.getString(R.string.toast_safe_mode), Toast.LENGTH_LONG).show()
    }
}
