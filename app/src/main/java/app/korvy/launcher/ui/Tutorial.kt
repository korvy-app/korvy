package app.korvy.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwipeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class Page(val icon: ImageVector, val title: String, val body: String)

private val pages = listOf(
    Page(Icons.Outlined.Shield, "Welcome to Korvy",
        "A private home screen: no accounts, no ads, no analytics, and no server of ours. Online extras like the " +
            "weather stay off until you turn them on."),
    Page(Icons.Outlined.SwipeUp, "All your apps",
        "Swipe up anywhere on the home screen to see every app. Press Home to come back."),
    Page(Icons.Outlined.Search, "Search everything",
        "Tap the search bar and type a few letters: \"gm\" finds Google Maps. Press Go to open the first app, " +
            "or search the web with DuckDuckGo or another engine you choose in Settings."),
    Page(Icons.Outlined.Lock, "Hide and lock apps",
        "Long-press any app and choose Hide, or Hide and lock to protect it with your fingerprint or PIN. " +
            "Find them again in Settings → Hidden apps."),
    Page(Icons.Outlined.Schedule, "Apps for right now",
        "The home screen learns which apps you open at different times and days, and suggests them. " +
            "It's all worked out on this phone. Turn it off or forget the history in Settings."),
    Page(Icons.Outlined.AutoAwesome, "Korvy, your assistant",
        "Optional, and it runs on this phone. Tap ✦ or hold the power button: \"call Jen\", \"set an alarm for 7\", " +
            "\"when I say goodnight, play rain sounds\". It always asks before texting, calling or booking."),
    Page(Icons.Outlined.Home, "Make Korvy your home screen",
        "Set it as your home app so the Home button opens it. Long-press the home screen any time for Settings."),
)

/** First-run tour (also in Settings → Help). The last page offers to set this as the default home app. */
@Composable
fun Tutorial(isDefaultHome: Boolean, onSetDefaultHome: () -> Unit, onDone: () -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val animations = LocalAnimations.current
    Column(
        Modifier.fillMaxSize().background(Color(0xF0101418)).statusBarsPadding().navigationBarsPadding().testTag("tutorial"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone, modifier = Modifier.testTag("tutorial_skip")) { Text("Skip") }
        }
        HorizontalPager(pager, modifier = Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(p.icon, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(28.dp))
                Text(p.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(p.body, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                if (i == pages.lastIndex) {
                    Spacer(Modifier.height(24.dp))
                    if (isDefaultHome) Text("✓ It's already your home screen.", color = MaterialTheme.colorScheme.primary)
                    else OutlinedButton(onClick = onSetDefaultHome, modifier = Modifier.testTag("tutorial_set_home")) { Text("Make this my home screen") }
                }
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(pages.size) { i ->
                Box(Modifier.size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == pager.currentPage) 1f else 0.35f)))
            }
        }
        Button(
            onClick = {
                if (pager.currentPage == pages.lastIndex) onDone()
                else scope.launch { if (animations) pager.animateScrollToPage(pager.currentPage + 1) else pager.scrollToPage(pager.currentPage + 1) }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp).height(52.dp).testTag("tutorial_next"),
        ) { Text(if (pager.currentPage == pages.lastIndex) "Start" else "Next") }
    }
}
