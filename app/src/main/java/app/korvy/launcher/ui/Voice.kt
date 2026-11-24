package app.korvy.launcher.ui

import app.korvy.launcher.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import android.speech.tts.Voice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.korvy.launcher.assistant.Speaker
import app.korvy.launcher.data.Settings
import app.korvy.launcher.data.SpeakMode
import kotlinx.coroutines.delay
import app.korvy.launcher.LauncherApp
import app.korvy.launcher.assistant.DownloadState
import app.korvy.launcher.assistant.ModelCatalog
import app.korvy.launcher.assistant.NeuralVoiceCatalog
import app.korvy.launcher.assistant.VoiceInput
import app.korvy.launcher.core.WordPieces
import app.korvy.launcher.platform.OnlineSource

/** Live state of one voice-input session, shared between the mic button and the text it fills in. */
class MicState {
    var listening by mutableStateOf(false)
    var partial by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var level by mutableFloatStateOf(0f)
    internal var startRequest by mutableStateOf(0)
    /** Ask the mic button to start (e.g. when the power-button panel opens). */
    fun requestStart() { startRequest++ }
}

/**
 * Mic button: asks for microphone permission when first needed, then listens with the phone's on-device
 * recognizer only. [onResult] gets the final words. The mic is on only while this shows "listening".
 */
