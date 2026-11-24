package app.korvy.launcher.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import app.korvy.launcher.core.Endpointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Something that turns the user's speech into text. */
interface SpeechInput {
    fun available(): Boolean
    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit = {})
    /** Stop listening and use what was heard. */
    fun stop()
    /** Stop listening and throw it away. */
    fun cancel() = stop()
}

/**
 * Open-source, fully offline speech recognition with whisper.cpp. Records here (16 kHz mono), stops when the user
 * stops talking ([Endpointer]), then transcribes in the ":assistant" process. Audio is kept only in memory and a
 * temporary file that is deleted right after transcription.
 */
class WhisperInput(private val context: Context, private val client: AssistantClient, private val scope: CoroutineScope) : SpeechInput {
    /** The model to use (set before [start]). */
    var model: ModelInfo? = null
    var modelFile: File? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var finishRequested = false
    @Volatile private var cancelled = false
    private var thread: Thread? = null

    override fun available() = modelFile?.exists() == true

    @SuppressLint("MissingPermission") // the mic button asks for RECORD_AUDIO first
    override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit) {
        val m = model; val file = modelFile
        if (m == null || file == null || !file.exists()) { onError("Download a speech model in Settings → Voice first."); return }
        cancel()
        finishRequested = false; cancelled = false
        thread = Thread({
            val rate = 16_000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, rate * 4))
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release(); main.post { onError("Couldn't open the microphone.") }; return@Thread
            }
            val audio = java.io.ByteArrayOutputStream(rate * 4 * 5)
            val frame = FloatArray(rate / 10) // 100 ms
            val bytes = ByteBuffer.allocate(frame.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            val ep = Endpointer()
            var result = Endpointer.Result.CONTINUE
            try {
                rec.startRecording()
                while (!finishRequested && !cancelled) {
                    val n = rec.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    bytes.clear(); for (i in 0 until n) bytes.putFloat(frame[i])
                    audio.write(bytes.array(), 0, n * 4)
                    result = ep.feed(frame, n)
                    val level = ep.level
                    main.post { onLevel(level) }
                    if (result != Endpointer.Result.CONTINUE) break
                }
            } finally { runCatching { rec.stop() }; rec.release() }
            if (cancelled) return@Thread
            if (!ep.heard) { main.post { onError("I didn't catch that.") }; return@Thread }
            main.post { onPartial("…") }
            val tmp = File(context.cacheDir, "speech-${System.nanoTime()}.f32").apply { writeBytes(audio.toByteArray()) }
            scope.launch {
                try {
                    val text = Endpointer.clean(client.transcribe(file.path, tmp, if (m.englishOnly) "en" else "auto", PROMPT))
                    if (!cancelled) { if (text.isBlank()) onError("I didn't catch that.") else onFinal(text) }
                } catch (e: Exception) {
                    if (!cancelled) onError(e.message ?: "Speech recognition failed")
                } finally { tmp.delete() }
            }
        }, "whisper-mic").also { it.start() }
    }

    override fun stop() { finishRequested = true }

    override fun cancel() { cancelled = true; thread = null }

    companion object {
        /** A hint of the style of what's said (short commands, capitalised names) without listing anything private. */
        private const val PROMPT = "Call Mom. Text Alex I'm on my way. Open Spotify. What's the weather tomorrow?"
    }
}
