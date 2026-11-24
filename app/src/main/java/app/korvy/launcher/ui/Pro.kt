package app.korvy.launcher.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import app.korvy.launcher.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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

/** "This is part of Pro": try free for 7 days (once) or unlock. Shown in place of a Pro feature's controls. */
@Composable
fun ProUnlock(vm: LauncherViewModel, what: String, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { vm.pro.refresh() }
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("pro_unlock")) {
        Text(stringResource(R.string.are_part_of_pro_a, what), style = MaterialTheme.typography.bodySmall)
        ProButtons(vm, Modifier.padding(top = 6.dp))
    }
}

/** The two Pro buttons: the free trial (if not used yet) and the purchase. */
@Composable
fun ProButtons(vm: LauncherViewModel, modifier: Modifier = Modifier) = ProButtons(vm.pro, modifier)

@Composable
fun ProButtons(pro: app.korvy.launcher.platform.ProAccess, modifier: Modifier = Modifier) {
    val price by pro.price.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pro.canStartTrial()) FilledTonalButton(onClick = { pro.startTrial() }, modifier = Modifier.testTag("pro_trial")) {
            Text(stringResource(R.string.pro_try_free))
        }
        androidx.compose.material3.OutlinedButton(onClick = { context.activity()?.let { pro.buy(it) } }, enabled = pro.canBuy) {
            Text(price?.let { stringResource(R.string.pro_unlock_price, it) } ?: stringResource(R.string.pro_unlock))
        }
    }
}

/** A whole screen that's part of Pro (AI models, notebook, people, personality), shown when Pro isn't unlocked. */
@Composable
fun ProLockedScreen(vm: LauncherViewModel, title: String, what: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().testTag("pro_locked")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.pro_locked_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.pro_locked_body, what), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.pro_list), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            ProButtons(vm, Modifier.padding(top = 16.dp))
        }
    }
}

/** Asked from an action (e.g. Hide and lock): the same choice as a dialog. */
@Composable
fun ProDialog(vm: LauncherViewModel) {
    val what by vm.proPrompt.collectAsStateWithLifecycle()
    val unlocked = proUnlocked(vm)
    val w = what ?: return
    if (unlocked) { vm.proPrompt.value = null; return }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.proPrompt.value = null },
        title = { Text(stringResource(R.string.pro_locked_title)) },
        text = { Column { Text(stringResource(R.string.pro_locked_body, w)); ProButtons(vm, Modifier.padding(top = 14.dp)) } },
        confirmButton = {}, dismissButton = { androidx.compose.material3.TextButton(onClick = { vm.proPrompt.value = null }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Device stats → "Show over other apps" (Pro). Asks for "Display over other apps" the first time. */
@Composable
fun StatsOverlayToggle(vm: LauncherViewModel) {
    val context = LocalContext.current
    if (!proUnlocked(vm)) { ProUnlock(vm, stringResource(R.string.stats_overlays)); return }
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
            Text(stringResource(R.string.show_over_other_apps))
            Text(stringResource(R.string.a_small_box_with_core), style = MaterialTheme.typography.bodySmall)
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

/** Settings → Pro: status (bought, trial days left), what it adds, try/buy and restore. */
@Composable
fun ProSection(vm: LauncherViewModel) {
    val unlocked = proUnlocked(vm)
    val bought by vm.pro.purchased.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.pro.refresh() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("pro_section")) {
        when {
            bought -> Text(stringResource(R.string.pro_unlocked_thanks), style = MaterialTheme.typography.bodyMedium)
            unlocked -> {
                val days = vm.pro.trialDaysLeft()
                Text(if (days <= 1) stringResource(R.string.pro_trial_last_day) else stringResource(R.string.pro_trial_days_left, days),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(stringResource(R.string.pro_list), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (!bought) {
            ProButtons(vm, Modifier.padding(top = 6.dp))
            TextButton(onClick = { vm.pro.refresh() }) { Text(stringResource(R.string.restore_purchase)) }
        }
    }
}
