package app.korvy.launcher.ui

import app.korvy.launcher.platform.exampleRes
import app.korvy.launcher.platform.titleRes
import app.korvy.launcher.R
import androidx.compose.ui.res.stringResource
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
        Header(stringResource(R.string.people_shortcuts), onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Text(stringResource(R.string.names_and_nicknames_the_assistant), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = alias, onValueChange = { alias = it }, singleLine = true, label = { Text(stringResource(R.string.name_or_nickname)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("shortcut_alias"))
                Button(onClick = { picker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) },
                    enabled = alias.isNotBlank(), modifier = Modifier.padding(top = 6.dp)) { Text(stringResource(R.string.choose_who)) }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                if (shortcuts.isEmpty()) Text(stringResource(R.string.no_shortcuts_yet), style = MaterialTheme.typography.bodyMedium)
            }
            items(shortcuts, key = { it.alias }) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ContactAvatar(s.person, 40.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(stringResource(R.string.text_3, s.alias, s.person.name))
                        Text(listOf(s.person.label, s.person.number).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.assistant.shortcuts.remove(s.alias) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.remove_alias, s.alias)) }
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
        Header(stringResource(R.string.apps_the_assistant_uses), onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item { Text(stringResource(R.string.pick_which_app_handles_each),
                style = MaterialTheme.typography.bodySmall) }
            AppTask.entries.forEach { task ->
                item(key = task.name) {
                    val opts = options[task].orEmpty()
                    val chosen = AppChoices.chosen(context, s.appChoices, task)
                    Text(stringResource(task.titleRes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp))
                    task.exampleRes?.let { ex -> Text(stringResource(R.string.text, stringResource(ex)), style = MaterialTheme.typography.bodySmall) }
                    if (opts.isEmpty()) Text(stringResource(R.string.no_installed_app_can_do), style = MaterialTheme.typography.bodySmall)
                    else {
                        Row(Modifier.fillMaxWidth().clickable { vm.setAppChoice(task, null) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = chosen == null, onClick = { vm.setAppChoice(task, null) }); Text(stringResource(R.string.android_decides))
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
            Text(stringResource(R.string.notebook), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { text = store.saveMarkdown(text).tidy().also { store.save(it) }.toMarkdown() }) { Text(stringResource(R.string.tidy)) }
            androidx.compose.material3.TextButton(onClick = { store.saveMarkdown(text); onBack() }, enabled = size <= max,
                modifier = Modifier.testTag("notebook_save")) { Text(stringResource(R.string.save)) }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            item {
                Text(stringResource(R.string.what_the_assistant_has_learned),
                    style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.LinearProgressIndicator(progress = { (size.toFloat() / max).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    color = if (size > max * 0.9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.of_characters_notes_phrases, size, max, book.notes.size, book.phrases.size), style = MaterialTheme.typography.bodySmall)
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
        Header(stringResource(R.string.about), onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Text(stringResource(R.string.korvy_launcher), style = MaterialTheme.typography.headlineSmall)
                // Tap the version 7 times for Developer options (experimental features behind flags).
                Text(stringResource(R.string.version, BuildConfig.VERSION_NAME) +
                    if (BuildConfig.FLAVOR == "foss") " · F-Droid build" else "", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("about_version").clickable {
                        if (!s.developer && ++taps >= 7) {
                            vm.setDeveloper(true)
                            android.widget.Toast.makeText(context, context.getString(R.string.developer_on), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    })
                Text(stringResource(R.string.a_private_fast_home_screen),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                Text(stringResource(R.string.your_privacy), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
                Text(stringResource(R.string.no_accounts_ads_analytics_or), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.open_source), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
                Text(stringResource(R.string.free_software_under_the_gnu), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.TextButton(onClick = { open("https://korvy.app") }) { Text(stringResource(R.string.korvy_app)) }
                androidx.compose.material3.TextButton(onClick = { open("https://github.com/korvy-app/korvy") }) { Text(stringResource(R.string.source_code_on_github)) }
                androidx.compose.material3.TextButton(onClick = { open("https://github.com/korvy-app/korvy/issues") }) { Text(stringResource(R.string.report_a_problem)) }
            }
            if (s.developer) item {
                androidx.compose.material3.TextButton(onClick = { vm.open(Screen.DEVELOPER) }, modifier = Modifier.testTag("open_developer")) { Text(stringResource(R.string.developer_options)) }
            }
        }
    }
}


/** Developer options (hidden until the version in About is tapped 7 times): experimental features behind flags. */
@Composable
fun DeveloperScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().testTag("developer")) {
        Header(stringResource(R.string.developer_options), onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Text(stringResource(R.string.features_still_being_built_they), style = MaterialTheme.typography.bodySmall)
            }
            // Features whose code isn't in this build (e.g. the wake word in the Play build) aren't offered.
            items(Feature.entries.filter { it != Feature.WAKE_WORD || app.korvy.launcher.assistant.WakeWordService.available(context) }) { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("flag_${f.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.title)
                        Text(f.note, style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.Switch(f.on(s.flagsOn, s.flagsOff), { vm.setFlag(f, it) })
                }
            }
            item {
                // Debug builds only: in a released app anyone who unlocks Developer options could restart the trial forever.
                if (app.korvy.launcher.BuildConfig.DEBUG) androidx.compose.material3.TextButton(onClick = { vm.pro.resetTrial() }) { Text(stringResource(R.string.reset_pro_trial)) }
                androidx.compose.material3.TextButton(onClick = {
                    Feature.entries.forEach { vm.setFlag(it, it.default) }
                    vm.setDeveloper(false); onBack()
                }, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.reset_and_hide_developer_options)) }
            }
        }
    }
}
