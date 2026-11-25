package app.korvy.launcher.assistant

import android.app.Service
import android.content.Context
import android.content.Intent

/** Foss build: no wake word (it needs the sherpa-onnx engine, a prebuilt library). Not in this build's manifest. */
class WakeWordService : Service() {
    override fun onBind(intent: Intent?): android.os.IBinder? = null

    companion object {
        fun available(context: Context) = false
        fun sync(context: Context, on: Boolean, phrase: String, threshold: Float, screenOnly: Boolean) {}
    }
}
