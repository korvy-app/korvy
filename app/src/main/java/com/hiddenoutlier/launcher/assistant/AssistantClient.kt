package com.hiddenoutlier.launcher.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** Talks to [AssistantService] from the launcher. Binds on first use and unbinds after [IDLE_MS] of quiet. */
class AssistantClient(private val context: Context, private val scope: CoroutineScope) {
    private var service: Messenger? = null
    private var connection: ServiceConnection? = null
    private var idleJob: Job? = null
    private val mutex = Mutex()

    private suspend fun bind(): Messenger = service ?: suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = Messenger(binder); if (cont.isActive) cont.resume(service!!)
            }
            override fun onServiceDisconnected(name: ComponentName) { service = null }
        }
        connection = conn
        context.bindService(Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    /**
     * One completion. [onStatus] gets progress like "Loading model…", [onToken] each new piece. Returns the full
     * reply, or throws with a readable message.
     */
    suspend fun complete(
        modelPath: String, roles: List<String>, contents: List<String>, grammar: String,
        maxTokens: Int, temp: Float, onStatus: (String) -> Unit, onToken: (String) -> Unit,
    ): String = mutex.withLock {
        idleJob?.cancel()
        val svc = bind()
        val result = CompletableDeferred<String>()
        val replyTo = Messenger(Handler(Looper.getMainLooper()) { m ->
            val text = m.data.getString(AssistantService.K_TEXT).orEmpty()
            when (m.what) {
                AssistantService.MSG_TOKEN -> onToken(text)
                AssistantService.MSG_STATUS -> onStatus(text)
                AssistantService.MSG_DONE -> result.complete(text)
                AssistantService.MSG_ERROR -> result.completeExceptionally(IllegalStateException(text))
            }
            true
        })
        svc.send(Message.obtain(null, AssistantService.MSG_COMPLETE).apply {
            this.replyTo = replyTo
            data = Bundle().apply {
                putString(AssistantService.K_PATH, modelPath)
                putStringArray(AssistantService.K_ROLES, roles.toTypedArray())
                putStringArray(AssistantService.K_CONTENTS, contents.toTypedArray())
                putString(AssistantService.K_GRAMMAR, grammar)
                putInt(AssistantService.K_MAX, maxTokens)
                putFloat(AssistantService.K_TEMP, temp)
            }
        })
        try { result.await() } finally { scheduleIdle() }
    }

    /** Loads the model in the background (e.g. when the assistant opens) so the first answer starts sooner. */
    fun preload(modelPath: String) {
        scope.launch {
            idleJob?.cancel()
            runCatching {
                bind().send(Message.obtain(null, AssistantService.MSG_PRELOAD).apply {
                    data = Bundle().apply { putString(AssistantService.K_PATH, modelPath) }
                })
            }
            scheduleIdle()
        }
    }

    fun stop() { runCatching { service?.send(Message.obtain(null, AssistantService.MSG_STOP)) } }

    private fun scheduleIdle() {
        idleJob?.cancel()
        idleJob = scope.launch(Dispatchers.Main) { delay(IDLE_MS); release() }
    }

    /** Frees the model now (also called when leaving the assistant screen for a while). */
    fun release() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null; service = null
    }

    /** Keep the model loaded this long after the last use (the conversation's KV cache is kept with it). */
    companion object { const val IDLE_MS = 300_000L }
}
