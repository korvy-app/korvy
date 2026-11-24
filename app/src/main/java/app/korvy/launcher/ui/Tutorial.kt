package app.korvy.launcher.ui

import app.korvy.launcher.R
import androidx.compose.ui.res.stringResource
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

private data class Page(val icon: ImageVector, @androidx.annotation.StringRes val title: Int, @androidx.annotation.StringRes val body: Int)

private val pages = listOf(
    Page(Icons.Outlined.Shield, R.string.tour_1_title, R.string.tour_1_body),
    Page(Icons.Outlined.SwipeUp, R.string.tour_2_title, R.string.tour_2_body),
    Page(Icons.Outlined.Search, R.string.tour_3_title, R.string.tour_3_body),
    Page(Icons.Outlined.Lock, R.string.tour_4_title, R.string.tour_4_body),
    Page(Icons.Outlined.Schedule, R.string.tour_5_title, R.string.tour_5_body),
    Page(Icons.Outlined.AutoAwesome, R.string.tour_6_title, R.string.tour_6_body),
    Page(Icons.Outlined.Home, R.string.tour_7_title, R.string.tour_7_body),
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
            TextButton(onClick = onDone, modifier = Modifier.testTag("tutorial_skip")) { Text(stringResource(R.string.skip)) }
        }
        HorizontalPager(pager, modifier = Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(p.icon, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(28.dp))
                Text(stringResource(p.title), style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(p.body), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                if (i == pages.lastIndex) {
                    Spacer(Modifier.height(24.dp))
                    if (isDefaultHome) Text(stringResource(R.string.it_s_already_your_home), color = MaterialTheme.colorScheme.primary)
                    else OutlinedButton(onClick = onSetDefaultHome, modifier = Modifier.testTag("tutorial_set_home")) { Text(stringResource(R.string.make_this_my_home_screen)) }
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
