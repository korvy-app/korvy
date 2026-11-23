package com.hiddenoutlier.launcher.ui

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
import com.hiddenoutlier.launcher.data.Settings
import com.hiddenoutlier.launcher.platform.AppChoices
import com.hiddenoutlier.launcher.platform.AppTask

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
