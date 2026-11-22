package com.hiddenoutlier.launcher.ui

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
import com.hiddenoutlier.launcher.BuildConfig
import com.hiddenoutlier.launcher.core.Tier
import com.hiddenoutlier.launcher.data.Backgrounds
import com.hiddenoutlier.launcher.data.SearchEngine
import com.hiddenoutlier.launcher.data.Settings
import com.hiddenoutlier.launcher.platform.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(
    s: Settings,
    animationsOn: Boolean,
    tier: Tier,
    backgrounds: List<File>,
    hiddenCount: Int,
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
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.fillMaxSize().testTag("settings"), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                Section("Home screen")
                if (isDefaultHome) Note("This is your home screen app.")
                else Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Button(onClick = onSetDefaultHome, modifier = Modifier.testTag("set_default_home")) { Text("Make this my home screen") }
                }
            }
            item {
                Section("Look and feel")
                Toggle(
                    "Animations",
                    "Default for this phone: ${if (tier == Tier.LOW) "off (keeps it fast)" else "on"}.",
                    animationsOn, "toggle_animations",
                ) { vm.setAnimations(it) }
                Text("Background", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
                Note(if (backgrounds.isEmpty()) "Using your phone's wallpaper." else "${backgrounds.size} picture${if (backgrounds.size == 1) "" else "s"}. Photos are copied in, resized for the screen, and stay on this phone.")
                if (backgrounds.isNotEmpty()) LazyRow(
                    Modifier.padding(vertical = 6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(backgrounds, key = { it.name }) { f -> Thumb(f) { vm.backgrounds.remove(f) } }
                }
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.testTag("add_backgrounds"),
                    ) { Text("Add pictures") }
                    if (backgrounds.isNotEmpty()) TextButton(onClick = { vm.backgrounds.clear() }) { Text("Use phone wallpaper") }
                }
                Toggle(
                    "Slideshow",
                    if (backgrounds.size < 2) "Add two or more pictures to rotate them." else "Crossfades between your pictures. Pauses in battery saver.",
                    s.slideshow, "toggle_slideshow", enabled = backgrounds.size >= 2,
                ) { vm.setSlideshow(it) }
                if (s.slideshow) Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 5, 15, 60).forEach { m ->
                        FilterChip(selected = s.slideshowMinutes == m, onClick = { vm.setSlideshowMinutes(m) },
                            label = { Text(if (m == 60) "1 hour" else "$m min") })
                    }
                }
            }
            item {
                Section("Web search")
                Note("Searches open in your browser. This app has no internet access of its own.")
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
                Section("Privacy")
                Row(
                    Modifier.fillMaxWidth().clickable { vm.open(Screen.HIDDEN) }.padding(horizontal = 20.dp, vertical = 12.dp).testTag("open_hidden"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Hidden apps")
                        Text("Long-press any app to hide or lock it.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (hiddenCount == 0) "None" else "$hiddenCount", style = MaterialTheme.typography.bodyMedium)
                }
                Toggle("Find hidden apps by full name", "Typing a hidden app's whole name shows it in search. Locked apps still ask for your fingerprint or PIN.",
                    s.revealHiddenByName, "toggle_reveal") { vm.setRevealHiddenByName(it) }
                Toggle("Suggested apps", "Learns which apps you open at which times, on this phone only. Hidden apps are never recorded.",
                    s.suggestions, "toggle_suggestions") { vm.setSuggestions(it) }
                Row(Modifier.padding(horizontal = 20.dp)) {
                    TextButton(onClick = { vm.forgetHistory() }, modifier = Modifier.testTag("forget_history")) { Text("Forget app history") }
                }
            }
            item {
                Section("Device")
                Row(
                    Modifier.fillMaxWidth().clickable { vm.open(Screen.STATS) }.padding(horizontal = 20.dp, vertical = 12.dp).testTag("open_stats"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Device stats")
                        Text("CPU, memory, frame rate, battery drain and heat, live.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Section("Help")
                Row(Modifier.padding(horizontal = 20.dp)) {
                    TextButton(onClick = { vm.open(Screen.TUTORIAL) }, modifier = Modifier.testTag("show_tutorial")) { Text("Show the tour again") }
                }
                Section("About")
                Note("Version ${BuildConfig.VERSION_NAME}. No accounts, no analytics, no crash reporting, no internet permission. Everything stays on this phone.")
                Note("Phone class: ${tier.name.lowercase()} (${Device.specs(LocalContext.current).let { "%.1f GB, %d cores, Android API %d".format(it.totalRamMb / 1024.0, it.cores, it.sdk) }}).")
            }
        }
    }
}

@Composable
private fun Thumb(f: File, onRemove: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, f) {
        value = withContext(Dispatchers.IO) { Backgrounds.load(f)?.let { android.graphics.Bitmap.createScaledBitmap(it, it.width / 8, it.height / 8, true).asImageBitmap() } }
    }
    androidx.compose.foundation.layout.Box(Modifier.size(width = 64.dp, height = 112.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
            Icon(Icons.Outlined.Close, "Remove picture", modifier = Modifier.size(16.dp))
        }
    }
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