@Composable
fun MicButton(state: MicState, onResult: (String) -> Unit, modifier: Modifier = Modifier, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    val context = LocalContext.current
    val voice = remember { (context.applicationContext as LauncherApp).assistant.speechInput }
    DisposableEffect(Unit) { onDispose { voice.cancel() } }
    fun begin() {
        state.error = null; state.partial = ""; state.listening = true
        voice.start(
            // "…" = recording finished, now transcribing: the mic is off, so stop showing it as listening.
            onPartial = { if (it == "…") state.listening = false; state.partial = it },
            onFinal = { state.listening = false; state.partial = ""; if (it.isNotBlank()) onResult(it) },
            onError = { state.listening = false; state.error = it },
            onLevel = { state.level = it },
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else state.error = "Voice needs microphone permission. You can still type."
    }
    fun toggle() {
        when {
            state.listening -> { voice.stop(); state.listening = false }
            !voice.available() -> state.error = "No offline speech recognition on this phone yet. Download a Whisper model in Settings → Voice (nothing is ever sent online)."
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> begin()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(state.startRequest) { if (state.startRequest > 0 && !state.listening) toggle() }
    val pulse by animateFloatAsState(if (state.listening) 1f + (state.level.coerceIn(0f, 10f) / 30f) else 1f, label = "mic")
    IconButton(onClick = ::toggle, modifier = modifier.testTag("mic")) {
        Box(
            Modifier.size(40.dp).scale(pulse).clip(CircleShape)
                .background(if (state.listening) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (state.listening) Icons.Filled.Mic else Icons.Outlined.Mic, if (state.listening) "Stop listening" else "Speak",
                tint = if (state.listening) MaterialTheme.colorScheme.onPrimary else tint)
        }
    }
}

/** Settings → Assistant → Voice: when to speak, which engine and voice, speed and pitch. */
@Composable
fun VoiceSettingsScreen(s: Settings, vm: LauncherViewModel, onBack: () -> Unit) {
    val speaker = vm.assistant.speaker
    var engines by remember { mutableStateOf(speaker.engines()) }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var showOnline by remember { mutableStateOf(false) }
    var voiceQuery by remember { mutableStateOf("") }
    LaunchedEffect(s.ttsEngine) {
        speaker.configure(Speaker.Config(s.ttsEngine, s.ttsVoice, s.ttsRate, s.ttsPitch)); speaker.warmUp()
        repeat(30) { if (speaker.isReady()) return@repeat; delay(100) }
        voices = speaker.voices(); engines = speaker.engines()
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().testTag("voice_settings")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(stringResource(R.string.voice), style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Text(stringResource(R.string.speak_replies), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeakMode.entries.forEach { m -> FilterChip(selected = s.speak == m, onClick = { vm.setSpeak(m) }, label = { Text(m.title) }) }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.listen_when_opened_with_the))
                        Text(stringResource(R.string.starts_the_microphone_right_away), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(s.listenOnAssist, { vm.setListenOnAssist(it) })
                }
                SpeechRecognitionSection(s, vm)
                if (app.korvy.launcher.core.Feature.WAKE_WORD.on(s.flagsOn, s.flagsOff)) WakeWordSection(s, vm)
                Text(stringResource(R.string.speed_1f).format(s.ttsRate))
                Slider(s.ttsRate, { vm.setTtsRate((it * 10).toInt() / 10f) }, valueRange = 0.5f..2f)
                Text(stringResource(R.string.pitch_1f).format(s.ttsPitch))
                Slider(s.ttsPitch, { vm.setTtsPitch((it * 10).toInt() / 10f) }, valueRange = 0.5f..2f)
                OutlinedButton(onClick = {
                    speaker.configure(Speaker.Config(s.ttsEngine, s.ttsVoice, s.ttsRate, s.ttsPitch)); speaker.stop(); speaker.say("Hi! This is how I'll sound."); vm.assistant.warnIfQuiet()
                }, modifier = Modifier.testTag("voice_test")) { Text(stringResource(R.string.test_voice)) }
                NaturalVoices(s, vm)
                Text(stringResource(R.string.speech_engine), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
                Text(stringResource(R.string.any_text_to_speech_app), style = MaterialTheme.typography.bodySmall)
            }
            item {
                Row(Modifier.fillMaxWidth().clickable { vm.setTtsEngine(null) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.ttsEngine == null, onClick = { vm.setTtsEngine(null) }); Text(stringResource(R.string.phone_default))
                }
            }
            items(engines, key = { it.name }) { e ->
                Row(Modifier.fillMaxWidth().clickable { vm.setTtsEngine(e.name) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.ttsEngine == e.name, onClick = { vm.setTtsEngine(e.name) }); Text(e.label)
                }
            }
            item {
                Text(stringResource(R.string.voice), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.show_voices_that_need_internet), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(showOnline, { showOnline = it })
                }
                androidx.compose.material3.OutlinedTextField(
                    value = voiceQuery, onValueChange = { voiceQuery = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_voices_language_country_name)) },
                    leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("voice_search"),
                )
                Row(Modifier.fillMaxWidth().clickable { vm.setTtsVoice(null) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.ttsVoice == null, onClick = { vm.setTtsVoice(null) }); Text(stringResource(R.string.engine_default_for_my_language))
                }
            }
            val q = voiceQuery.trim().lowercase()
            items(voices.filter { (showOnline || !it.isNetworkConnectionRequired) && (q.isEmpty() ||
                listOf(it.locale.displayName, it.locale.displayLanguage, it.locale.displayCountry, it.locale.toLanguageTag(), it.name)
                    .any { f -> f.lowercase().contains(q) }) }, key = { it.name }) { v ->
                Row(Modifier.fillMaxWidth().clickable { vm.setTtsVoice(v.name) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.ttsVoice == v.name, onClick = { vm.setTtsVoice(v.name) })
                    Column {
                        Text(v.locale.displayName)
                        Text(v.name + if (v.isNetworkConnectionRequired) " · needs internet" else " · on this phone", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Speaker button in the assistant: on = speak every reply; off = speak only after the user spoke (the default). */
@Composable
fun SpeakToggle(mode: SpeakMode, onChange: (SpeakMode) -> Unit, onTurnedOn: () -> Unit = {}) {
    val on = mode == SpeakMode.ALWAYS
    IconButton(onClick = { if (on) onChange(SpeakMode.AFTER_VOICE) else { onChange(SpeakMode.ALWAYS); onTurnedOn() } },
        modifier = Modifier.testTag("speak_toggle")) {
        Icon(if (on) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
            if (on) "Spoken replies on (tap to only speak after I talk)" else "Spoken replies off (tap to speak every reply)",
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Natural voices (Kokoro, Piper) that run on the phone: download, choose, pick a speaker, delete. */
@Composable
private fun NaturalVoices(s: Settings, vm: LauncherViewModel) {
    val installed by vm.voices.installed.collectAsStateWithLifecycle()
    val download by vm.voices.download.collectAsStateWithLifecycle()
    val allowed = s.allows(OnlineSource.VOICES)
    Text(stringResource(R.string.natural_voices), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
    Text(stringResource(R.string.human_sounding_voices_that_run),
        style = MaterialTheme.typography.bodySmall)
    if (!proUnlocked(vm)) { ProUnlock(vm, stringResource(R.string.natural_voices)); return }
    if (!allowed) Text(stringResource(R.string.downloading_needs_settings_online_featur), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    NeuralVoiceCatalog.all.forEach { p ->
        val engineId = Speaker.NEURAL_PREFIX + p.id
        val has = p.id in installed
        val selected = has && s.ttsEngine == engineId
        Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp).testTag("voice_${p.id}")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (has) RadioButton(selected = selected, onClick = { vm.setTtsEngine(engineId) })
                Column(Modifier.weight(1f)) {
                    Text(p.name)
                    Text(stringResource(R.string.text_4, p.license, p.sizeText), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(p.note, style = MaterialTheme.typography.bodySmall)
            when {
                download is DownloadState.Running && (download as DownloadState.Running).id == p.id -> {
                    val d = download as DownloadState.Running
                    androidx.compose.material3.LinearProgressIndicator(progress = { d.done.toFloat() / d.total }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    Text(stringResource(R.string.n0f_of_0f_mb).format(d.done / 1e6, d.total / 1e6), style = MaterialTheme.typography.bodySmall)
                }
                download is DownloadState.Verifying && (download as DownloadState.Verifying).id == p.id ->
                    Text(stringResource(R.string.checking_and_unpacking), style = MaterialTheme.typography.bodySmall)
                has -> {
                    if (selected && p.speakers.size > 1) {
                        Text(stringResource(R.string.speaker), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        p.speakers.forEach { sp ->
                            Row(Modifier.fillMaxWidth().clickable { vm.setTtsVoice(sp.id.toString()) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = (s.ttsVoice?.toIntOrNull() ?: p.speakers.first().id) == sp.id, onClick = { vm.setTtsVoice(sp.id.toString()) })
                                Text(stringResource(R.string.text_4, sp.name, sp.accent))
                            }
                        }
                    }
                    androidx.compose.material3.TextButton(onClick = { vm.deleteVoice(p) }) { Text(stringResource(R.string.delete)) }
                }
                else -> {
                    (download as? DownloadState.Failed)?.takeIf { it.id == p.id }?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { vm.downloadVoice(p) }, enabled = allowed && download !is DownloadState.Running,
                        modifier = Modifier.padding(top = 6.dp).testTag("download_voice_${p.id}")) { Text(stringResource(R.string.download, p.sizeText)) }
                }
            }
        }
    }
}

/** Settings → Voice → Speech recognition: the phone's own recognizer or a downloaded Whisper model. */
@Composable
internal fun SpeechRecognitionSection(s: Settings, vm: LauncherViewModel) {
    val installed by vm.models.installed.collectAsStateWithLifecycle()
    val download by vm.models.download.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val phoneHasOne = remember { VoiceInput(context).available() }
    Text(stringResource(R.string.speech_recognition), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
    Text(stringResource(R.string.turns_what_you_say_into) +
        "and is often more accurate; the phone's recognizer starts a little faster.", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth().clickable(enabled = phoneHasOne) { vm.setSttModel(null) }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = s.sttModel == null && phoneHasOne, enabled = phoneHasOne, onClick = { vm.setSttModel(null) })
        Column {
            Text(stringResource(R.string.phone_s_on_device_recognizer))
            if (!phoneHasOne) Text(stringResource(R.string.not_available_on_this_phone), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (!proUnlocked(vm)) { ProUnlock(vm, stringResource(R.string.whisper_models)); return }
    ModelCatalog.speech.forEach { m ->
        val have = m.id in installed
        val state = download
        Row(Modifier.fillMaxWidth().clickable(enabled = have) { vm.setSttModel(m.id) }.padding(vertical = 2.dp).testTag("stt_${m.id}"),
            verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = have && (s.sttModel == m.id || (s.sttModel == null && !phoneHasOne && installed.firstOrNull { it.startsWith("whisper") } == m.id)),
                enabled = have, onClick = { vm.setSttModel(m.id) })
            Column(Modifier.weight(1f)) {
                Text(m.name)
                Text(stringResource(R.string.text_4, m.sizeText, m.note), style = MaterialTheme.typography.bodySmall)
                when {
                    state is DownloadState.Running && state.id == m.id ->
                        androidx.compose.material3.LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    state is DownloadState.Verifying && state.id == m.id -> Text(stringResource(R.string.checking), style = MaterialTheme.typography.bodySmall)
                    state is DownloadState.Retrying && state.id == m.id -> Text(stringResource(R.string.connection_dropped_retrying_in_s_2, state.inSeconds), style = MaterialTheme.typography.bodySmall)
                    state is DownloadState.Failed && state.id == m.id -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            when {
                have -> TextButton(onClick = { vm.deleteModel(m) }) { Text(stringResource(R.string.delete)) }
                (state as? DownloadState.Running)?.id == m.id -> TextButton(onClick = { vm.cancelDownload() }) { Text(stringResource(R.string.cancel)) }
                else -> TextButton(onClick = { vm.downloadModel(m) }, enabled = state is DownloadState.Idle ||
                    state is DownloadState.Failed, modifier = Modifier.testTag("stt_get_${m.id}")) { Text(stringResource(R.string.download_2)) }
            }
        }
    }
}

/** Settings → Voice → Wake word (Pro, opt-in). */
@Composable
internal fun WakeWordSection(s: Settings, vm: LauncherViewModel) {
    val installed by vm.voices.installed.collectAsStateWithLifecycle()
    val download by vm.voices.download.collectAsStateWithLifecycle()
    val model = NeuralVoiceCatalog.wakeWord
    val have = model.id in installed
    val context = LocalContext.current
    Text(stringResource(R.string.wake_word), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
    Text(stringResource(R.string.say_your_wake_word_to) +
        "nothing else is recognised, kept or sent. Android shows a notification and the green mic dot while it listens. Uses some battery.",
        style = MaterialTheme.typography.bodySmall)
    if (!proUnlocked(vm)) { ProUnlock(vm, stringResource(R.string.wake_words)); return }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.setWake(on = true) }
    if (!have) {
        val d = download
        when {
            d is DownloadState.Running && d.id == model.id ->
                androidx.compose.material3.LinearProgressIndicator(progress = { d.done.toFloat() / d.total }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            d is DownloadState.Verifying && d.id == model.id -> Text(stringResource(R.string.checking_and_unpacking), style = MaterialTheme.typography.bodySmall)
            else -> OutlinedButton(onClick = { vm.downloadWakeModel() }, modifier = Modifier.padding(top = 6.dp).testTag("wake_download")) { Text(stringResource(R.string.download_wake_word_model, model.sizeText)) }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.listen_for_the_wake_word), modifier = Modifier.weight(1f))
        Switch(s.wakeWord, { on ->
            if (on && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            else vm.setWake(on = on)
        }, modifier = Modifier.testTag("wake_toggle"))
    }
    var phrase by remember(s.wakePhrase) { mutableStateOf(s.wakePhrase) }
    val syllables = WordPieces.syllables(phrase)
    val usable = phrase.any { it.isLetter() } && phrase.all { it.isLetter() || it == ' ' || it == '\'' || it == ',' }
    androidx.compose.material3.OutlinedTextField(
        value = phrase, onValueChange = { phrase = it.take(30) }, singleLine = true, label = { Text(stringResource(R.string.wake_word)) },
        supportingText = {
            Text(when {
                !usable -> "Letters only (English)."
                syllables < 3 -> "Short or everyday words wake it by mistake more often. Try 3+ syllables, like “Hey Korvy”."
                else -> "Good: distinctive phrases work best."
            })
        },
        modifier = Modifier.fillMaxWidth().testTag("wake_phrase"),
    )
    if (phrase != s.wakePhrase) TextButton(onClick = { vm.setWake(phrase = phrase.trim()) }, enabled = usable) { Text(stringResource(R.string.use_2, phrase.trim())) }
    val models by vm.models.installed.collectAsStateWithLifecycle()
    if (models.none { it.startsWith("whisper") }) {
        val tiny = app.korvy.launcher.assistant.ModelCatalog.byId("whisper-tiny.en")!!
        Text(stringResource(R.string.for_fewer_false_wakes_and) +
            "Whisper tiny is enough (${tiny.sizeText}).", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        OutlinedButton(onClick = { vm.downloadModel(tiny); vm.setWake() }, modifier = Modifier.testTag("wake_get_whisper")) { Text(stringResource(R.string.download_whisper_tiny)) }
    } else Text(stringResource(R.string.each_wake_is_double_checked), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    Text(stringResource(R.string.sensitivity), modifier = Modifier.padding(top = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Fewer false wakes", "Balanced", "Hears me better").forEachIndexed { i, label ->
            FilterChip(selected = s.wakeSensitivity == i, onClick = { vm.setWake(sensitivity = i) }, label = { Text(label) })
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.only_while_the_screen_is))
            Text(stringResource(R.string.saves_battery_always_pauses_in), style = MaterialTheme.typography.bodySmall)
        }
        Switch(s.wakeScreenOnly, { vm.setWake(screenOnly = it) })
    }
}
