package app.korvy.launcher.ui

import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.korvy.launcher.data.Settings
import app.korvy.launcher.platform.AppChoices
import app.korvy.launcher.platform.AppTask
import app.korvy.launcher.BuildConfig
import app.korvy.launcher.core.Feature
import app.korvy.launcher.core.Notebook

@Composable
private fun Header(title: String, onBack: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
    Text(title, style = MaterialTheme.typography.titleLarge)
}

/** Settings → Assistant → People shortcuts: "Jen" → this Jennifer, "wife" → … */
@Composable
fun PeopleScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val shortcuts by vm.assistant.shortcuts.all.collectAsStateWithLifecycle()
    var alias by remember { mutableStateOf("") }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        vm.assistant.contacts.fromPicked(uri)?.let { p -> vm.assistant.shortcuts.set(alias, p); alias = "" }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().imePadding().testTag("people")) {
        Header("People shortcuts", onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Text("Names and nicknames the assistant should always understand the same way, like “Jen” for one particular " +
                    "Jennifer or “wife”. Kept encrypted on this phone only; the AI model never sees them. You can also tick " +
                    "“Always use this person” when the assistant asks which one you mean.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = alias, onValueChange = { alias = it }, singleLine = true, label = { Text("Name or nickname") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("shortcut_alias"))
                Button(onClick = { picker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) },
                    enabled = alias.isNotBlank(), modifier = Modifier.padding(top = 6.dp)) { Text("Choose who") }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                if (shortcuts.isEmpty()) Text("No shortcuts yet.", style = MaterialTheme.typography.bodyMedium)
            }
            items(shortcuts, key = { it.alias }) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ContactAvatar(s.person, 40.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("“${s.alias}” → ${s.person.name}")
                        Text(listOf(s.person.label, s.person.number).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.assistant.shortcuts.remove(s.alias) }) { Icon(Icons.Outlined.Delete, "Remove ${s.alias}") }
                }
            }
        }
    }
}

/** Settings → Assistant → Apps: which app does each task. */
@Composable
fun AppsForTasksScreen(s: Settings, vm: LauncherViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val options = remember { AppTask.entries.associateWith { AppChoices.options(context, it) } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().testTag("apps_for_tasks")) {
        Header("Apps the assistant uses", onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item { Text("Pick which app handles each kind of request. “Android decides” uses your phone's default (or asks).",
                style = MaterialTheme.typography.bodySmall) }
            AppTask.entries.forEach { task ->
                item(key = task.name) {
                    val opts = options[task].orEmpty()
                    val chosen = AppChoices.chosen(context, s.appChoices, task)
                    Text(task.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp))
                    if (task.example.isNotBlank()) Text("“${task.example}”", style = MaterialTheme.typography.bodySmall)
                    if (opts.isEmpty()) Text("No installed app can do this.", style = MaterialTheme.typography.bodySmall)
                    else {
                        Row(Modifier.fillMaxWidth().clickable { vm.setAppChoice(task, null) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = chosen == null, onClick = { vm.setAppChoice(task, null) }); Text("Android decides")
                        }
                        opts.forEach { o ->
                            Row(Modifier.fillMaxWidth().clickable { vm.setAppChoice(task, o.pkg) }.testTag("app_${task.name}_${o.pkg}"), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = chosen == o.pkg, onClick = { vm.setAppChoice(task, o.pkg) }); Text(o.label)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Settings → Assistant → Notebook: what the assistant has learned (notes and phrases), editable as Markdown. */
@Composable
fun NotebookScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val store = vm.assistant.notebook
    val book by store.book.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(book.toMarkdown()) }
    val size = text.length
    val max = Notebook.MAX_CHARS
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().imePadding().testTag("notebook")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Notebook", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { text = store.saveMarkdown(text).tidy().also { store.save(it) }.toMarkdown() }) { Text("Tidy") }
            androidx.compose.material3.TextButton(onClick = { store.saveMarkdown(text); onBack() }, enabled = size <= max,
                modifier = Modifier.testTag("notebook_save")) { Text("Save") }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            item {
                Text("What the assistant has learned. Teach it by saying “remember …”, “when I say goodnight, set a timer for 8 hours " +
                    "and play rain sounds”, or “forget …”. Phrases run instantly without a model. The model only sees the few notes " +
                    "that matter for each question. Kept encrypted on this phone, never backed up. People nicknames live in People shortcuts.",
                    style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.LinearProgressIndicator(progress = { (size.toFloat() / max).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    color = if (size > max * 0.9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text("$size of $max characters · ${book.notes.size} notes · ${book.phrases.size} phrases", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 320.dp).testTag("notebook_text"),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
            }
        }
    }
}

/** Settings → About Korvy: name, version, license, links and the privacy promise in plain words. */
@Composable
fun AboutScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    var taps by remember { mutableStateOf(0) }
    fun open(url: String) = runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().testTag("about")) {
        Header("About", onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Text("Korvy Launcher™", style = MaterialTheme.typography.headlineSmall)
                // Tap the version 7 times for Developer options (experimental features behind flags).
                Text("Version ${BuildConfig.VERSION_NAME}" +
                    if (BuildConfig.FLAVOR == "foss") " · F-Droid build" else "", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("about_version").clickable {
                        if (!s.developer && ++taps >= 7) {
                            vm.setDeveloper(true)
                            android.widget.Toast.makeText(context, "Developer options are on", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    })
                Text("A private, fast home screen with an optional assistant that runs on your phone.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                Text("Your privacy", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
                Text("No accounts, ads, analytics or crash reporting, and no server of ours: there is nothing for this app to send " +
                    "your data to. Online features (weather, news, downloads…) are off until you turn each one on, and they talk " +
                    "only to the services named in Settings → Online features.", style = MaterialTheme.typography.bodyMedium)
                Text("Open source", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
                Text("Free software under the GNU GPL version 3 or later. Built with llama.cpp, whisper.cpp and sherpa-onnx (MIT/Apache 2.0). " +
                    "“Korvy” is a trademark of the project; forks are welcome under another name.", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.TextButton(onClick = { open("https://korvy.app") }) { Text("korvy.app") }
                androidx.compose.material3.TextButton(onClick = { open("https://github.com/korvy-app/korvy") }) { Text("Source code on GitHub") }
                androidx.compose.material3.TextButton(onClick = { open("https://github.com/korvy-app/korvy/issues") }) { Text("Report a problem") }
            }
            if (s.developer) item {
                androidx.compose.material3.TextButton(onClick = { vm.open(Screen.DEVELOPER) }, modifier = Modifier.testTag("open_developer")) { Text("Developer options") }
            }
        }
    }
}


/** Developer options (hidden until the version in About is tapped 7 times): experimental features behind flags. */
@Composable
fun DeveloperScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().testTag("developer")) {
        Header("Developer options", onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Text("Features still being built. They may not work, and can change or disappear. Turning one on only " +
                    "affects this phone.", style = MaterialTheme.typography.bodySmall)
            }
            items(Feature.entries) { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("flag_${f.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.title)
                        Text(f.note, style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.Switch(f.on(s.flagsOn, s.flagsOff), { vm.setFlag(f, it) })
                }
            }
            item {
                androidx.compose.material3.TextButton(onClick = {
                    Feature.entries.forEach { vm.setFlag(it, it.default) }
                    vm.setDeveloper(false); onBack()
                }, modifier = Modifier.padding(top = 12.dp)) { Text("Reset and hide developer options") }
            }
        }
    }
}
