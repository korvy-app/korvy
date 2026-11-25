package app.korvy.launcher.assistant

import java.io.File

/**
 * Foss build: no natural voices (the sherpa-onnx engine is a prebuilt library, which F-Droid doesn't accept).
 * Nothing can call [speak] here, because voice packs can't be downloaded in this build; the phone's own
 * text-to-speech voices are used instead.
 */
class NeuralTts {
    fun speak(dir: File, kind: NeuralVoicePack.Kind, modelFile: String, speaker: Int, speed: Float, text: String, onStart: () -> Unit = {}) {
        throw UnsupportedOperationException("Natural voices aren't part of this build")
    }
    fun stop() {}
    fun release() {}
}
