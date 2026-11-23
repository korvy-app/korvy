package com.hiddenoutlier.launcher.ui

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
import com.hiddenoutlier.launcher.assistant.Speaker
import com.hiddenoutlier.launcher.assistant.VoiceInput
import com.hiddenoutlier.launcher.data.Settings
import com.hiddenoutlier.launcher.data.SpeakMode
import kotlinx.coroutines.delay

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
    val voice = remember { VoiceInput(context) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }
    fun begin() {
        state.error = null; state.partial = ""; state.listening = true
        voice.start(
            onPartial = { state.partial = it },
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
            !voice.available() -> state.error = "This phone has no on-device speech recognition, so voice input is off (nothing is sent online)."
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
            Text("Voice", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Text("Speak replies", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeakMode.entries.forEach { m -> FilterChip(selected = s.speak == m, onClick = { vm.setSpeak(m) }, label = { Text(m.title) }) }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Listen when opened with the power button")
                        Text("Starts the microphone right away. Uses the phone's on-device speech recognition only.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(s.listenOnAssist, { vm.setListenOnAssist(it) })
                }
                Text("Speed %.1f×".format(s.ttsRate))
                Slider(s.ttsRate, { vm.setTtsRate((it * 10).toInt() / 10f) }, valueRange = 0.5f..2f)
                Text("Pitch %.1f".format(s.ttsPitch))
                Slider(s.ttsPitch, { vm.setTtsPitch((it * 10).toInt() / 10f) }, valueRange = 0.5f..2f)
                OutlinedButton(onClick = {
                    speaker.configure(Speaker.Config(s.ttsEngine, s.ttsVoice, s.ttsRate, s.ttsPitch)); speaker.stop(); speaker.say("Hi! This is how I'll sound."); vm.assistant.warnIfQuiet()
                }, modifier = Modifier.testTag("voice_test")) { Text("Test voice") }
                NaturalVoices(s, vm)
                Text("Speech engine", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
                Text("Any text-to-speech app you install appears here (e.g. RHVoice or eSpeak NG from F-Droid).", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Row(Modifier.fillMaxWidth().clickable { vm.setTtsEngine(null) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.ttsEngine == null, onClick = { vm.setTtsEngine(null) }); Text("Phone default")
                }
            }
            items(engines, key = { it.name }) { e ->
                Row(Modifier.fillMaxWidth().clickable { vm.setTtsEngine(e.name) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.ttsEngine == e.name, onClick = { vm.setTtsEngine(e.name) }); Text(e.label)
                }
            }
            item {
                Text("Voice", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show voices that need internet", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(showOnline, { showOnline = it })
                }
                androidx.compose.material3.OutlinedTextField(
                    value = voiceQuery, onValueChange = { voiceQuery = it }, singleLine = true,
                    placeholder = { Text("Search voices (language, country, name)") },
                    leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("voice_search"),
                )
                Row(Modifier.fillMaxWidth().clickable { vm.setTtsVoice(null) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.ttsVoice == null, onClick = { vm.setTtsVoice(null) }); Text("Engine default for my language")
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
    val allowed = s.allows(com.hiddenoutlier.launcher.platform.OnlineSource.VOICES)
    Text("Natural voices", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
    Text("Human-sounding voices that run entirely on this phone (open source, via sherpa-onnx). Nothing you hear is sent anywhere.",
        style = MaterialTheme.typography.bodySmall)
    if (!allowed) Text("Downloading needs Settings → Online features → Voice downloads.", color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    com.hiddenoutlier.launcher.assistant.NeuralVoiceCatalog.all.forEach { p ->
        val engineId = com.hiddenoutlier.launcher.assistant.Speaker.NEURAL_PREFIX + p.id
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
                    Text("${p.license} · ${p.sizeText}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(p.note, style = MaterialTheme.typography.bodySmall)
            when {
                download is com.hiddenoutlier.launcher.assistant.DownloadState.Running && (download as com.hiddenoutlier.launcher.assistant.DownloadState.Running).id == p.id -> {
                    val d = download as com.hiddenoutlier.launcher.assistant.DownloadState.Running
                    androidx.compose.material3.LinearProgressIndicator(progress = { d.done.toFloat() / d.total }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    Text("%.0f of %.0f MB".format(d.done / 1e6, d.total / 1e6), style = MaterialTheme.typography.bodySmall)
                }
                download is com.hiddenoutlier.launcher.assistant.DownloadState.Verifying && (download as com.hiddenoutlier.launcher.assistant.DownloadState.Verifying).id == p.id ->
                    Text("Checking and unpacking…", style = MaterialTheme.typography.bodySmall)
                has -> {
                    if (selected && p.speakers.size > 1) {
                        Text("Speaker", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        p.speakers.forEach { sp ->
                            Row(Modifier.fillMaxWidth().clickable { vm.setTtsVoice(sp.id.toString()) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = (s.ttsVoice?.toIntOrNull() ?: p.speakers.first().id) == sp.id, onClick = { vm.setTtsVoice(sp.id.toString()) })
                                Text("${sp.name} · ${sp.accent}")
                            }
                        }
                    }
                    androidx.compose.material3.TextButton(onClick = { vm.deleteVoice(p) }) { Text("Delete") }
                }
                else -> {
                    (download as? com.hiddenoutlier.launcher.assistant.DownloadState.Failed)?.takeIf { it.id == p.id }?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { vm.downloadVoice(p) }, enabled = allowed && download !is com.hiddenoutlier.launcher.assistant.DownloadState.Running,
                        modifier = Modifier.padding(top = 6.dp).testTag("download_voice_${p.id}")) { Text("Download ${p.sizeText}") }
                }
            }
        }
    }
}
