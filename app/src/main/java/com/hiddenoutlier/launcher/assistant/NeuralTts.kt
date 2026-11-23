package com.hiddenoutlier.launcher.assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Natural voices with sherpa-onnx (Kokoro, Piper). Runs only in the ":assistant" process, so the voice model's
 * memory never weighs on the launcher. Audio is streamed: playback starts as soon as the first chunk is ready.
 */
class NeuralTts {
    private var tts: OfflineTts? = null
    private var loadedDir: String? = null
    private var track: AudioTrack? = null
    private val stopFlag = AtomicBoolean(false)

    private fun ensure(dir: File, kind: NeuralVoicePack.Kind, modelFile: String) {
        if (loadedDir == dir.path && tts != null) return
        release()
        val threads = (Runtime.getRuntime().availableProcessors() - 4).coerceIn(2, 4)
        val model = when (kind) {
            NeuralVoicePack.Kind.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, modelFile).path, voices = File(dir, "voices.bin").path,
                    tokens = File(dir, "tokens.txt").path, dataDir = File(dir, "espeak-ng-data").path,
                ),
                numThreads = threads, debug = false, provider = "cpu",
            )
            NeuralVoicePack.Kind.VITS -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(dir, modelFile).path, tokens = File(dir, "tokens.txt").path,
                    dataDir = File(dir, "espeak-ng-data").path,
                ),
                numThreads = threads, debug = false, provider = "cpu",
            )
        }
        tts = OfflineTts(assetManager = null, config = OfflineTtsConfig(model = model, maxNumSentences = 1))
        loadedDir = dir.path
    }

    /** Speaks [text] and returns when playback has finished (or [stop] was called). Call from one worker thread. */
    fun speak(dir: File, kind: NeuralVoicePack.Kind, modelFile: String, speaker: Int, speed: Float, text: String, onStart: () -> Unit = {}) {
        stopFlag.set(false)
        val t0 = android.os.SystemClock.elapsedRealtime()
        ensure(dir, kind, modelFile)
        val tLoaded = android.os.SystemClock.elapsedRealtime()
        val engine = tts ?: return
        val rate = engine.sampleRate()
        // A fresh track per sentence keeps "has it finished playing?" simple and exact.
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA) // same volume as Android's TTS
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 4)
            .build()
        track = t
        try {
            t.play()
            var written = 0
            var firstChunkAt = 0L
            val audio = engine.generateWithCallback(text, speaker.coerceIn(0, engine.numSpeakers() - 1), speed, AudioChunkCallback { chunk ->
                if (stopFlag.get()) false else {
                    if (firstChunkAt == 0L) { firstChunkAt = android.os.SystemClock.elapsedRealtime(); onStart() }
                    t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING); written += chunk.size; true
                }
            })
            // Seen once on a cold start: audio generated but the callback never called. Play the result instead.
            if (written == 0 && !stopFlag.get() && audio.samples.isNotEmpty()) {
                onStart()
                t.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING); written = audio.samples.size
            }
            Log.i("NeuralTts", "load ${tLoaded - t0} ms, first audio ${firstChunkAt - tLoaded} ms after load, " +
                "${written * 1000L / rate} ms of speech for ${text.length} chars")
            // Let the last samples play out before the next sentence.
            while (!stopFlag.get() && t.playbackHeadPosition < written) Thread.sleep(20)
        } finally {
            runCatching { t.stop() }; t.release()
            if (track === t) track = null
        }
    }

    fun stop() {
        stopFlag.set(true)
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    fun release() {
        stop()
        runCatching { tts?.release() }.onFailure { Log.w("NeuralTts", "release", it) }
        tts = null; loadedDir = null
    }
}

/**
 * The audio callback handed to sherpa-onnx. Its native code calls `invoke([F)Ljava/lang/Integer;` by name, so this
 * is a named class kept whole by proguard-rules.pro (as a lambda, R8 merged it away and the voice process aborted).
 * Return true to keep going, false to stop.
 */
class AudioChunkCallback(private val onChunk: (FloatArray) -> Boolean) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = if (onChunk(samples)) 1 else 0
}
