package link.mccoy88f.zephyr.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * In the foreground while a download has work to do, so Android doesn't stop the engine halfway through a film:
 * every two seconds the downloads' progress is looked at (see Engine.tick), and once none is left it stops. Zephyr TV
 * starts it after asking for a download (an app on screen may; the engine, in the background, may not since Android 12).
 * Exported without a permission: all it can be made to do is look at the downloads Zephyr TV asked for.
 */
class DownloadService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val going = runCatching { Engine.tick() }.getOrDefault(false)
            if (!going) return stop()
            notify(notification())
            ui.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Engine.use(this)
        val n = notification()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        }.onFailure { Engine.log("not in the foreground: $it") }
        ui.removeCallbacks(tick)
        ui.post(tick)
        return START_STICKY
    }

    // Android 15 lets a data sync run six hours a day in the foreground: past them it goes on in the background, for
    // as long as Android leaves the engine be.
    override fun onTimeout(startId: Int, fgsType: Int) = stop()

    private fun stop() {
        ui.removeCallbacks(tick)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun notify(n: Notification) = runCatching { getSystemService(NotificationManager::class.java)?.notify(ID, n) }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW))
        val going = Engine.downloads().filter { !it.done && !it.failed }
        val total = going.sumOf { it.total }
        val done = going.sumOf { Engine.progress(it) }
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.downloading, going.size, percent))
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "downloads"

        /** Started when a download has work to do; it stops itself once none has. */
        fun update(c: Context) {
            if (!Engine.downloading()) return
            runCatching {
                val i = Intent(c, DownloadService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            }.onFailure { Engine.log("download service not started here: $it") }
        }
    }
}
