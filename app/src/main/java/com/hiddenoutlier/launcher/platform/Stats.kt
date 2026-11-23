package com.hiddenoutlier.launcher.platform

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import java.io.File
import kotlin.math.abs

/** One reading of the device stats screen. Null means the phone doesn't report that value. */
data class StatsSnapshot(
    val appCpuPercent: Double,
    val appMemoryMb: Double,
    val ramTotalMb: Long,
    val ramAvailableMb: Long,
    val lowMemory: Boolean,
    val cores: Int,
    val coreMhz: List<Int?>,
    val refreshHz: Float,
    val fps: Double,
    /** Null when too few frames were drawn to judge (an idle screen). */
    val slowFramePercent: Double?,
    val batteryPercent: Int,
    val charging: Boolean,
    val currentMa: Int?,
    val powerMw: Double?,
    val voltage: Double?,
    val batteryTempC: Double?,
    val thermal: String,
    val thermalHeadroom: Float?,
)

/**
 * Measures this app (CPU share, memory, frames drawn) and the phone (RAM, core speeds, battery drain,
 * temperature, thermal state). Everything is read locally; nothing is stored or sent anywhere.
 */
class StatsSampler(private val context: Context, private val activity: Activity? = null) {
    /** On a screen: also counts that screen's frames. */
    constructor(activity: Activity) : this(activity, activity)
    private val am = context.getSystemService(ActivityManager::class.java)
    private val bm = context.getSystemService(BatteryManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)
    private val cores = Runtime.getRuntime().availableProcessors()

    private var lastWall = SystemClock.elapsedRealtime()
    private var lastCpu = Process.getElapsedCpuTime()
    private var frames = 0
    private var slowFrames = 0
    private var frameBudgetNs = 16_666_667L
    private var thread: HandlerThread? = null
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        synchronized(this) {
            frames++
            // Drawing overlaps across refreshes, so a frame only counts as dropped past twice the budget
            // (the same rule as AndroidX JankStats).
            if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) > 2 * frameBudgetNs) slowFrames++
        }
    }

    fun start() {
        val hz = refreshHz()
        frameBudgetNs = (1_000_000_000L / hz).toLong()
        val t = HandlerThread("frame-metrics").also { it.start() }
        thread = t
        activity?.window?.addOnFrameMetricsAvailableListener(listener, Handler(t.looper))
        lastWall = SystemClock.elapsedRealtime(); lastCpu = Process.getElapsedCpuTime()
    }

    fun stop() {
        runCatching { activity?.window?.removeOnFrameMetricsAvailableListener(listener) }
        thread?.quitSafely(); thread = null
    }

    @Suppress("DEPRECATION")
    private fun refreshHz(): Float =
        context.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f

    fun sample(): StatsSnapshot {
        val now = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        val wallMs = (now - lastWall).coerceAtLeast(1)
        val cpuPercent = (cpu - lastCpu) * 100.0 / (wallMs * cores)
        val (f, slow) = synchronized(this) { (frames to slowFrames).also { frames = 0; slowFrames = 0 } }
        lastWall = now; lastCpu = cpu

        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val appMem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024.0

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }?.let { it / 1000.0 }
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        // Microamps; the sign differs between phone makers, so report the size and use the charging state for direction.
        val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE && it != 0 }
        val currentMa = currentUa?.let { abs(it) / 1000 }
        val powerMw = if (currentMa != null && voltage != null) currentMa * voltage else null

        return StatsSnapshot(
            appCpuPercent = cpuPercent.coerceAtLeast(0.0),
            appMemoryMb = appMem,
            ramTotalMb = mem.totalMem / (1024 * 1024),
            ramAvailableMb = mem.availMem / (1024 * 1024),
            lowMemory = mem.lowMemory,
            cores = cores,
            coreMhz = (0 until cores).map { readMhz(it) },
            refreshHz = refreshHz(),
            fps = f * 1000.0 / wallMs,
            slowFramePercent = if (f < 10) null else slow * 100.0 / f,
            batteryPercent = if (level >= 0) level * 100 / scale else -1,
            charging = charging,
            currentMa = currentMa,
            powerMw = powerMw,
            voltage = voltage,
            batteryTempC = temp,
            thermal = thermal(),
            thermalHeadroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10).takeIf { !it.isNaN() } else null,
        )
    }

    private fun thermal(): String {
        if (Build.VERSION.SDK_INT < 29) return "Not reported (needs Android 10)"
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light (warm)"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate (may slow down)"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe (slowing down)"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutting down"
            else -> "Unknown"
        }
    }

    /** Current core speed. Some phones don't let apps read it; then null. */
    private fun readMhz(core: Int): Int? = runCatching {
        File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq").readText().trim().toInt() / 1000
    }.getOrNull()

    companion object {
        fun isPowerSave(context: Context) = context.getSystemService(PowerManager::class.java).isPowerSaveMode
    }
}
