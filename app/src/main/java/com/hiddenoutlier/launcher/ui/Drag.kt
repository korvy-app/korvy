package com.hiddenoutlier.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiddenoutlier.launcher.core.HomeGrid
import com.hiddenoutlier.launcher.data.AppEntry
import com.hiddenoutlier.launcher.data.AppRepository
import kotlin.math.roundToInt

/** Where a drag started: a home-screen cell, or a list (app drawer, search results, suggestions). */
data class DragInfo(val app: AppEntry, val fromCell: Int?, val pos: Offset)

/**
 * The one drag in progress. Positions are in root (window) coordinates, so an icon can be picked up in the
 * app list and dropped on the home grid after the list has faded away.
 */
class DragController(
    private val grid: () -> HomeGrid,
    private val onDrop: (AppEntry, target: Int, from: Int?) -> Unit,
    private val onRemove: (AppEntry) -> Unit,
    private val onStart: (DragInfo) -> Unit,
) {
    var drag by mutableStateOf<DragInfo?>(null)
        private set
    var gridBounds: Rect? = null
    var removeBounds: Rect? = null

    fun start(app: AppEntry, fromCell: Int?, pos: Offset) {
        drag = DragInfo(app, fromCell, pos).also(onStart)
    }

    fun move(pos: Offset) { drag = drag?.copy(pos = pos) }

    fun hoverCell(): Int? {
        val d = drag ?: return null
        val b = gridBounds ?: return null
        return grid().cellAt(d.pos.x - b.left, d.pos.y - b.top, b.width, b.height)
    }

    fun overRemove(): Boolean = drag?.let { d -> d.fromCell != null && removeBounds?.contains(d.pos) == true } ?: false

    fun drop() {
        val d = drag ?: return
        if (overRemove()) onRemove(d.app) else hoverCell()?.let { onDrop(d.app, it, d.fromCell) }
        drag = null
    }

    fun cancel() { drag = null }
}

/**
 * One gesture handler for an app icon, so a single touch is exactly one of:
 * - tap: open the app;
 * - hold and release: show the app's menu (and nothing else);
 * - hold and move: drag the app (the menu closes as the drag starts).
 * A scroll that starts before the hold (e.g. in the app list) cancels it.
 */
fun Modifier.appGestures(
    app: AppEntry,
    fromCell: Int?,
    drag: DragController,
    onTap: () -> Unit,
    onMenu: (Boolean) -> Unit,
    onDragStarted: () -> Unit = {},
): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val slop = with(LocalDensity.current) { 10.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .semantics(mergeDescendants = true) {
            onClick(label = "Open") { onTap(); true }
            onLongClick(label = "Options") { onMenu(true); true }
        }
        .pointerInput(app.key, fromCell) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume() // this touch belongs to the icon: the home screen's own long-press must not fire
                // Wait for the long-press time. Lifting first = tap. Moving first (beyond touch slop) or a parent
                // taking the touch (a list scrolling) = not ours: give up, so scrolling never turns into a drag.
                val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull Outcome.CANCEL
                        if (!c.pressed) { c.consume(); return@withTimeoutOrNull Outcome.TAP }
                        if (c.isConsumed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            return@withTimeoutOrNull Outcome.CANCEL
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") Outcome.CANCEL
                }
                when (outcome) {
                    Outcome.TAP -> { onTap(); return@awaitEachGesture }
                    Outcome.CANCEL -> return@awaitEachGesture
                    null -> {} // held still for the whole long-press time
                }
                // Held long enough.
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onMenu(true)
                var dragging = false
                var total = Offset.Zero
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val c = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) { c.consume(); if (dragging) drag.drop(); break }
                        total += c.positionChange()
                        c.consume()
                        if (!dragging && total.getDistance() > slop) {
                            dragging = true
                            onMenu(false)
                            drag.start(app, fromCell, origin + c.position)
                            onDragStarted()
                        }
                        if (dragging) drag.move(origin + c.position)
                    }
                } finally {
                    if (dragging && drag.drag != null) drag.cancel() // gesture cancelled by the system
                }
            }
        }
}

private enum class Outcome { TAP, CANCEL }

private val labelShadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f)

/** An app icon with its name underneath, as shown on the home screen and in the suggestion row. */
@Composable
fun AppTile(app: AppEntry, repo: AppRepository, modifier: Modifier = Modifier, iconSize: androidx.compose.ui.unit.Dp = 52.dp) {
    Column(modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppIcon(app, repo, iconSize)
        Text(
            app.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = TextStyle(fontSize = 12.sp, shadow = labelShadow), modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** The home-screen grid. While something is being dragged, the cells show faintly and the target lights up. */
@Composable
fun HomeGridView(
    grid: HomeGrid,
    apps: Map<String, AppEntry>,
    repo: AppRepository,
    drag: DragController,
    onLaunch: (AppEntry) -> Unit,
    menu: @Composable (AppEntry, Boolean, () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dragging = drag.drag != null
    val hover by remember(drag) { derivedStateOf { drag.hoverCell() } }
    val animations = LocalAnimations.current
    BoxWithConstraints(modifier.onGloballyPositioned { drag.gridBounds = it.boundsInRoot() }.testTag("home_grid")) {
        val cw = maxWidth / grid.cols
        val ch = maxHeight / grid.rows
        for (cell in 0 until grid.size) {
            val app = grid.cells[cell]?.let(apps::get)
            val isSource = dragging && drag.drag?.fromCell == cell
            Box(
                Modifier
                    .offset(x = cw * (cell % grid.cols), y = ch * (cell / grid.cols))
                    .size(cw, ch)
                    .padding(3.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (dragging) {
                    val target = hover == cell
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = if (target) 0.22f else 0.05f))
                            .border(1.dp, Color.White.copy(alpha = if (target) 0.7f else 0.15f), RoundedCornerShape(16.dp))
                    )
                }
                if (app != null) {
                    var showMenu by remember(app.key) { mutableStateOf(false) }
                    val alpha by animateFloatAsState(if (isSource) 0f else 1f, Motion.fade(animations, 120), label = "source")
                    Box(contentAlignment = Alignment.Center) {
                        AppTile(
                            app, repo,
                            Modifier
                                .width(cw)
                                .graphicsLayer { this.alpha = alpha }
                                .clip(RoundedCornerShape(14.dp))
                                .appGestures(app, cell, drag, onTap = { onLaunch(app) }, onMenu = { showMenu = it })
                                .testTag("home_app_${app.label}"),
                            iconSize = minOf(52.dp, ch - 30.dp),
                        )
                        menu(app, showMenu) { showMenu = false }
                    }
                }
            }
        }
    }
}

/** The icon following the finger, plus the "Remove" target while a home-screen icon is being dragged. */
@Composable
fun DragOverlay(drag: DragController, repo: AppRepository) {
    val d = drag.drag ?: return
    val size = 60.dp
    val half = with(LocalDensity.current) { (size / 2).toPx() }
    Box(Modifier.fillMaxSize()) {
        if (d.fromCell != null) {
            val over = drag.overRemove()
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp)
                    .onGloballyPositioned { drag.removeBounds = it.boundsInRoot().inflate(24f) }
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (over) Color(0xFFB3261E) else Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .testTag("drop_remove"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Close, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Remove from home", color = Color.White)
            }
        }
        Box(
            Modifier
                .offset { IntOffset((d.pos.x - half).roundToInt(), (d.pos.y - half).roundToInt()) }
                .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f; shadowElevation = 16f }
        ) { AppIcon(d.app, repo, size) }
    }
}
