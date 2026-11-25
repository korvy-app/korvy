package app.korvy.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import app.korvy.launcher.R
import androidx.compose.ui.res.stringResource
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
import app.korvy.launcher.core.HomeGrid
import app.korvy.launcher.data.AppEntry
import app.korvy.launcher.data.AppRepository
import kotlin.math.roundToInt

/** Where a drag started: a home-screen cell, or a list (app drawer, search results, suggestions). */
data class DragInfo(val app: AppEntry, val fromCell: Int?, val pos: Offset)

/**
 * The one drag in progress. Positions are in root (window) coordinates, so an icon can be picked up in the
 * app list and dropped on the home grid after the list has faded away.
 */
class DragController(
    private val grid: () -> HomeGrid,
    private val onDrop: (AppEntry, target: Int, from: Int?, combine: Boolean) -> Unit,
    private val onRemove: (AppEntry) -> Unit,
    private val onStart: (DragInfo) -> Unit,
) {
    var drag by mutableStateOf<DragInfo?>(null)
        private set
    var gridBounds: Rect? = null
    var removeBounds: Rect? = null
    /** The home page on screen (set by the home pager), so a drop lands on that page. */
    var page: Int = 0

    fun start(app: AppEntry, fromCell: Int?, pos: Offset) {
        drag = DragInfo(app, fromCell, pos).also(onStart)
    }

    fun move(pos: Offset) { drag = drag?.copy(pos = pos) }

    fun hoverCell(): Int? {
        val d = drag ?: return null
        val b = gridBounds ?: return null
        val g = grid()
        return g.cellAt(d.pos.x - b.left, d.pos.y - b.top, b.width, b.height)?.let { page * g.pageSize + it }
    }

    /**
     * Over the middle of an occupied cell (not its edges): dropping there makes a folder (or adds to one).
     * Folders themselves never go into folders.
     */
    fun hoverCombine(): Boolean {
        val d = drag ?: return false
        if (HomeGrid.isFolder(d.app.key)) return false
        val b = gridBounds ?: return false
        val g = grid()
        val cell = hoverCell() ?: return false
        val occupant = g.cells[cell] ?: return false
        if (occupant == d.app.key) return false
        val cw = b.width / g.cols; val ch = b.height / g.rows
        val local = cell % g.pageSize
        val cx = b.left + cw * (local % g.cols + 0.5f); val cy = b.top + ch * (local / g.cols + 0.5f)
        val dx = d.pos.x - cx; val dy = d.pos.y - cy
        return kotlin.math.sqrt(dx * dx + dy * dy) < 0.32f * minOf(cw, ch)
    }

    fun overRemove(): Boolean = drag?.let { d -> d.fromCell != null && removeBounds?.contains(d.pos) == true } ?: false

    fun drop() {
        val d = drag ?: return
        if (overRemove()) onRemove(d.app) else hoverCell()?.let { onDrop(d.app, it, d.fromCell, hoverCombine()) }
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
    val openLabel = stringResource(R.string.open)
    val optionsLabel = stringResource(R.string.options)
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .semantics(mergeDescendants = true) {
            onClick(label = openLabel) { onTap(); true }
            onLongClick(label = optionsLabel) { onMenu(true); true }
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

/**
 * The home-screen grid, one page per screen; swipe sideways between pages (dots underneath). While something is
 * being dragged, the cells show faintly, the target lights up, an extra empty page waits on the right, and
 * holding the icon near the left or right edge turns the page.
 */
@Composable
fun HomeGridView(
    grid: HomeGrid,
    apps: Map<String, AppEntry>,
    repo: AppRepository,
    drag: DragController,
    onLaunch: (AppEntry) -> Unit,
    menu: @Composable (AppEntry, Boolean, () -> Unit) -> Unit,
    pager: androidx.compose.foundation.pager.PagerState,
    modifier: Modifier = Modifier,
    onOpenFolder: (String) -> Unit = {},
    onUngroup: (String) -> Unit = {},
) {
    val dragging = drag.drag != null
    val animations = LocalAnimations.current
    drag.page = pager.currentPage
    EdgeTurnsPage(drag, pager, animations)
    Column(modifier) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            userScrollEnabled = !dragging, // while dragging, the page turns at the screen edge instead
            beyondViewportPageCount = 1,
            modifier = Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { drag.gridBounds = it.boundsInRoot() }.testTag("home_grid"),
            key = { it },
        ) { page -> GridPage(grid, page, apps, repo, drag, onLaunch, menu, onOpenFolder, onUngroup) }
        if (pager.pageCount > 1) PageDots(pager, grid.pages)
    }
}

/** While an icon is held still near the left or right edge of the grid, turn the page (about every 0.7 s). */
@Composable
private fun EdgeTurnsPage(drag: DragController, pager: androidx.compose.foundation.pager.PagerState, animations: Boolean) {
    val edge = with(LocalDensity.current) { 28.dp.toPx() }
    val dragging = drag.drag != null
    androidx.compose.runtime.LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        var side = 0; var since = 0L
        while (drag.drag != null) {
            kotlinx.coroutines.delay(80)
            val d = drag.drag ?: break
            val b = drag.gridBounds ?: continue
            val now = side.let { _ -> when {
                d.pos.y < b.top || d.pos.y > b.bottom -> 0
                d.pos.x < b.left + edge -> -1
                d.pos.x > b.right - edge -> 1
                else -> 0
            } }
            if (now != side) { side = now; since = System.currentTimeMillis(); continue }
            if (side != 0 && System.currentTimeMillis() - since > 650) {
                val target = (pager.currentPage + side).coerceIn(0, pager.pageCount - 1)
                if (target != pager.currentPage) {
                    if (animations) pager.animateScrollToPage(target) else pager.scrollToPage(target)
                }
                since = System.currentTimeMillis()
            }
        }
    }
}

