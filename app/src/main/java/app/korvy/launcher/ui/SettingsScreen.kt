package app.korvy.launcher.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import app.korvy.launcher.data.titleRes
import app.korvy.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.korvy.launcher.BuildConfig
import app.korvy.launcher.core.Tier
import app.korvy.launcher.data.Backgrounds
import app.korvy.launcher.data.SearchEngine
import app.korvy.launcher.data.Settings
import app.korvy.launcher.platform.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import app.korvy.launcher.assistant.ModelCatalog

@Composable
fun SettingsScreen(
    s: Settings,
    animationsOn: Boolean,
    tier: Tier,
    backgrounds: List<File>,
    isDefaultHome: Boolean,
    vm: LauncherViewModel,
    onSetDefaultHome: () -> Unit,
    onBack: () -> Unit,
) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) vm.importBackgrounds(uris)
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.fillMaxSize().testTag("settings"), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                Section(stringResource(R.string.pro))
                ProSection(vm)
                Section(stringResource(R.string.home_screen))
                if (isDefaultHome) Note(stringResource(R.string.this_is_your_home_screen))
                else Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Button(onClick = onSetDefaultHome, modifier = Modifier.testTag("set_default_home")) { Text(stringResource(R.string.make_this_my_home_screen)) }
                }
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    OutlinedButton(onClick = { vm.open(Screen.WIDGETS) }, modifier = Modifier.testTag("add_widget")) { Text(stringResource(R.string.add_a_widget)) }
                }
                Note(stringResource(R.string.hold_a_widget_on_the))
            }
            item {
                Section(stringResource(R.string.look_and_feel))
                Text(stringResource(R.string.screen_rotation), modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(stringResource(R.string.rotation_follow), stringResource(R.string.rotation_portrait), stringResource(R.string.rotation_landscape)).forEachIndexed { i, label ->
                        FilterChip(selected = s.orientation == i, onClick = { vm.setOrientation(i) }, label = { Text(label) },
                            modifier = Modifier.testTag("orientation_$i"))
                    }
                }
                Toggle(
                    stringResource(R.string.animations),
                    if (tier == Tier.LOW) stringResource(R.string.animations_default_low) else stringResource(R.string.animations_default_on),
                    animationsOn, "toggle_animations",
                ) { vm.setAnimations(it) }
                Text(stringResource(R.string.background), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
                Note(when { backgrounds.isEmpty() -> stringResource(R.string.bg_wallpaper); backgrounds.size == 1 -> stringResource(R.string.bg_pictures_one); else -> stringResource(R.string.bg_pictures_many, backgrounds.size) } +
                    if (backgrounds.isNotEmpty()) " " + stringResource(R.string.bg_tap_to_remove) else "")
                if (backgrounds.isNotEmpty()) LazyRow(
                    Modifier.padding(vertical = 6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(backgrounds, key = { it.name }) { f -> Thumb(f) { vm.backgrounds.remove(f) } }
                }
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.testTag("add_backgrounds"),
                    ) { Text(stringResource(R.string.add_pictures)) }
                    if (backgrounds.isNotEmpty()) TextButton(onClick = { vm.backgrounds.clear() }) { Text(stringResource(R.string.use_phone_wallpaper)) }
                }
                val pro = proUnlocked(vm)
                Toggle(
                    stringResource(R.string.slideshow),
                    when {
                        !pro -> stringResource(R.string.slideshow_pro)
                        backgrounds.size < 2 -> stringResource(R.string.slideshow_need_two)
                        else -> stringResource(R.string.slideshow_on)
                    },
                    s.slideshow && pro, "toggle_slideshow", enabled = backgrounds.size >= 2 && pro,
                ) { vm.setSlideshow(it) }
                if (!pro) ProUnlock(vm, stringResource(R.string.slideshows), Modifier.padding(horizontal = 20.dp))
                if (s.slideshow) androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 5, 15, 60).forEach { m ->
                        FilterChip(selected = s.slideshowMinutes == m, onClick = { vm.setSlideshowMinutes(m) },
                            label = { Text(if (m == 60) "1 hour" else "$m min") })
                    }
                }
            }
            item {
                Section(stringResource(R.string.web_search))
                Note(stringResource(R.string.searches_open_in_your_browser))
                SearchEngine.entries.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setEngine(e) }.padding(horizontal = 12.dp).testTag("engine_${e.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.engine == e, onClick = { vm.setEngine(e) })
                        Text(e.title)
                    }
                }
            }
            item {
                Section(stringResource(R.string.assistant))
                Toggle(stringResource(R.string.assistant), stringResource(R.string.ask_it_to_open_apps),
                    s.assistant, "toggle_assistant") { vm.setAssistant(it) }
                if (s.assistant) {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.open(Screen.MODELS) }.padding(horizontal = 20.dp, vertical = 12.dp).testTag("open_models"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.model))
                            Text(stringResource(R.string.choose_and_download_the_on), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(ModelCatalog.chatById(s.modelId)?.name ?: "None")
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.open(Screen.VOICE) }.padding(horizontal = 20.dp, vertical = 12.dp).testTag("open_voice"),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.voice))
                            Text(stringResource(R.string.spoken_replies_speech_engine_and), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(stringResource(s.speak.titleRes))
                    }
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val isAssistant = remember { Device.isDefaultAssistant(ctx) }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("power_button")) {
                        Text(stringResource(R.string.hold_the_power_button_for))
                        Text(if (isAssistant) stringResource(R.string.assistant_role_yes) else stringResource(R.string.assistant_role_no),
                            style = MaterialTheme.typography.bodySmall)
                        // Korvy holds the assistant role but Android still uses the older panel (chosen before 0.17):
                        // choosing it again switches to the full assistant service, which can read the screen.
                        val fullService = remember { android.service.voice.VoiceInteractionService.isActiveService(ctx,
                            android.content.ComponentName(ctx, app.korvy.launcher.assistant.AssistService::class.java)) }
                        if (isAssistant && !fullService && s.readScreen) {
                            Text(stringResource(R.string.read_screen_reselect), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                            OutlinedButton(onClick = { Device.openAssistantSettings(ctx) }, modifier = Modifier.padding(top = 4.dp)) {
                                Text(stringResource(R.string.choose_digital_assistant_app))
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.read_screen))
                                Text(stringResource(R.string.read_screen_detail), style = MaterialTheme.typography.bodySmall)
                            }
                            androidx.compose.material3.Switch(s.readScreen, { vm.setReadScreen(it) }, modifier = Modifier.testTag("toggle_read_screen"))
                        }
                        if (!isAssistant) OutlinedButton(onClick = { Device.openAssistantSettings(ctx) }, modifier = Modifier.padding(top = 6.dp)) {
                            Text(stringResource(R.string.choose_digital_assistant_app))
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { vm.open(Screen.NOTEBOOK) }.padding(horizontal = 20.dp, vertical = 12.dp).testTag("open_notebook")) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.notebook))
                            Text(stringResource(R.string.what_it_has_learned_your), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { vm.open(Screen.PEOPLE) }.padding(horizontal = 20.dp, vertical = 12.dp).testTag("open_people")) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.people_shortcuts))
                            Text(stringResource(R.string.jen_always_means_this_jennifer), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { vm.open(Screen.APPS) }.padding(horizontal = 20.dp, vertical = 12.dp).testTag("open_apps")) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.apps_the_assistant_uses))
                            Text(stringResource(R.string.which_app_texts_navigates_plays), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.open(Screen.PERSONALITY) }.padding(horizontal = 20.dp, vertical = 12.dp).testTag("open_personality"),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.personality))
                            Text(stringResource(R.string.edit_agent_md_to_change), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Section(stringResource(R.string.online_features))
                OnlineSection(
                    s, onMaster = vm::setOnline, onSource = vm::setSource, onHomePlace = vm::setHomePlace,
                    onFahrenheit = vm::setFahrenheit, onMobileData = vm::setModelsOnMobileData, onUseLocation = vm::setUseLocation,
                ) { title, detail, checked, tag, enabled, onChange -> Toggle(title, detail, checked, tag, enabled, onChange) }
            }
            item {
                Section(stringResource(R.string.privacy))
                Row(
                    Modifier.fillMaxWidth().clickable { vm.open(Screen.HIDDEN) }.padding(horizontal = 20.dp, vertical = 12.dp).testTag("open_hidden"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.hidden_apps))
                        Text(stringResource(R.string.long_press_any_app_to), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Toggle(stringResource(R.string.find_hidden_apps_by_full), stringResource(R.string.typing_a_hidden_app_s),
                    s.revealHiddenByName, "toggle_reveal") { vm.setRevealHiddenByName(it) }
                Toggle(stringResource(R.string.suggested_apps), stringResource(R.string.learns_which_apps_you_open),
                    s.suggestions, "toggle_suggestions") { vm.setSuggestions(it) }
                Toggle(stringResource(R.string.smart_search), stringResource(R.string.smart_search_detail),
                    s.smartSearch, "toggle_smart_search") { vm.setSmartSearch(it) }
                ContactsSetting(s, vm)
                Row(Modifier.padding(horizontal = 20.dp)) {
                    TextButton(onClick = { vm.forgetHistory() }, modifier = Modifier.testTag("forget_history")) { Text(stringResource(R.string.forget_app_history)) }
                }
            }
            item {
                Section(stringResource(R.string.device))
                Row(
                    Modifier.fillMaxWidth().clickable { vm.open(Screen.STATS) }.padding(horizontal = 20.dp, vertical = 12.dp).testTag("open_stats"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.device_stats))
                        Text(stringResource(R.string.cpu_memory_frame_rate_battery), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Section(stringResource(R.string.help))
                Row(Modifier.padding(horizontal = 20.dp)) {
                    TextButton(onClick = { vm.open(Screen.TUTORIAL) }, modifier = Modifier.testTag("show_tutorial")) { Text(stringResource(R.string.show_the_tour_again)) }
                }
                Section(stringResource(R.string.about))
                Row(Modifier.padding(horizontal = 20.dp)) {
                    TextButton(onClick = { vm.open(Screen.ABOUT) }, modifier = Modifier.testTag("open_about")) { Text(stringResource(R.string.about_korvy)) }
                    // Only after tapping the version in About 7 times.
                    if (s.developer) TextButton(onClick = { vm.open(Screen.DEVELOPER) }, modifier = Modifier.testTag("open_developer")) { Text(stringResource(R.string.developer_options)) }
                }
                Note(if (s.online) stringResource(R.string.about_note_online, BuildConfig.VERSION_NAME) else stringResource(R.string.about_note_offline, BuildConfig.VERSION_NAME))
                Device.specs(LocalContext.current).let { Note(stringResource(R.string.phone_class, tier.name.lowercase(), it.totalRamMb / 1024.0, it.cores, it.sdk)) }
            }
        }
    }
}

