package com.hiddenoutlier.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiddenoutlier.launcher.data.AppEntry
import com.hiddenoutlier.launcher.data.AppRepository
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f)

/** The home screen: clock, suggested apps for this time of day, and the search bar. Swipe up for all apps. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    suggestions: List<AppEntry>,
    repo: AppRepository,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    menu: @Composable (AppEntry, Boolean, () -> Unit) -> Unit,
) {
    val threshold = with(LocalDensity.current) { 60.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .testTag("home")
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { _, dy -> total += dy },
                    onDragEnd = { if (total < -threshold) onOpenDrawer() },
                )
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = {}, onLongClick = onOpenSettings,
            )
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Spacer(Modifier.height(32.dp))
            Clock()
            Spacer(Modifier.weight(1f))
            if (suggestions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("suggestions"),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    suggestions.take(5).forEach { app -> SuggestedApp(app, repo, onLaunch, menu) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClick = onOpenSearch)
                        .padding(horizontal = 18.dp)
                        .testTag("search_bar"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text("Search apps and the web", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_settings")) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestedApp(app: AppEntry, repo: AppRepository, onLaunch: (AppEntry) -> Unit, menu: @Composable (AppEntry, Boolean, () -> Unit) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .width(68.dp)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = { onLaunch(app) }, onLongClick = { showMenu = true })
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app, repo, 48.dp)
            Text(
                app.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 12.sp, shadow = shadow), modifier = Modifier.padding(top = 4.dp),
            )
        }
        menu(app, showMenu) { showMenu = false }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(60_000L - (System.currentTimeMillis() % 60_000L)) // wake exactly on the minute
        }
    }
    val time = now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)).replace(Regex("\\s?[AaPp]\\.?[Mm]\\.?$"), "")
    Text(time, color = Color.White, style = TextStyle(fontSize = 72.sp, fontWeight = FontWeight.Light, shadow = shadow))
    Text(
        now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), color = Color.White.copy(alpha = 0.9f),
        style = TextStyle(fontSize = 18.sp, shadow = shadow),
    )
}
