package com.hiddenoutlier.launcher.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hiddenoutlier.launcher.LauncherApp
import com.hiddenoutlier.launcher.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Model downloads (0.5–2.5 GB) run here, as a foreground service with a progress notification, so Android keeps
 * them going with the screen off or while other apps are used. A Wi-Fi lock and a partial wake lock are held
 * only while a download is running. The work itself is [ModelStore.download] (resumable, retried, verified).
 */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { job?.cancel(); stop(); return START_NOT_STICKY }
        val model = ModelCatalog.byId(intent?.getStringExtra(EXTRA_MODEL)) ?: run { stop(); return START_NOT_STICKY }
        val app = application as LauncherApp
        startInForeground(notification(model, 0, model.bytes, "Starting…"))
        if (job?.isActive == true) return START_NOT_STICKY
        acquireLocks()
        job = scope.launch {
            val progress = launch {
                app.models.download.collect { st ->
                    val n = when (st) {
                        is DownloadState.Running -> notification(model, st.done, st.total, null)
                        is DownloadState.Retrying -> notification(model, st.done, st.total, "Connection dropped, retrying in ${st.inSeconds} s")
                        is DownloadState.Verifying -> notification(model, -1, 0, "Checking the download…")
                        else -> null
                    }
                    n?.let { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, it) }
                }
            }
            app.models.download(model) { app.settingsState.value.allows(it) }
            progress.cancel()
            if (model.id in app.models.installed.value && app.settingsState.value.modelId == null) app.settings.setModelId(model.id)
            done(model, app.models.download.value)
            stop()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(n: Notification) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of assistant model downloads"
                setShowBadge(false)
            })
        }
    }

    private fun openApp() = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_MODELS), PendingIntent.FLAG_IMMUTABLE)

    private fun notification(m: ModelInfo, done: Long, total: Long, text: String?): Notification {
        channel()
        val cancel = PendingIntent.getService(this, 1, Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${m.name}")
            .setContentText(text ?: "%.0f of %.0f MB".format(done / 1e6, total / 1e6))
            .setProgress(if (done < 0) 0 else 100, if (done < 0 || total <= 0) 0 else (done * 100 / total).toInt(), done < 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(openApp())
            .addAction(0, "Pause", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** A short, dismissible notification when it finishes (or fails), since the user may have left the app. */
    private fun done(m: ModelInfo, state: DownloadState) {
        val ok = state is DownloadState.Idle && m.id in (application as LauncherApp).models.installed.value
        val text = if (ok) "${m.name} is ready. The assistant can use it now." else (state as? DownloadState.Failed)?.message ?: return
        channel()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(if (ok) "Model downloaded" else "Model download stopped").setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp()).setAutoCancel(true).build())
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "launcher:model-download").apply {
            setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) // safety cap: 3 hours
        }
        wifiLock = (applicationContext.getSystemService(WifiManager::class.java)).createWifiLock(
            if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL, "launcher:model-download"
        ).apply { setReferenceCounted(false); acquire() }
    }

    private fun stop() {
        runCatching { wakeLock?.release() }; runCatching { wifiLock?.release() }
        wakeLock = null; wifiLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.release() }; runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "model_downloads"
        private const val NOTIFICATION_ID = 41
        private const val EXTRA_MODEL = "model"
        private const val ACTION_CANCEL = "com.hiddenoutlier.launcher.CANCEL_DOWNLOAD"

        fun start(context: Context, m: ModelInfo) = ContextCompat.startForegroundService(context,
            Intent(context, ModelDownloadService::class.java).putExtra(EXTRA_MODEL, m.id))

        fun cancel(context: Context) = context.startService(Intent(context, ModelDownloadService::class.java).setAction(ACTION_CANCEL))
    }
}
