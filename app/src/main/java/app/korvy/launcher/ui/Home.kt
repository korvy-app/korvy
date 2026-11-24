package app.korvy.launcher.ui

import app.korvy.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.withTimeoutOrNull
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.TimeZone
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.korvy.launcher.data.AppEntry
import app.korvy.launcher.data.AppRepository
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
    showHomePrompt: Boolean = false,
    onSetDefaultHome: () -> Unit = {},
    onDismissHomePrompt: () -> Unit = {},
    drag: DragController? = null,
    grid: @Composable (Modifier) -> Unit = { Spacer(it) },
    onOpenAssistant: (() -> Unit)? = null,
    widgets: @Composable () -> Unit = {},
) {
    val threshold = with(LocalDensity.current) { 60.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .testTag("home")
            .pointerInput(Unit) {
                // Swipe up anywhere (also starting on an icon) opens the app list. Runs after the icons see
                // the touch, so an icon that is already being dragged (its moves consumed) wins.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var total = 0f
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed || c.isConsumed) break
                        total += c.positionChange().y
                        if (total < -threshold) { c.consume(); onOpenDrawer(); break }
                    }
                }
            }
            .pointerInput(Unit) {
                // Hold an empty part of the home screen for Settings. Touches that an icon claimed are skipped.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                    if (up == null && currentEvent.changes.any { it.id == down.id && it.pressed && !it.isConsumed }) onOpenSettings()
                }
            }
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Spacer(Modifier.height(32.dp))
            Clock()
            Spacer(Modifier.height(12.dp))
            widgets()
            grid(Modifier.weight(1f).fillMaxWidth())
            if (showHomePrompt) HomePrompt(onSetDefaultHome, onDismissHomePrompt)
            if (suggestions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("suggestions"),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    suggestions.take(5).forEach { app -> SuggestedApp(app, repo, onLaunch, menu, drag) }
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
                    Text(if (onOpenAssistant != null) stringResource(R.string.search) else stringResource(R.string.search_apps_and_web), color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (onOpenAssistant != null) IconButton(onClick = onOpenAssistant, modifier = Modifier.testTag("open_assistant")) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = stringResource(R.string.assistant), tint = Color.White)
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_settings")) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings), tint = Color.White)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestedApp(app: AppEntry, repo: AppRepository, onLaunch: (AppEntry) -> Unit, menu: @Composable (AppEntry, Boolean, () -> Unit) -> Unit, drag: DragController?) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .width(68.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (drag != null) Modifier.appGestures(app, null, drag, onTap = { onLaunch(app) }, onMenu = { showMenu = it })
                    else Modifier.combinedClickable(onClick = { onLaunch(app) }, onLongClick = { showMenu = true })
                )
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

/** Shown until this is the default home app or the user taps "Not now" (Settings keeps the same button). */
@Composable
private fun HomePrompt(onSet: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(16.dp)
            .testTag("home_prompt"),
    ) {
        Text(stringResource(R.string.make_this_your_home_screen), color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.home_prompt_detail),
            color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("home_prompt_dismiss")) { Text(stringResource(R.string.not_now), color = Color.White) }
            Button(onClick = onSet, modifier = Modifier.testTag("home_prompt_set")) { Text(stringResource(R.string.set_as_home)) }
        }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Android announces every minute (TIME_TICK) and every jump: the clock or time zone set by the network
    // (e.g. turning off airplane mode after travelling), manually, or the date rolling over. Refresh on all
    // of them, and whenever the home screen comes back into view.
    DisposableEffect(context, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) TimeZone.setDefault(null) // re-read the zone
                now = LocalDateTime.now()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) now = LocalDateTime.now() }
        lifecycle.addObserver(observer)
        onDispose { context.unregisterReceiver(receiver); lifecycle.removeObserver(observer) }
    }
    val time = now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)).replace(Regex("\\s?[AaPp]\\.?[Mm]\\.?$"), "")
    Text(time, color = Color.White, style = TextStyle(fontSize = 72.sp, fontWeight = FontWeight.Light, shadow = shadow))
    Text(
        now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), color = Color.White.copy(alpha = 0.9f),
        style = TextStyle(fontSize = 18.sp, shadow = shadow),
    )
}
