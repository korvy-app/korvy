package com.hiddenoutlier.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hiddenoutlier.launcher.platform.StatsSampler
import com.hiddenoutlier.launcher.platform.StatsSnapshot
import kotlinx.coroutines.delay

/** Live device and app stats, updated every second while this screen is open. */
@Composable
fun StatsScreen(sampler: StatsSampler, onBack: () -> Unit) {
    var snap by remember { mutableStateOf<StatsSnapshot?>(null) }
    var stress by remember { mutableStateOf(false) }
    DisposableEffect(sampler) { sampler.start(); onDispose { sampler.stop() } }
    LaunchedEffect(sampler) { while (true) { delay(1000); snap = sampler.sample() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().testTag("stats")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Device stats", style = MaterialTheme.typography.titleLarge)
        }
        val s = snap
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (s == null) { Text("Measuring…", modifier = Modifier.padding(16.dp)); return@Column }

            Card("This app") {
                Stat("CPU", "%.1f%% of the phone".format(s.appCpuPercent))
                Stat("Memory", "%.0f MB".format(s.appMemoryMb))
                Stat("Frames drawn", "%.0f fps (screen runs at %.0f Hz)".format(s.fps, s.refreshHz))
                Stat("Dropped frames", s.slowFramePercent?.let { "%.0f%%".format(it) } ?: "needs motion to measure")
                Text(
                    "A still screen draws nothing, which saves battery, so fps is near zero when idle. " +
                        "Turn on the smoothness test to measure animation.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Smoothness test", modifier = Modifier.weight(1f))
                    Switch(stress, { stress = it }, modifier = Modifier.testTag("stats_stress"))
                }
                if (stress) Spinner()
            }

            Card("Memory (whole phone)") {
                val used = s.ramTotalMb - s.ramAvailableMb
                Stat("Used", "%.1f of %.1f GB".format(used / 1024.0, s.ramTotalMb / 1024.0))
                LinearProgressIndicator(progress = { used.toFloat() / s.ramTotalMb }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                if (s.lowMemory) Text("Android reports low memory: apps may be closed in the background.", color = MaterialTheme.colorScheme.error)
            }

            Card("CPU (${s.cores} cores)") {
                s.coreMhz.forEachIndexed { i, mhz -> Stat("Core $i", mhz?.let { "$it MHz" } ?: "asleep or not readable") }
            }

            Card("Battery") {
                Stat("Level", if (s.batteryPercent >= 0) "${s.batteryPercent}%" + if (s.charging) " (charging)" else "" else "unknown")
                Stat(if (s.charging) "Charging current" else "Drain", s.currentMa?.let { "$it mA" } ?: "not reported")
                Stat("Power", s.powerMw?.let { "%.0f mW".format(it) } ?: "not reported")
                Stat("Voltage", s.voltage?.let { "%.2f V".format(it) } ?: "not reported")
                Stat("Temperature", s.batteryTempC?.let { "%.1f °C".format(it) } ?: "not reported")
            }

            Card("Heat") {
                Stat("Thermal state", s.thermal)
                s.thermalHeadroom?.let {
                    Stat("Headroom", "%.0f%% of the way to slowing down".format(it * 100))
                    LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
            }
            Text(
                "Measured on this phone only. Nothing is recorded or sent anywhere.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A continuously moving shape, so the frame counter has something to measure. */
@Composable
private fun Spinner() {
    val t = rememberInfiniteTransition(label = "stress")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse), label = "x")
    Box(Modifier.fillMaxWidth().height(40.dp)) {
        Box(Modifier.size(32.dp).graphicsLayer { translationX = x * (size.width * 8) }.clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}
