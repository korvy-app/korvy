package app.korvy.launcher.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import app.korvy.launcher.core.SentenceSplitter
import java.util.Locale
import java.util.UUID

/**
 * Spoken replies through Android's text-to-speech, so any installed engine works (Google, RHVoice, eSpeak…).
 * Created on first use and shut down when idle; changing the engine re-creates it.
 */
class Speaker(private val context: Context) {
    /** Natural voices (sherpa-onnx) speak in the assistant process; set by AssistantController. */
    interface Neural {
        fun say(engine: String, voice: String?, speed: Float, text: String, onStart: () -> Unit, onDone: () -> Unit): Boolean
        fun stop()
    }
    var neural: Neural? = null
    private fun isNeural() = config.engine?.startsWith(NEURAL_PREFIX) == true
    data class Config(val engine: String?, val voice: String?, val rate: Float, val pitch: Float)

    private var tts: TextToSpeech? = null
    private var ttsEngine: String? = null
    private var ready = false
    private val pending = ArrayList<Pair<String, Int>>()

    /** IDLE, LOADING (asked to speak, no sound yet) or SPEAKING. Drives the reply's spinner / stop button. */
    enum class State { IDLE, LOADING, SPEAKING }
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(State.IDLE)
    val state: kotlinx.coroutines.flow.StateFlow<State> = _state
    private val lock = Any()
    private var generation = 0
    private var outstanding = 0

    private fun queued(): Int = synchronized(lock) {
        outstanding++
        if (_state.value == State.IDLE) _state.value = State.LOADING
        generation
    }
    private fun started(gen: Int) = synchronized(lock) { if (gen == generation && outstanding > 0) _state.value = State.SPEAKING }
    private fun finished(gen: Int) = synchronized(lock) {
        if (gen != generation) return@synchronized
        outstanding = (outstanding - 1).coerceAtLeast(0)
        if (outstanding == 0) _state.value = State.IDLE
    }
    private fun genOf(utteranceId: String?) = utteranceId?.substringBefore(':')?.toIntOrNull() ?: -1
    private var config = Config(null, null, 1f, 1f)
    private var splitter = SentenceSplitter()

    fun configure(c: Config) {
        if (c.engine != ttsEngine && tts != null) shutdown()
        if (c.engine?.startsWith(NEURAL_PREFIX) == true) { config = c; return } // Android TTS not needed
        config = c
        if (ready) apply()
    }

    private fun ensure() {
        if (tts != null) return
        ttsEngine = config.engine
        val listener = TextToSpeech.OnInitListener { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) { apply(); pending.forEach { (t, g) -> enqueue(t, g) } } else pending.forEach { finished(it.second) }
            pending.clear()
        }
        tts = (if (config.engine != null) TextToSpeech(context, listener, config.engine) else TextToSpeech(context, listener)).also {
            it.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) = started(genOf(id))
                override fun onDone(id: String?) = finished(genOf(id))
                @Deprecated("Deprecated in Java") override fun onError(id: String?) = finished(genOf(id))
                override fun onError(id: String?, errorCode: Int) = finished(genOf(id))
                override fun onStop(id: String?, interrupted: Boolean) = finished(genOf(id))
            })
        }
    }

    private fun apply() {
        val t = tts ?: return
        t.setSpeechRate(config.rate)
        t.setPitch(config.pitch)
        val v = config.voice?.let { name -> runCatching { t.voices?.firstOrNull { it.name == name } }.getOrNull() }
        if (v != null) t.voice = v else runCatching { t.language = Locale.getDefault() }
    }

    private fun enqueue(text: String, gen: Int) {
        val rc = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "$gen:" + UUID.randomUUID())
        if (rc != TextToSpeech.SUCCESS) finished(gen)
    }

    /** Speaks [text] after anything already queued. */
    fun say(text: String) {
        if (text.isBlank()) return
        val gen = queued()
        if (isNeural() && neural?.say(config.engine!!, config.voice, config.rate, text,
                onStart = { started(gen) }, onDone = { finished(gen) }) == true) return
        ensure()
        if (ready) enqueue(text, gen) else pending += text to gen
    }

    /** Speaks a whole reply sentence by sentence, so the first one starts sooner (natural voices are slow on long text). */
    fun sayAll(text: String) {
        val sp = SentenceSplitter()
        (sp.feed(text) + sp.finish(text)).ifEmpty { listOf(text) }.forEach(::say)
    }

    /** Starts a new streamed reply: call [stream] with the text so far, then [endStream] with the full text. */
    fun beginStream() { splitter = SentenceSplitter() }
    fun stream(textSoFar: String) = splitter.feed(textSoFar).forEach(::say)
    fun endStream(fullText: String) = splitter.finish(fullText).forEach(::say)

    fun stop() {
        synchronized(lock) { generation++; outstanding = 0; _state.value = State.IDLE }
        pending.clear(); tts?.stop(); neural?.stop()
    }

    fun shutdown() {
        tts?.shutdown(); tts = null; ready = false; pending.clear()
    }

    /** Installed engines and the voices of the current one (for Settings). Must be ready (call after [ensure]). */
    fun engines(): List<TextToSpeech.EngineInfo> { ensure(); return tts?.engines.orEmpty() }

    fun voices(): List<Voice> = runCatching { tts?.voices.orEmpty().toList() }.getOrDefault(emptyList())
        .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.locale.toLanguageTag() != Locale.getDefault().toLanguageTag() }, { it.name }))

    /** True when the media volume (which spoken replies use) is muted or very low. */
    fun volumeLow(): Boolean {
        val am = context.getSystemService(android.media.AudioManager::class.java)
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / max < 20
    }

    fun isReady() = ready

    companion object { const val NEURAL_PREFIX = "neural:" }
    fun warmUp() = ensure()
}