@Composable
private fun Thumb(f: File, onRemove: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, f) {
        value = withContext(Dispatchers.IO) { Backgrounds.load(f)?.let { android.graphics.Bitmap.createScaledBitmap(it, it.width / 8, it.height / 8, true).asImageBitmap() } }
    }
    var ask by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(
        Modifier.size(width = 64.dp, height = 112.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { ask = true }.testTag("bg_thumb"),
    ) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        // A dark round badge so the ✕ shows on any photo (light or dark).
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(36.dp).testTag("bg_remove")) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, stringResource(R.string.remove_picture), tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
    }
    if (ask) androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text(stringResource(R.string.remove_picture_q)) },
        confirmButton = { TextButton(onClick = { ask = false; onRemove() }) { Text(stringResource(R.string.remove)) } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}

/** Settings → Privacy → Contacts: allow access (optional), and whether contacts show up in search. */
@Composable
private fun ContactsSetting(s: Settings, vm: LauncherViewModel) {
    val granted by vm.contactsGranted.collectAsStateWithLifecycle()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshContactsGranted() }
    if (!granted) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.contacts))
        Text(stringResource(R.string.optional_lets_search_find_people), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { ask.launch(android.Manifest.permission.READ_CONTACTS) }, modifier = Modifier.padding(top = 6.dp).testTag("allow_contacts")) {
            Text(stringResource(R.string.allow_contacts))
        }
    } else Toggle(stringResource(R.string.contacts_in_search), stringResource(R.string.show_matching_people_when_you),
        s.contactsInSearch, "toggle_contacts_search") { vm.setContactsInSearch(it) }
}
