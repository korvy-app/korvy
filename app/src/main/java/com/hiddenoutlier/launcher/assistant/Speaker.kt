package com.hiddenoutlier.launcher.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.hiddenoutlier.launcher.core.SentenceSplitter
import java.util.Locale
import java.util.UUID

/**
 * Spoken replies through Android's text-to-speech, so any installed engine works (Google, RHVoice, eSpeak…).
 * Created on first use and shut down when idle; changing the engine re-creates it.
 */
class Speaker(private val context: Context) {
    data class Config(val engine: String?, val voice: String?, val rate: Float, val pitch: Float)

    private var tts: TextToSpeech? = null
    private var ttsEngine: String? = null
    private var ready = false
    private val pending = ArrayList<String>()
    private var config = Config(null, null, 1f, 1f)
    private var splitter = SentenceSplitter()

    fun configure(c: Config) {
        if (c.engine != ttsEngine && tts != null) shutdown()
        config = c
        if (ready) apply()
    }

    private fun ensure() {
        if (tts != null) return
        ttsEngine = config.engine
        val listener = TextToSpeech.OnInitListener { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) { apply(); pending.forEach(::enqueue); pending.clear() }
        }
        tts = if (config.engine != null) TextToSpeech(context, listener, config.engine) else TextToSpeech(context, listener)
    }

    private fun apply() {
        val t = tts ?: return
        t.setSpeechRate(config.rate)
        t.setPitch(config.pitch)
        val v = config.voice?.let { name -> runCatching { t.voices?.firstOrNull { it.name == name } }.getOrNull() }
        if (v != null) t.voice = v else runCatching { t.language = Locale.getDefault() }
    }

    private fun enqueue(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    /** Speaks [text] after anything already queued. */
    fun say(text: String) {
        if (text.isBlank()) return
        ensure()
        if (ready) enqueue(text) else pending += text
    }

    /** Starts a new streamed reply: call [stream] with the text so far, then [endStream] with the full text. */
    fun beginStream() { splitter = SentenceSplitter() }
    fun stream(textSoFar: String) = splitter.feed(textSoFar).forEach(::say)
    fun endStream(fullText: String) = splitter.finish(fullText).forEach(::say)

    fun stop() { pending.clear(); tts?.stop() }

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
    fun warmUp() = ensure()
}
