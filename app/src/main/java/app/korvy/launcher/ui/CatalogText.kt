package app.korvy.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.korvy.launcher.R
import app.korvy.launcher.assistant.ModelInfo
import app.korvy.launcher.assistant.NeuralSpeaker
import app.korvy.launcher.assistant.NeuralVoicePack

/** Translated catalog text for the screens; the catalogs keep English (used as the fallback). */
@Composable
fun ModelInfo.noteText(): String = when (id) {
    "gemma4-e2b" -> stringResource(R.string.model_note_gemma4_e2b)
    "gemma4-e4b" -> stringResource(R.string.model_note_gemma4_e4b)
    "smollm3-3b" -> stringResource(R.string.model_note_smollm3_3b)
    "qwen3.5-4b" -> stringResource(R.string.model_note_qwen3_5_4b)
    "qwen2.5-0.5b" -> stringResource(R.string.model_note_qwen2_5_0_5b)
    "qwen2.5-1.5b" -> stringResource(R.string.model_note_qwen2_5_1_5b)
    "qwen2.5-3b" -> stringResource(R.string.model_note_qwen2_5_3b)
    "llama3.2-1b" -> stringResource(R.string.model_note_llama3_2_1b)
    "llama3.2-3b" -> stringResource(R.string.model_note_llama3_2_3b)
    "gemma3-1b" -> stringResource(R.string.model_note_gemma3_1b)
    "gemma3-4b" -> stringResource(R.string.model_note_gemma3_4b)
    "smollm2-1.7b" -> stringResource(R.string.model_note_smollm2_1_7b)
    "phi3.5-mini" -> stringResource(R.string.model_note_phi3_5_mini)
    "whisper-tiny.en" -> stringResource(R.string.model_note_whisper_tiny_en)
    "whisper-base.en" -> stringResource(R.string.model_note_whisper_base_en)
    "whisper-small.en" -> stringResource(R.string.model_note_whisper_small_en)
    "whisper-base" -> stringResource(R.string.model_note_whisper_base)
    "whisper-small" -> stringResource(R.string.model_note_whisper_small)
    else -> note
}

@Composable
fun NeuralVoicePack.noteText(): String = when (id) {
    "kokoro-en" -> stringResource(R.string.voice_note_kokoro_en)
    "piper-ljspeech" -> stringResource(R.string.voice_note_piper_ljspeech)
    "piper-norman" -> stringResource(R.string.voice_note_piper_norman)
    "piper-es-mx-ald" -> stringResource(R.string.voice_note_piper_es_mx)
    "piper-es-es-davefx" -> stringResource(R.string.voice_note_piper_es_es)
    "kws-en" -> stringResource(R.string.voice_note_kws_en)
    else -> note
}

@Composable
fun NeuralSpeaker.accentText(): String = when (accent) {
    "American, female" -> stringResource(R.string.accent_us_f)
    "American, male" -> stringResource(R.string.accent_us_m)
    "British, female" -> stringResource(R.string.accent_uk_f)
    "British, male" -> stringResource(R.string.accent_uk_m)
    "Mexican Spanish" -> stringResource(R.string.accent_mx)
    "Spanish (Spain)" -> stringResource(R.string.accent_es)
    else -> accent
}
