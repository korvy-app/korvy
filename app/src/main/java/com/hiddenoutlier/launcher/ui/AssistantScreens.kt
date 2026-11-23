package com.hiddenoutlier.launcher.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hiddenoutlier.launcher.assistant.DownloadState
import com.hiddenoutlier.launcher.assistant.ModelCatalog
import com.hiddenoutlier.launcher.assistant.ModelInfo
import com.hiddenoutlier.launcher.assistant.Personality
import com.hiddenoutlier.launcher.data.Settings
import com.hiddenoutlier.launcher.platform.Device
import com.hiddenoutlier.launcher.platform.OnlineSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        actions()
    }
}

/** The assistant chat. Common requests run as plain code; the rest go to the user's chosen on-device model. */
@Composable
fun AssistantScreen(
    chat: List<ChatItem>,
    busy: Boolean,
    modelName: String?,
    onSend: (String) -> Unit,
    onVoice: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onOpenModels: () -> Unit,
    onBack: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val mic = remember { MicState() }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    LaunchedEffect(chat.size, chat.lastOrNull()?.text) { if (chat.isNotEmpty()) list.animateScrollToItem(chat.lastIndex) }
    LaunchedEffect(Unit) { if (chat.isEmpty()) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().imePadding().testTag("assistant")) {
        TopBar("Assistant", onBack) {
            TextButton(onClick = onOpenModels) { Text(modelName ?: "No model", maxLines = 1) }
            if (chat.isNotEmpty() && !busy) IconButton(onClick = onClear) { Icon(Icons.Outlined.DeleteSweep, "Clear conversation") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chat.isEmpty()) item {
                Column(Modifier.padding(8.dp)) {
                    Text("Ask me to do something.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    listOf("open camera", "timer 10 minutes", "weather", "news about science", "stock AAPL", "what's 18% of 64?")
                        .forEach { ex -> Text("“$ex”", color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onSend(ex) }.padding(vertical = 4.dp)) }
                    Spacer(Modifier.height(8.dp))
                    Text("Everything runs on this phone. Weather, news, stocks and quick answers only go online if you allow them in Settings → Online features.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            items(chat, key = { it.id }) { m -> Bubble(m) { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
        }
        val status = mic.error ?: if (mic.listening) mic.partial.ifBlank { "Listening…" } else null
        status?.let { Text(it, color = if (mic.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp)) }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                // Enter sends (hardware keyboards and keyboards without a Send key insert a newline otherwise).
                onValueChange = { v -> if (v.contains('\n')) { val q = v.replace('\n', ' ').trim(); input = ""; if (!busy && q.isNotEmpty()) { mic.error = null; onSend(q) } } else input = v },
                placeholder = { Text("Ask or tell me to do something") },
                modifier = Modifier.weight(1f).focusRequester(focus).testTag("assistant_input"), maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!busy && input.isNotBlank()) { onSend(input); input = "" } }),
            )
            MicButton(mic, onResult = onVoice)
            if (busy) IconButton(onClick = onStop, modifier = Modifier.testTag("assistant_stop")) { Icon(Icons.Outlined.Stop, "Stop") }
            else IconButton(onClick = { if (input.isNotBlank()) { onSend(input); input = "" } }, modifier = Modifier.testTag("assistant_send")) {
                Icon(Icons.AutoMirrored.Outlined.Send, "Send")
            }
        }
    }
}

@Composable
internal fun Bubble(m: ChatItem, openLink: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp))
                .background(if (m.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(m.text.ifBlank { if (m.pending) "Thinking…" else "" })
            m.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp)) }
            m.link?.let { url -> Text("Read more", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp).clickable { openLink(url) }) }
            if (m.pending) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

