package app.korvy.launcher.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.korvy.launcher.platform.StatsOverlayService

private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

/** True when Pro is unlocked (always in the F-Droid build and sideloaded copies). */
@Composable
fun proUnlocked(vm: LauncherViewModel): Boolean = vm.pro.unlocked.collectAsStateWithLifecycle().value

/** "This is part of Pro" with a buy button; shown in place of a Pro feature's controls. */
@Composable
fun ProUnlock(vm: LauncherViewModel, what: String, modifier: Modifier = Modifier) {
    val price by vm.pro.price.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.pro.refresh() }
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("pro_unlock"), verticalAlignment = Alignment.CenterVertically) {
        Text("$what are part of Pro, a one-time unlock that supports development.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = { context.activity()?.let { vm.pro.buy(it) } }, enabled = vm.pro.canBuy, modifier = Modifier.padding(start = 8.dp)) {
            Text(price?.let { "Unlock · $it" } ?: "Unlock Pro")
        }
    }
}

/** Device stats → "Show over other apps" (Pro). Asks for "Display over other apps" the first time. */
@Composable
fun StatsOverlayToggle(vm: LauncherViewModel) {
    val context = LocalContext.current
    if (!proUnlocked(vm)) { ProUnlock(vm, "Stats overlays"); return }
    var on by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(StatsOverlayService.running(context)) }
    var asked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Coming back from Android's permission screen: turn it on if allowed.
    androidx.lifecycle.compose.LifecycleResumeEffect(asked) {
        if (asked && StatsOverlayService.canShow(context)) {
            asked = false; StatsOverlayService.start(context); on = true
        }
        onPauseOrDispose {}
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("overlay_toggle"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Show over other apps")
            Text("A small box with core speeds, memory, battery drain and temperature. Drag it anywhere.", style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Switch(on, { want ->
            val ov = StatsOverlayService
            when {
                !want -> { ov.stop(context); on = false }
                ov.canShow(context) -> { ov.start(context); on = true }
                else -> {
                    asked = true
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + context.packageName)))
                }
            }
        })
    }
}

/** Settings → Pro: status, buy and restore. */
@Composable
fun ProSection(vm: LauncherViewModel) {
    val unlocked = proUnlocked(vm)
    val s by vm.settings.collectAsStateWithLifecycle()
    val wake = app.korvy.launcher.core.Feature.WAKE_WORD.on(s.flagsOn, s.flagsOff)
    val price by vm.pro.price.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.pro.refresh() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("pro_section")) {
        if (unlocked) {
            Text("Pro is unlocked. Thank you!", style = MaterialTheme.typography.bodyMedium)
            Text("Natural voices, Whisper speech recognition, " + (if (wake) "the wake word, " else "") + "the slideshow and the stats overlay.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Pro: one-time ${price ?: "$4.99"}", style = MaterialTheme.typography.bodyMedium)
            Text("Adds natural voices, Whisper speech recognition, " + (if (wake) "the wake word, " else "") + "the background slideshow and the stats overlay. " +
                "Everything else stays free, with no ads and no tracking. Buying is handled by Google Play; this app never sees your payment details.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                FilledTonalButton(onClick = { context.activity()?.let { vm.pro.buy(it) } }, enabled = vm.pro.canBuy, modifier = Modifier.testTag("pro_buy")) {
                    Text(price?.let { "Unlock · $it" } ?: "Unlock Pro")
                }
                TextButton(onClick = { vm.pro.refresh() }) { Text("Restore purchase") }
            }
        }
    }
}
