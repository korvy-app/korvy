package app.korvy.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.korvy.launcher.data.AppEntry
import app.korvy.launcher.data.AppRepository
import app.korvy.launcher.data.HideLevel

/**
 * Hidden apps (level 1) are listed directly. Locked apps (level 2) stay out of sight until the user passes the
 * fingerprint / face / PIN prompt, and lock again on leaving this screen.
 */
@Composable
fun HiddenAppsScreen(
    hidden: List<AppEntry>,
    locked: List<AppEntry>,
    lockedOpen: Boolean,
    repo: AppRepository,
    onUnlockList: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onUnhide: (AppEntry) -> Unit,
    onLock: (AppEntry) -> Unit,
    onUnlockApp: (AppEntry) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Hidden apps", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Hidden apps are left out of this launcher's home screen, app list, search and suggestions. " +
                "They still appear in Android's Settings → Apps and in other launchers.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.fillMaxSize().testTag("hidden_list")) {
            item { Header("Hidden") }
            if (hidden.isEmpty()) item { Empty("No hidden apps. Long-press an app and choose Hide.") }
            items(hidden, key = { "h-" + it.key }) { app ->
                AppRow(app, repo, onLaunch) {
                    TextButton(onClick = { onLock(app) }) { Text("Lock") }
                    TextButton(onClick = { onUnhide(app) }, modifier = Modifier.testTag("unhide_${app.label}")) { Text("Unhide") }
                }
            }
            item { Header("Locked") }
            if (!lockedOpen) item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, null)
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onUnlockList, modifier = Modifier.testTag("unlock_locked")) { Text("Unlock to see locked apps") }
                }
            } else {
                if (locked.isEmpty()) item { Empty("No locked apps. Long-press an app and choose Hide and lock.") }
                items(locked, key = { "l-" + it.key }) { app ->
                    AppRow(app, repo, onLaunch) {
                        TextButton(onClick = { onUnlockApp(app) }) { Text("Don't lock") }
                        TextButton(onClick = { onUnhide(app) }) { Text("Unhide") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))

@Composable
private fun Empty(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

@Composable
private fun AppRow(app: AppEntry, repo: AppRepository, onLaunch: (AppEntry) -> Unit, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onLaunch(app) }.padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app, repo, 40.dp)
        Spacer(Modifier.width(14.dp))
        Text(app.label, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Lists for the screen, split by level. */
fun splitHidden(apps: List<AppEntry>, hidden: Map<String, HideLevel>) =
    apps.filter { hidden[it.key] == HideLevel.HIDDEN } to apps.filter { hidden[it.key] == HideLevel.LOCKED }