@Composable
private fun PageDots(pager: androidx.compose.foundation.pager.PagerState, realPages: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).testTag("page_dots"), horizontalArrangement = Arrangement.Center) {
        for (i in 0 until pager.pageCount) {
            val on = i == pager.currentPage
            Box(
                Modifier.padding(horizontal = 4.dp).size(if (on) 8.dp else 6.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .then(
                        if (i < realPages) Modifier.background(Color.White.copy(alpha = if (on) 0.95f else 0.45f))
                        else Modifier.border(1.dp, Color.White.copy(alpha = 0.7f), androidx.compose.foundation.shape.CircleShape) // the new page
                    )
            )
        }
    }
}

@Composable
private fun GridPage(
    grid: HomeGrid, page: Int, apps: Map<String, AppEntry>, repo: AppRepository, drag: DragController,
    onLaunch: (AppEntry) -> Unit, menu: @Composable (AppEntry, Boolean, () -> Unit) -> Unit,
    onOpenFolder: (String) -> Unit, onUngroup: (String) -> Unit,
) {
    val dragging = drag.drag != null
    val hover by remember(drag) { derivedStateOf { drag.hoverCell() } }
    val combine by remember(drag) { derivedStateOf { drag.hoverCombine() } }
    val animations = LocalAnimations.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cw = maxWidth / grid.cols
        val ch = maxHeight / grid.rows
        for (local in 0 until grid.pageSize) {
            val cell = page * grid.pageSize + local
            val app = grid.cells[cell]?.let(apps::get)
            val isSource = dragging && drag.drag?.fromCell == cell
            Box(
                Modifier
                    .offset(x = cw * (local % grid.cols), y = ch * (local / grid.cols))
                    .size(cw, ch)
                    .padding(3.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (dragging) {
                    val target = hover == cell
                    val folding = target && combine
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = if (folding) 0.4f else if (target) 0.22f else 0.05f))
                            .border(if (folding) 2.dp else 1.dp, Color.White.copy(alpha = if (target) 0.8f else 0.15f), RoundedCornerShape(16.dp))
                    )
                }
                val folder = grid.folder(grid.cells[cell])
                if (folder != null) {
                    val value = grid.cells.getValue(cell)
                    val entry = remember(value, folder.name) { folderEntry(value, folder) }
                    var showMenu by remember(value) { mutableStateOf(false) }
                    val alpha by animateFloatAsState(if (isSource) 0f else 1f, Motion.fade(animations, 120), label = "source")
                    Box(contentAlignment = Alignment.Center) {
                        FolderTile(folder, apps, repo, minOf(52.dp, ch - 30.dp),
                            Modifier.width(cw).graphicsLayer { this.alpha = alpha }.clip(RoundedCornerShape(14.dp))
                                .appGestures(entry, cell, drag, onTap = { onOpenFolder(value.removePrefix(HomeGrid.FOLDER)) }, onMenu = { showMenu = it })
                                .testTag("home_folder_${folder.name}"))
                        FolderMenu(showMenu, { showMenu = false }, onOpen = { onOpenFolder(value.removePrefix(HomeGrid.FOLDER)) },
                            onUngroup = { onUngroup(value) })
                    }
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
fun DragOverlay(drag: DragController, repo: AppRepository, grid: HomeGrid? = null, apps: Map<String, AppEntry> = emptyMap()) {
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
                Text(stringResource(R.string.remove_from_home), color = Color.White)
            }
        }
        Box(
            Modifier
                .offset { IntOffset((d.pos.x - half).roundToInt(), (d.pos.y - half).roundToInt()) }
                .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f; shadowElevation = 16f }
        ) {
            val f = grid?.folder(d.app.key)
            if (f != null) FolderIcon(f, apps, repo, size) else AppIcon(d.app, repo, size)
        }
    }
}
