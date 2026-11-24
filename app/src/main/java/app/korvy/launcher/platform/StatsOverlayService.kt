package app.korvy.launcher.platform

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.korvy.launcher.R

/**
 * A small, draggable box over other apps with the phone's vital signs, updated every second: core speeds, memory,
 * battery drain and temperature, thermal state. Plain views (no Compose) to stay light. Needs "Display over other
 * apps"; nothing is stored or sent.
 */
class StatsOverlayService : Service() {
    private var view: TextView? = null
    private lateinit var sampler: StatsSampler
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 1000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val n = notification()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, n)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (view == null) show()
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        sampler = StatsSampler(this).also { it.start() }
        val wm = getSystemService(WindowManager::class.java)
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply { setColor(0xB0000000.toInt()); cornerRadius = 10 * dp }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val prefs = getSharedPreferences("overlay", MODE_PRIVATE)
            x = prefs.getInt("x", (8 * dp).toInt()); y = prefs.getInt("y", (80 * dp).toInt())
        }
        // Drag to move; the spot is remembered.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y }
                MotionEvent.ACTION_MOVE -> { lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt(); wm.updateViewLayout(v, lp) }
                MotionEvent.ACTION_UP -> getSharedPreferences("overlay", MODE_PRIVATE).edit().putInt("x", lp.x).putInt("y", lp.y).apply()
            }
            true
        }
        wm.addView(tv, lp)
        view = tv
        handler.post(tick)
    }

    private fun update() {
        val s = runCatching { sampler.sample() }.getOrNull() ?: return
        val mhz = s.coreMhz.filterNotNull()
        view?.text = buildString {
            if (mhz.isNotEmpty()) appendLine("CPU  ${mhz.max()} MHz max · ${mhz.average().toInt()} avg")
            appendLine("RAM  ${(s.ramTotalMb - s.ramAvailableMb) / 1024.0}".take(9) + " / ${"%.1f".format(s.ramTotalMb / 1024.0)} GB")
            val drain = s.currentMa?.let { "${if (s.charging) "+" else "−"}$it mA" } ?: "—"
            appendLine("BAT  ${s.batteryPercent}% · $drain" + (s.powerMw?.let { " · ${"%.1f".format(it / 1000)} W" } ?: ""))
            append("TEMP ${s.batteryTempC?.let { "%.1f°C".format(it) } ?: "—"} · ${s.thermal.substringBefore(" (")}")
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.n_channel_overlay), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val stop = PendingIntent.getService(this, 0, Intent(this, StatsOverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_listen)
            .setContentTitle(getString(R.string.n_overlay_on)).setOngoing(true).setSilent(true)
            .addAction(0, "Hide", stop).build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        view?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        view = null
        if (::sampler.isInitialized) sampler.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 8
        private const val ACTION_STOP = "app.korvy.launcher.STOP_OVERLAY"

        fun canShow(context: Context) = Settings.canDrawOverlays(context)
        fun start(context: Context) = runCatching { ContextCompat.startForegroundService(context, Intent(context, StatsOverlayService::class.java)) }
        fun stop(context: Context) = context.stopService(Intent(context, StatsOverlayService::class.java))
        @Suppress("DEPRECATION")
        fun running(context: Context) = context.getSystemService(android.app.ActivityManager::class.java)
            .getRunningServices(50).any { it.service.className == StatsOverlayService::class.java.name }
    }
}
