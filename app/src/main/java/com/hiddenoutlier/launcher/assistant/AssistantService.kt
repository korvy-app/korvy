package com.hiddenoutlier.launcher.assistant

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
            MSG_STOP -> Native.stop()
            MSG_PRELOAD -> worker.execute { msg.data.getString(K_PATH)?.let { runCatching { ensureLoaded(it, msg.data.getInt(K_CTX, CTX), null) } } }
            MSG_COMPLETE -> worker.execute {
                val d = msg.data
                val path = d.getString(K_PATH)!!
                try {
                    if (!ensureLoaded(path, d.getInt(K_CTX, CTX), reply)) return@execute
                    send(reply, MSG_STATUS, "Thinking…")
                    val out = Native.complete(
                        d.getStringArray(K_ROLES)!!, d.getStringArray(K_CONTENTS)!!, d.getString(K_GRAMMAR) ?: "",
                        d.getInt(K_MAX, 256), d.getFloat(K_TEMP, 0.3f),
                    ) { piece -> send(reply, MSG_TOKEN, piece); true }
                    if (out == null) send(reply, MSG_ERROR, "The model couldn't answer (prompt too long?)")
                    else send(reply, MSG_DONE, out)
                } catch (t: Throwable) {
                    Log.e("AssistantService", "inference failed", t)
                    send(reply, MSG_ERROR, t.message ?: "Assistant error")
                }
            }
        }
    }

    /** Loads [path] unless it's already loaded. Runs on the worker thread. */
    private fun ensureLoaded(path: String, ctx: Int, reply: Messenger?): Boolean {
        if (path == loadedPath) return true
        send(reply, MSG_STATUS, "Loading model…")
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
        val rc = Native.load(path, ctx, threads)
        if (rc != 0) { loadedPath = null; send(reply, MSG_ERROR, "Couldn't load the model (code $rc)"); return false }
        loadedPath = path
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
        worker.shutdown()
        super.onDestroy()
        // Give the memory back right away instead of leaving a cached process holding a model.
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val MSG_COMPLETE = 1; const val MSG_STOP = 2; const val MSG_PRELOAD = 3
        /** Context window: room for the system prompt, examples, a few earlier turns and the reply. */
        const val CTX = 4096
        const val MSG_TOKEN = 10; const val MSG_DONE = 11; const val MSG_ERROR = 12; const val MSG_STATUS = 13
        const val K_PATH = "path"; const val K_CTX = "ctx"; const val K_ROLES = "roles"; const val K_CONTENTS = "contents"
        const val K_GRAMMAR = "grammar"; const val K_MAX = "max"; const val K_TEMP = "temp"; const val K_TEXT = "text"
    }
}
