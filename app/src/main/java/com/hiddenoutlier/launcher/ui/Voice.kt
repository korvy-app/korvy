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
                Row(Modifier.fillMaxWidth().clickable { vm.setTtsVoice(null) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.ttsVoice == null, onClick = { vm.setTtsVoice(null) }); Text("Engine default for my language")
                }
            }
            items(voices.filter { showOnline || !it.isNetworkConnectionRequired }, key = { it.name }) { v ->
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