/** Pick, download, switch and delete models. Shows each model's maker, license, size and memory needs. */
@Composable
fun ModelsScreen(
    s: Settings,
    installed: Set<String>,
    download: DownloadState,
    freeBytes: Long,
    metered: Boolean,
    onDownload: (ModelInfo) -> Unit,
    onCancel: () -> Unit,
    onUse: (ModelInfo) -> Unit,
    onDelete: (ModelInfo) -> Unit,
    onOpenOnline: () -> Unit,
    onBack: () -> Unit,
) {
    val ramGb = Device.specs(LocalContext.current).totalRamMb / 1024.0
    // Ask (once) to show the download's progress notification. The download runs either way.
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    val ctx = LocalContext.current
    val startDownload: (ModelInfo) -> Unit = { m ->
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        onDownload(m)
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().testTag("models")) {
        TopBar("Assistant model", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Models run entirely on this phone. Bigger models are smarter but slower and need more memory. " +
                    "This phone has %.1f GB of memory and %.1f GB free storage.".format(ramGb, freeBytes / 1e9),
                    style = MaterialTheme.typography.bodySmall)
                if (!s.allows(OnlineSource.MODELS)) {
                    Spacer(Modifier.height(8.dp))
                    Text("Downloading needs Online features → Model downloads.", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onOpenOnline) { Text("Open Online features") }
                } else if (metered && !s.modelsOnMobileData) {
                    Spacer(Modifier.height(8.dp))
                    Text("You're on mobile data. Downloads wait for Wi-Fi (change this in Online features).", color = MaterialTheme.colorScheme.error)
                }
            }
            items(ModelCatalog.all, key = { it.id }) { m ->
                val has = m.id in installed
                val selected = s.modelId == m.id && has
                val tooBig = ramGb < m.minRamGb
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(14.dp).testTag("model_${m.id}")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (has) RadioButton(selected = selected, onClick = { onUse(m) })
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleMedium)
                            Text("${m.maker} · ${m.license} · ${m.sizeText}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(m.note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    if (tooBig) Text("May be too big for this phone (best with %.0f GB+ memory).".format(m.minRamGb),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    when {
                        download is DownloadState.Running && download.id == m.id -> {
                            LinearProgressIndicator(progress = { download.done.toFloat() / download.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("%.0f of %.0f MB".format(download.done / 1e6, download.total / 1e6), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = onCancel) { Text("Pause") }
                            }
                        }
                        download is DownloadState.Retrying && download.id == m.id -> {
                            LinearProgressIndicator(progress = { download.done.toFloat() / download.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Connection dropped; retrying in ${download.inSeconds} s", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = onCancel) { Text("Pause") }
                            }
                        }
                        download is DownloadState.Verifying && download.id == m.id -> Text("Checking the download…", modifier = Modifier.padding(top = 8.dp))
                        has -> Row(Modifier.padding(top = 6.dp)) {
                            if (!selected) Button(onClick = { onUse(m) }) { Text("Use") } else Text("In use", modifier = Modifier.padding(end = 12.dp, top = 10.dp))
                            Spacer(Modifier.size(8.dp))
                            TextButton(onClick = { onDelete(m) }) { Text("Delete") }
                        }
                        else -> {
                            if (download is DownloadState.Failed && download.id == m.id)
                                Text(download.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            val canDownload = s.allows(OnlineSource.MODELS) && (!metered || s.modelsOnMobileData) && download !is DownloadState.Running && download !is DownloadState.Retrying
                            OutlinedButton(onClick = { startDownload(m) }, enabled = canDownload, modifier = Modifier.padding(top = 6.dp).testTag("download_${m.id}")) {
                                Text("Download ${m.sizeText}")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Edit agent.md: the assistant's personality. Presets, a size meter, and a note that rules always win. */
@Composable
fun PersonalityScreen(personality: Personality, onSave: (String) -> Unit, onBack: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { text = withContext(Dispatchers.IO) { personality.load() } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().imePadding().testTag("personality")) {
        TopBar("Personality (agent.md)", onBack) {
            TextButton(onClick = { text?.let { onSave(it); onBack() } }, modifier = Modifier.testTag("personality_save")) { Text("Save") }
        }
        val t = text ?: return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Shapes how the assistant words its answers. Its safety rules (ask before acting, never see hidden apps) always come first.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Personality.PRESETS.forEach { (name, preset) -> FilterChip(selected = t == preset, onClick = { text = preset }, label = { Text(name) }) }
            }
            OutlinedTextField(
                value = t, onValueChange = { if (it.length <= Personality.MAX_CHARS) text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).testTag("personality_text"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            val over = t.length > Personality.SOFT_LIMIT_CHARS
            LinearProgressIndicator(progress = { (t.length.toFloat() / Personality.MAX_CHARS).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text("About ${t.length / 4} tokens." + if (over) " Long personalities make small models slower to start answering." else "",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Settings → Online features: master switch plus one switch per source, each saying exactly where it connects. */
@Composable
fun OnlineSection(s: Settings, onMaster: (Boolean) -> Unit, onSource: (OnlineSource, Boolean) -> Unit,
                  onHomePlace: (String) -> Unit, onFahrenheit: (Boolean) -> Unit, onMobileData: (Boolean) -> Unit,
                  toggle: @Composable (String, String, Boolean, String, Boolean, (Boolean) -> Unit) -> Unit) {
    toggle("Online features", "Off: the app never connects to the internet. On: only the sources below that you leave on.", s.online, "toggle_online", true, onMaster)
    OnlineSource.entries.forEach { src ->
        toggle(src.title, src.detail + " (" + src.hosts.joinToString() + ")", s.allows(src), "toggle_src_${src.name}", s.online) { onSource(src, it) }
    }
    if (s.online) {
        var place by remember(s.homePlace) { mutableStateOf(s.homePlace) }
        OutlinedTextField(
            value = place, onValueChange = { place = it }, singleLine = true, label = { Text("Home town for weather") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).testTag("home_place"),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onHomePlace(place) }),
            supportingText = { Text("Typed by you; no location permission is used.") },
        )
        if (place != s.homePlace) TextButton(onClick = { onHomePlace(place) }, modifier = Modifier.padding(start = 12.dp)) { Text("Save home town") }
        toggle("Fahrenheit", "Show temperatures in °F (off: °C).", s.fahrenheit, "toggle_fahrenheit", true, onFahrenheit)
        toggle("Download models on mobile data", "Models are 0.5–2.5 GB. Off: downloads wait for Wi-Fi.", s.modelsOnMobileData, "toggle_mobile_data", true, onMobileData)
    }
}
