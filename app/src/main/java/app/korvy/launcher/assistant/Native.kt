package app.korvy.launcher.assistant

/** JNI bridge to llama.cpp (src/main/cpp/assistant_jni.cpp). Used only inside the ":assistant" process. */
object Native {
    fun interface Listener { fun onToken(piece: String): Boolean }

    private var ready = false

    @Synchronized
    fun ensureInit(nativeLibDir: String) {
        if (ready) return
        System.loadLibrary("assistant")
        init(nativeLibDir)
        ready = true
    }

    private external fun init(nativeLibDir: String)
    /** Reads the fixed start of every request ahead of time, kept in [statePath]; tokens read, or -1. */
    external fun prefill(roles: Array<String>, contents: Array<String>, statePath: String): Int

    /** [threads] write the reply (fast cores only), [threadsBatch] read the prompt (all cores). */
    external fun load(path: String, nCtx: Int, threads: Int, threadsBatch: Int): Int
    external fun complete(roles: Array<String>, contents: Array<String>, grammar: String, maxTokens: Int, temp: Float, listener: Listener): String?
    external fun stop()
    external fun unload()

    // whisper.cpp speech recognition (whisper_jni.cpp)
    external fun whisperLoad(path: String): Int
    external fun whisperTranscribe(pcm: FloatArray, lang: String, threads: Int, prompt: String): String?
    external fun whisperStop()
    external fun whisperUnload()
}
