package app.korvy.launcher.assistant

import app.korvy.launcher.R
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import java.util.concurrent.Executors

/**
 * Runs the model in its own process (":assistant"), so the launcher stays small and is never slowed or killed
 * because of the model's memory. The launcher binds only while the assistant is in use; when it unbinds, the
 * model is freed and this process exits.
 */
class AssistantService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    // Speech recognition gets its own thread: what the user said must not wait behind the chat model loading (~6 s).
    private val listener = Executors.newSingleThreadExecutor()
    // Speech gets its own thread so a reply can be spoken while the model keeps writing the next sentence.
    private val voice = Executors.newSingleThreadExecutor()
    private val neural by lazy { NeuralTts() }
    /** Bumped by "stop speaking": queued sentences from before the stop are skipped. */
    @Volatile private var speechGeneration = 0
    private var loadedPath: String? = null
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(Handler(Looper.getMainLooper()) { msg -> handle(Message.obtain(msg)); true })
        Native.ensureInit(applicationInfo.nativeLibraryDir)
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handle(msg: Message) {
        val reply = msg.replyTo
        when (msg.what) {
            MSG_STOP -> { Native.stop(); Native.whisperStop() }
            MSG_STOP_SPEAK -> { speechGeneration++; neural.stop() }
            MSG_SPEAK -> {
                val d = msg.data
                val gen = speechGeneration
                voice.execute {
                    // Always report the end (even when skipped) so the launcher's "speaking" state can't get stuck.
                    if (gen != speechGeneration) { send(reply, MSG_SPEAK_DONE, ""); return@execute }
                    runCatching {
                        neural.speak(java.io.File(d.getString(K_PATH)!!), NeuralVoicePack.Kind.valueOf(d.getString(K_KIND)!!),
                            d.getString(K_MODEL)!!, d.getInt(K_SPEAKER), d.getFloat(K_SPEED, 1f), d.getString(K_TEXT)!!,
                            onStart = { send(reply, MSG_SPEAK_START, "") })
                    }.onFailure { Log.e("AssistantService", "speech failed", it); send(reply, MSG_ERROR, "Voice failed: ${it.message}") }
                    send(reply, MSG_SPEAK_DONE, "")
                }
            }
            MSG_TRANSCRIBE -> listener.execute {
                val d = msg.data
                try {
                    val audio = java.io.File(d.getString(K_AUDIO)!!)
                    val pcm = java.nio.ByteBuffer.wrap(audio.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        .let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                    audio.delete()
                    if (Native.whisperLoad(d.getString(K_PATH)!!) != 0) { send(reply, MSG_ERROR, getString(R.string.s_load_speech_failed)); return@execute }
                    val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
                    val text = Native.whisperTranscribe(pcm, d.getString(K_LANG) ?: "en", threads, d.getString(K_PROMPT).orEmpty())
                    if (text == null) send(reply, MSG_ERROR, getString(R.string.s_stt_failed)) else send(reply, MSG_DONE, text.trim())
                } catch (t: Throwable) {
                    Log.e("AssistantService", "transcribe failed", t); send(reply, MSG_ERROR, t.message ?: getString(R.string.s_stt_failed))
                }
            }
            MSG_PRELOAD -> worker.execute {
                val d = msg.data
                val path = d.getString(K_PATH) ?: return@execute
                runCatching {
                    if (!ensureLoaded(path, d.getInt(K_CTX, CTX), null)) return@execute
                    val roles = d.getStringArray(K_ROLES) ?: return@execute
                    Native.prefill(roles, d.getStringArray(K_CONTENTS)!!, d.getString(K_STATE).orEmpty())
                }.onFailure { Log.e("AssistantService", "warm-up failed", it) }
            }
            MSG_COMPLETE -> worker.execute {
                val d = msg.data
                val path = d.getString(K_PATH)!!
                try {
                    if (!ensureLoaded(path, d.getInt(K_CTX, CTX), reply)) return@execute
                    send(reply, MSG_STATUS, getString(R.string.thinking))
                    val out = Native.complete(
                        d.getStringArray(K_ROLES)!!, d.getStringArray(K_CONTENTS)!!, d.getString(K_GRAMMAR) ?: "",
                        d.getInt(K_MAX, 256), d.getFloat(K_TEMP, 0.3f),
                    ) { piece -> send(reply, MSG_TOKEN, piece); true }
                    if (out == null) send(reply, MSG_ERROR, getString(R.string.s_prompt_too_long))
                    else send(reply, MSG_DONE, out)
                } catch (t: Throwable) {
                    Log.e("AssistantService", "inference failed", t)
                    send(reply, MSG_ERROR, t.message ?: getString(R.string.s_assistant_error))
                }
            }
        }
    }

    /** Loads [path] unless it's already loaded. Runs on the worker thread. */
    private fun ensureLoaded(path: String, ctx: Int, reply: Messenger?): Boolean {
        if ("$path#$ctx" == loadedPath) return true // a new context size (Advanced settings) means loading again
        send(reply, MSG_STATUS, getString(R.string.s_loading_model))
        val (threads, batch) = app.korvy.launcher.platform.CpuCores.inferenceThreads()
        Log.i("AssistantService", "threads: $threads to write, $batch to read")
        val rc = Native.load(path, ctx, threads, batch)
        if (rc != 0) { loadedPath = null; send(reply, MSG_ERROR, getString(R.string.s_load_model_failed, rc)); return false }
        loadedPath = "$path#$ctx"
        return true
    }

    private fun send(to: Messenger?, what: Int, text: String) {
        runCatching { to?.send(Message.obtain(null, what).apply { data = Bundle().apply { putString(K_TEXT, text) } }) }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Native.stop()
        worker.execute { Native.unload(); loadedPath = null }
        return false
    }

    override fun onDestroy() {
        Native.stop()
        speechGeneration++; runCatching { neural.release() }; runCatching { Native.whisperUnload() }
        worker.shutdown(); voice.shutdown(); listener.shutdown()
        super.onDestroy()
        // Give the memory back right away instead of leaving a cached process holding a model.
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val MSG_COMPLETE = 1; const val MSG_STOP = 2; const val MSG_PRELOAD = 3; const val MSG_SPEAK = 4; const val MSG_STOP_SPEAK = 5; const val MSG_TRANSCRIBE = 6
        const val K_AUDIO = "audio"; const val K_LANG = "lang"; const val K_PROMPT = "prompt"
        const val K_KIND = "kind"; const val K_MODEL = "model"; const val K_SPEAKER = "speaker"; const val K_SPEED = "speed"
        /** Context window: room for the system prompt, examples, a few earlier turns and the reply. */
        const val CTX = 4096
        const val MSG_TOKEN = 10; const val MSG_DONE = 11; const val MSG_ERROR = 12; const val MSG_STATUS = 13
        const val MSG_SPEAK_START = 14; const val MSG_SPEAK_DONE = 15
        const val K_PATH = "path"; const val K_CTX = "ctx"; const val K_ROLES = "roles"; const val K_CONTENTS = "contents"
        const val K_STATE = "state"; const val K_GRAMMAR = "grammar"; const val K_MAX = "max"; const val K_TEMP = "temp"; const val K_TEXT = "text"
    }
}
