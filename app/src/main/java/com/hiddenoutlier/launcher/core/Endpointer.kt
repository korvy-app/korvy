package com.hiddenoutlier.launcher.core

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Decides when someone has finished speaking, from microphone frames (plain energy detection, no model):
 * waits for speech louder than the room's noise floor, then stops after [silenceMs] of quiet. Gives up after
 * [noSpeechMs] if nobody spoke, and always stops at [maxMs].
 */
class Endpointer(
    private val sampleRate: Int = 16_000,
    private val silenceMs: Int = 900,
    private val noSpeechMs: Int = 6_000,
    private val maxMs: Int = 20_000,
) {
    enum class Result { CONTINUE, DONE, NO_SPEECH }

    private var floor = -1.0
    private var elapsed = 0
    private var quietFor = 0
    var heard = false; private set
    /** Loudness of the last frame, 0–10 (for the mic button's pulse). */
    var level = 0f; private set

    fun feed(frame: FloatArray, n: Int = frame.size): Result {
        if (n <= 0) return Result.CONTINUE
        var sum = 0.0
        for (i in 0 until n) sum += frame[i] * frame[i]
        val rms = sqrt(sum / n)
        level = (20 * log10(rms / 0.003 + 1e-9)).toFloat().coerceIn(0f, 30f) / 3f
        val ms = n * 1000 / sampleRate
        elapsed += ms
        // The noise floor follows quiet sounds quickly and loud ones slowly (so speech doesn't raise it much).
        floor = when {
            floor < 0 -> rms
            rms < floor -> rms
            else -> floor * 0.995 + rms * 0.005
        }
        val speech = rms > maxOf(0.012, floor * 3)
        if (speech) { heard = true; quietFor = 0 } else if (heard) quietFor += ms
        return when {
            heard && quietFor >= silenceMs -> Result.DONE
            !heard && elapsed >= noSpeechMs -> Result.NO_SPEECH
            elapsed >= maxMs -> if (heard) Result.DONE else Result.NO_SPEECH
            else -> Result.CONTINUE
        }
    }

    companion object {
        /** Whisper marks non-speech like "[BLANK_AUDIO]" or "(music)"; drop those and tidy spaces. */
        fun clean(text: String): String = text.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\*[^*]*\\*"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }
}
