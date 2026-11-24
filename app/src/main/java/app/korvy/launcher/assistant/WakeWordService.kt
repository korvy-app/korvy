package app.korvy.launcher.assistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.korvy.launcher.AssistActivity
import app.korvy.launcher.R
import app.korvy.launcher.core.WordPieces
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import app.korvy.launcher.LauncherApp

/**
 * Listens for the wake word with a tiny open-source keyword model (sherpa-onnx), in its own ":wake" process.
 * Audio is processed in memory 0.1 s at a time and never stored or sent; only the wake word is recognised.
 * Android shows the ongoing notification and the green mic dot while it runs. Pauses in battery saver, and
 * (by default) while the screen is off.
 */
class WakeWordService : Service() {
    @Volatile private var running = false
    @Volatile private var paused = false
    private var worker: Thread? = null
    private val prefs by lazy { getSharedPreferences("wake", MODE_PRIVATE) }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { updatePaused() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        intent?.getStringExtra(EXTRA_PHRASE)?.let { phrase ->
            prefs.edit().putString(EXTRA_PHRASE, phrase).putFloat(EXTRA_THRESHOLD, intent.getFloatExtra(EXTRA_THRESHOLD, 0.25f))
                .putBoolean(EXTRA_SCREEN_ONLY, intent.getBooleanExtra(EXTRA_SCREEN_ONLY, true)).apply()
        }
        val phrase = prefs.getString(EXTRA_PHRASE, null) ?: run { stopSelf(); return START_NOT_STICKY }
        val n = notification(phrase)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            // Android only lets the mic start while the app is on screen; the launcher retries when it's shown.
            Log.w(TAG, "can't start listening now", e); stopSelf(); return START_NOT_STICKY
        }
        restart(phrase)
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        updatePaused()
    }

    private fun updatePaused() {
        val pm = getSystemService(PowerManager::class.java)
        paused = pm.isPowerSaveMode || (prefs.getBoolean(EXTRA_SCREEN_ONLY, true) && !pm.isInteractive)
    }

    private fun restart(phrase: String) {
        running = false
        worker?.join(2000)
        running = true
        worker = Thread({ listen(phrase) }, "wake-word").also { it.start() }
    }

    @SuppressLint("MissingPermission") // only started after the user turned it on and granted the microphone
    private fun listen(phrase: String) {
        val dir = (application as LauncherApp).voices.folderFor(NeuralVoiceCatalog.wakeWord)
        val pieces = WordPieces.parse(assets.open("kws-pieces.tsv").bufferedReader().readText()).encode(phrase)
        if (pieces == null || !File(dir, NeuralVoiceCatalog.wakeWord.modelFile).exists()) { Log.w(TAG, "no model or bad phrase"); stopSelf(); return }
        val threshold = prefs.getFloat(EXTRA_THRESHOLD, 0.25f)
        val keywords = File(filesDir, "wake-keywords.txt").apply { writeText("$pieces :2.0 #$threshold @$phrase\n") }
        val m = "epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        val kws = KeywordSpotter(null, KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = "$dir/encoder-$m", decoder = "$dir/decoder-$m", joiner = "$dir/joiner-$m"),
                tokens = "$dir/tokens.txt", numThreads = 1, provider = "cpu",
            ),
            keywordsFile = keywords.path,
        ))
        val stream = kws.createStream("")
        val rate = 16_000
        val buf = FloatArray(rate / 10)
        var rec: AudioRecord? = null
        var lastHit = 0L
        try {
            while (running) {
                if (paused) {
                    rec?.let { runCatching { it.stop() }; it.release() }; rec = null
                    Thread.sleep(500); continue
                }
                if (rec == null) {
                    rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                        maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT), rate * 2))
                    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); rec = null; Thread.sleep(2000); continue }
                    rec.startRecording()
                }
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) { Thread.sleep(100); continue }
                stream.acceptWaveform(if (n == buf.size) buf else buf.copyOf(n), rate)
                while (kws.isReady(stream)) {
                    kws.decode(stream)
                    if (kws.getResult(stream).keyword.isNotBlank()) {
                        kws.reset(stream)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastHit > 3000) { lastHit = now; wake() }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "wake word stopped", e)
        } finally {
            rec?.let { runCatching { it.stop() }; it.release() }
            stream.release(); kws.release()
        }
    }

    /** Opens the assistant panel, listening. */
    private fun wake() {
        val i = Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AssistActivity.EXTRA_LISTEN, true)
        runCatching { startActivity(i) }.onFailure { Log.w(TAG, "couldn't open the assistant", it) }
    }

    private fun notification(phrase: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while the launcher listens for your wake word"; setShowBadge(false)
        })
        val stop = PendingIntent.getService(this, 0, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_listen)
            .setContentTitle("Listening for “$phrase”")
            .setContentText("Only the wake word is recognised, on this phone.")
            .setOngoing(true).setSilent(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(stateReceiver) }
        super.onDestroy()
        // Free the model's memory right away.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val CHANNEL = "wake"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "app.korvy.launcher.STOP_WAKE"
        private const val EXTRA_PHRASE = "phrase"
        private const val EXTRA_THRESHOLD = "threshold"
        private const val EXTRA_SCREEN_ONLY = "screenOnly"

        /** Starts or stops listening to match the settings. Call while the launcher is on screen. */
        fun sync(context: Context, on: Boolean, phrase: String, threshold: Float, screenOnly: Boolean) {
            val i = Intent(context, WakeWordService::class.java)
            if (!on) { if (running(context)) context.startService(i.setAction(ACTION_STOP)); return }
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            runCatching {
                ContextCompat.startForegroundService(context, i.putExtra(EXTRA_PHRASE, phrase).putExtra(EXTRA_THRESHOLD, threshold).putExtra(EXTRA_SCREEN_ONLY, screenOnly))
            }.onFailure { Log.w(TAG, "start failed", it) }
        }

        @Suppress("DEPRECATION")
        private fun running(context: Context) = context.getSystemService(android.app.ActivityManager::class.java)
            .getRunningServices(50).any { it.service.className == WakeWordService::class.java.name }
    }
}
