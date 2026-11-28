package app.korvy.launcher.ui

import kotlin.math.roundToInt
import androidx.compose.material3.Slider
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import app.korvy.launcher.platform.detailRes
import app.korvy.launcher.platform.titleRes
import app.korvy.launcher.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ContentCopy
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
import app.korvy.launcher.assistant.DownloadState
import app.korvy.launcher.assistant.ModelCatalog
import app.korvy.launcher.assistant.ModelInfo
import app.korvy.launcher.assistant.Personality
import app.korvy.launcher.data.Settings
import app.korvy.launcher.platform.Device
import app.korvy.launcher.platform.OnlineSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.korvy.launcher.assistant.Speaker
import app.korvy.launcher.data.SpeakMode

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
    speak: SpeakMode = SpeakMode.AFTER_VOICE,
    onSpeak: (SpeakMode) -> Unit = {},
    onSpeakOn: () -> Unit = {},
    cardActions: CardActions? = null,
    onReadAloud: ((Long) -> Unit)? = null,
    readingAloud: Long? = null,
    speechState: Speaker.State = Speaker.State.IDLE,
) {
    val listeningLabel = stringResource(R.string.listening)
    var input by remember { mutableStateOf("") }
    val mic = remember { MicState() }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    LaunchedEffect(chat.size, chat.lastOrNull()?.text) { if (chat.isNotEmpty()) list.animateScrollToItem(chat.lastIndex) }
    LaunchedEffect(Unit) { if (chat.isEmpty()) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().imePadding().testTag("assistant")) {
        TopBar(stringResource(R.string.assistant), onBack) {
            SpeakToggle(speak, onSpeak, onSpeakOn)
            TextButton(onClick = onOpenModels) { Text(modelName ?: stringResource(R.string.no_model), maxLines = 1) }
            if (chat.isNotEmpty() && !busy) IconButton(onClick = onClear) { Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.clear_conversation)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chat.isEmpty()) item {
                Column(Modifier.padding(8.dp)) {
                    Text(stringResource(R.string.ask_me_to_do_something), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    listOf("open camera", "timer 10 minutes", "weather", "news about science", "stock AAPL", "what's 18% of 64?")
                        .forEach { ex -> Text(stringResource(R.string.text, ex), color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onSend(ex) }.padding(vertical = 4.dp)) }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.everything_runs_on_this_phone),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            items(chat, key = { it.id }) { m -> Bubble(m, actions = cardActions, onSpeak = onReadAloud, speech = if (readingAloud == m.id) speechState else null, openLink = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) }
        }
        val status = mic.error ?: if (mic.listening) mic.partial.ifBlank { listeningLabel } else null
        status?.let { Text(it, color = if (mic.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp)) }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                // Enter sends (hardware keyboards and keyboards without a Send key insert a newline otherwise).
                onValueChange = { v -> if (v.contains('\n')) { val q = v.replace('\n', ' ').trim(); input = ""; if (!busy && q.isNotEmpty()) { mic.error = null; onSend(q) } } else input = v },
                placeholder = { Text(stringResource(R.string.ask_or_tell_me_to)) },
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
internal fun Bubble(m: ChatItem, openLink: (String) -> Unit, actions: CardActions? = null, onSpeak: ((Long) -> Unit)? = null, speech: Speaker.State? = null) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    val thinkingLabel = stringResource(R.string.thinking)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp))
                .background(if (m.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Long-press to select and copy any part of a message; web addresses open in the default browser.
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column {
                    Text(linkify(m.text.ifBlank { if (m.pending) thinkingLabel else "" }, MaterialTheme.colorScheme.primary))
                    m.lines.forEachIndexed { i, line ->
                        val url = m.lineLinks.getOrNull(i)
                        // A line with its own page (a headline) is a link to it; tapping opens the default browser.
                        val shown = if (url == null) linkify(line, MaterialTheme.colorScheme.primary) else androidx.compose.ui.text.buildAnnotatedString {
                            pushLink(androidx.compose.ui.text.LinkAnnotation.Url(url, androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(
                                color = MaterialTheme.colorScheme.primary, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))))
                            append(line); pop()
                        }
                        Text(shown, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            if (!m.pending && m.text.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                m.link?.let { url -> Text(stringResource(R.string.read_more), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, end = 12.dp).clickable { openLink(url) }) }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                // Read this reply aloud now, whatever the "speak replies" setting (tap again to stop).
                if (!m.fromUser && onSpeak != null) IconButton(onClick = { onSpeak(m.id) }, modifier = Modifier.size(28.dp).testTag("speak_${m.id}")) {
                    val readyLabel = stringResource(R.string.getting_ready_to_read_tap)
                    when (speech) {
                        // Getting the voice ready (first sentence can take a moment): spinner, tap to cancel.
                        Speaker.State.LOADING, Speaker.State.IDLE ->
                            Box(contentAlignment = androidx.compose.ui.Alignment.Center, modifier = Modifier.semantics { contentDescription = readyLabel }) {
                                androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            }
                        Speaker.State.SPEAKING ->
                            Icon(androidx.compose.material.icons.Icons.Outlined.Stop, stringResource(R.string.stop_reading), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        null -> Icon(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.read_aloud), modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString((listOf(m.text) + m.lines.mapIndexed { i, l ->
                        m.lineLinks.getOrNull(i)?.let { "$l ($it)" } ?: l }).joinToString("\n")))
                    android.widget.Toast.makeText(context, context.getString(R.string.copied), android.widget.Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.size(28.dp).testTag("copy_${m.id}")) {
                    Icon(androidx.compose.material.icons.Icons.Outlined.ContentCopy, "Copy", modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (m.card != null && actions != null) ActionCard(m.id, m.card, actions)
            if (m.pending) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

private val URL = Regex("""(https?://[^\s<>"')\]]+[^\s<>"')\].,;:!?])""")

/** Text with web addresses turned into links that open in the default browser. */
internal fun linkify(text: String, color: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        var last = 0
        for (m in URL.findAll(text)) {
            append(text.substring(last, m.range.first))
            pushLink(androidx.compose.ui.text.LinkAnnotation.Url(m.value, androidx.compose.ui.text.TextLinkStyles(
                androidx.compose.ui.text.SpanStyle(color = color, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))))
            append(m.value)
            pop()
            last = m.range.last + 1
        }
        append(text.substring(last))
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
    custom: List<ModelInfo> = emptyList(),
    onAddCustom: suspend (String) -> app.korvy.launcher.data.CustomModels.AddResult = { app.korvy.launcher.data.CustomModels.Problem(null) },
    onTuning: (temp: Float, maxTokens: Int, ctx: Int) -> Unit = { _, _, _ -> },
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
        TopBar(stringResource(R.string.assistant_model), onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(stringResource(R.string.models_run_entirely_on_this, ramGb, freeBytes / 1e9),
                    style = MaterialTheme.typography.bodySmall)
                if (!s.allows(OnlineSource.MODELS)) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.downloading_needs_online_features_model), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onOpenOnline) { Text(stringResource(R.string.open_online_features)) }
                } else if (metered && !s.modelsOnMobileData) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.you_re_on_mobile_data), color = MaterialTheme.colorScheme.error)
                }
            }
            items(ModelCatalog.all.filter { !it.retired || it.id in installed } + custom, key = { it.id }) { m ->
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
                            Text(if (m.custom) stringResource(R.string.custom_model_info, m.maker, m.sizeText)
                                else stringResource(R.string.text_2, m.maker, m.license, m.sizeText), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(if (m.custom) stringResource(R.string.custom_model_note) else m.noteText(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    if (tooBig) Text(stringResource(R.string.may_be_too_big_for).format(m.minRamGb),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    when {
                        download is DownloadState.Running && download.id == m.id -> {
                            LinearProgressIndicator(progress = { download.done.toFloat() / download.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.n0f_of_0f_mb).format(download.done / 1e6, download.total / 1e6), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = onCancel) { Text(stringResource(R.string.pause)) }
                            }
                        }
                        download is DownloadState.Retrying && download.id == m.id -> {
                            LinearProgressIndicator(progress = { download.done.toFloat() / download.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.connection_dropped_retrying_in_s, download.inSeconds), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = onCancel) { Text(stringResource(R.string.pause)) }
                            }
                        }
                        download is DownloadState.Verifying && download.id == m.id -> Text(stringResource(R.string.checking_the_download), modifier = Modifier.padding(top = 8.dp))
                        has -> Row(Modifier.padding(top = 6.dp)) {
                            if (!selected) Button(onClick = { onUse(m) }) { Text(stringResource(R.string.use)) } else Text(stringResource(R.string.in_use), modifier = Modifier.padding(end = 12.dp, top = 10.dp))
                            Spacer(Modifier.size(8.dp))
                            TextButton(onClick = { onDelete(m) }) { Text(stringResource(R.string.delete)) }
                        }
                        else -> {
                            if (download is DownloadState.Failed && download.id == m.id)
                                Text(download.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            val canDownload = s.allows(OnlineSource.MODELS) && (!metered || s.modelsOnMobileData) && download !is DownloadState.Running && download !is DownloadState.Retrying
                            OutlinedButton(onClick = { startDownload(m) }, enabled = canDownload, modifier = Modifier.padding(top = 6.dp).testTag("download_${m.id}")) {
                                Text(stringResource(R.string.download, m.sizeText))
                            }
                        }
                    }
                }
            }
            item(key = "custom_add") { CustomModelCard(onAddCustom) }
            item(key = "advanced") { AdvancedModelSettings(s, onTuning) }
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
        TopBar(stringResource(R.string.personality_agent_md), onBack) {
            TextButton(onClick = { text?.let { onSave(it); onBack() } }, modifier = Modifier.testTag("personality_save")) { Text(stringResource(R.string.save)) }
        }
        val t = text ?: return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.shapes_how_the_assistant_words),
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
            Text(if (over) stringResource(R.string.about_tokens_long, t.length / 4) else stringResource(R.string.about_tokens_n, t.length / 4),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Settings → Online features: master switch plus one switch per source, each saying exactly where it connects. */
@Composable
fun OnlineSection(s: Settings, onMaster: (Boolean) -> Unit, onSource: (OnlineSource, Boolean) -> Unit,
                  onHomePlace: (String) -> Unit, onFahrenheit: (Boolean) -> Unit, onMobileData: (Boolean) -> Unit,
                  onUseLocation: ((Boolean) -> Unit)? = null,
                  toggle: @Composable (String, String, Boolean, String, Boolean, (Boolean) -> Unit) -> Unit) {
    toggle(stringResource(R.string.online_title), stringResource(R.string.online_detail), s.online, "toggle_online", true, onMaster)
    OnlineSource.entries.filter { it != OnlineSource.VOICES || app.korvy.launcher.BuildConfig.NATURAL_VOICES }.forEach { src ->
        toggle(stringResource(src.titleRes), stringResource(src.detailRes) + " (" + src.hosts.joinToString() + ")", s.allows(src), "toggle_src_${src.name}", s.online) { onSource(src, it) }
    }
    if (s.online) {
        var place by remember(s.homePlace) { mutableStateOf(s.homePlace) }
        OutlinedTextField(
            value = place, onValueChange = { place = it }, singleLine = true, label = { Text(stringResource(R.string.home_town_for_weather)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).testTag("home_place"),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onHomePlace(place) }),
            supportingText = { Text(stringResource(R.string.typed_by_you_no_location)) },
        )
        if (place != s.homePlace) TextButton(onClick = { onHomePlace(place) }, modifier = Modifier.padding(start = 12.dp)) { Text(stringResource(R.string.save_home_town)) }
        toggle(stringResource(R.string.fahrenheit), stringResource(R.string.fahrenheit_detail), s.fahrenheit, "toggle_fahrenheit", true, onFahrenheit)
        onUseLocation?.let { toggle(stringResource(R.string.use_location), stringResource(R.string.use_location_detail), s.useLocation, "toggle_location", true, it) }
        toggle(stringResource(R.string.mobile_data), stringResource(R.string.mobile_data_detail), s.modelsOnMobileData, "toggle_mobile_data", true, onMobileData)
    }
}


/** Paste a Hugging Face link to a .gguf file; it joins the list with a Download button. */
@Composable
private fun CustomModelCard(onAdd: suspend (String) -> app.korvy.launcher.data.CustomModels.AddResult) {
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    val scope = rememberCoroutineScope()
    val added = stringResource(R.string.custom_added)
    val errHost = stringResource(R.string.custom_err_host); val errFile = stringResource(R.string.custom_err_file)
    val errGguf = stringResource(R.string.custom_err_gguf); val errFetch = stringResource(R.string.custom_err_fetch)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp).testTag("custom_model")) {
        Text(stringResource(R.string.custom_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.custom_detail), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        OutlinedTextField(value = link, onValueChange = { link = it; message = null }, singleLine = true,
            label = { Text(stringResource(R.string.custom_link_hint)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("custom_link"))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Button(enabled = link.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    message = when (val r = onAdd(link)) {
                        is app.korvy.launcher.data.CustomModels.Added -> { link = ""; added to false }
                        is app.korvy.launcher.data.CustomModels.Problem -> when (r.error) {
                            app.korvy.launcher.core.HfLink.Error.NOT_HUGGING_FACE -> errHost to true
                            app.korvy.launcher.core.HfLink.Error.NOT_A_FILE -> errFile to true
                            app.korvy.launcher.core.HfLink.Error.NOT_GGUF -> errGguf to true
                            null -> errFetch to true
                        }
                    }
                    busy = false
                }
            }, modifier = Modifier.testTag("custom_add")) { Text(stringResource(R.string.custom_add)) }
            if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
        }
        message?.let { (text, error) ->
            Text(text, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Temperature, reply length and context size for the curious; closed by default, with a reset. */
@Composable
private fun AdvancedModelSettings(s: Settings, onTuning: (Float, Int, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var temp by remember(s.modelTemp) { mutableStateOf(s.modelTemp) }
    var maxTokens by remember(s.modelMaxTokens) { mutableStateOf(s.modelMaxTokens.toFloat()) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).testTag("model_advanced")) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.advanced), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (!open) return@Column
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
            Text(stringResource(R.string.advanced_detail), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.temp_label, temp), modifier = Modifier.padding(top = 10.dp))
            Slider(temp, { temp = (it * 10).roundToInt() / 10f }, valueRange = 0f..1.5f, steps = 14,
                onValueChangeFinished = { onTuning(temp, s.modelMaxTokens, s.modelCtx) }, modifier = Modifier.testTag("temp_slider"))
            Text(stringResource(R.string.temp_detail), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.max_tokens_label, maxTokens.roundToInt()), modifier = Modifier.padding(top = 10.dp))
            Slider(maxTokens, { maxTokens = ((it / 20).roundToInt() * 20).toFloat() }, valueRange = 100f..1000f,
                onValueChangeFinished = { onTuning(s.modelTemp, maxTokens.roundToInt(), s.modelCtx) }, modifier = Modifier.testTag("max_tokens_slider"))
            Text(stringResource(R.string.max_tokens_detail), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.ctx_label), modifier = Modifier.padding(top = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.modelCtx != 8192, onClick = { onTuning(s.modelTemp, s.modelMaxTokens, 4096) }, label = { Text(stringResource(R.string.ctx_4k)) })
                FilterChip(selected = s.modelCtx == 8192, onClick = { onTuning(s.modelTemp, s.modelMaxTokens, 8192) }, label = { Text(stringResource(R.string.ctx_8k)) })
            }
            Text(stringResource(R.string.ctx_detail), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onTuning(Settings.DEFAULT_TEMP, Settings.DEFAULT_MAX_TOKENS, Settings.DEFAULT_CTX) },
                modifier = Modifier.padding(top = 6.dp).testTag("tuning_reset")) { Text(stringResource(R.string.reset_defaults)) }
        }
    }
}
