package app.korvy.launcher.ui

import app.korvy.launcher.R
import androidx.compose.ui.res.stringResource
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.korvy.launcher.core.Folder
import app.korvy.launcher.core.HomeGrid
import app.korvy.launcher.data.AppEntry
import app.korvy.launcher.data.AppRepository

/** A folder as an AppEntry, so it can be dragged with the same gestures as an app (its key is "folder:<id>"). */
fun folderEntry(cellValue: String, f: Folder) = AppEntry(cellValue, f.name, "", android.content.ComponentName("folder", cellValue),
    Process.myUserHandle(), false)

/** A folder's icon: up to four of its apps in a rounded square. */
@Composable
fun FolderIcon(f: Folder, apps: Map<String, AppEntry>, repo: AppRepository, size: Dp) {
    val inner = (size - 10.dp) / 2
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Color.White.copy(alpha = 0.28f)).padding(3.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            f.keys.mapNotNull(apps::get).take(4).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { row.forEach { AppIcon(it, repo, inner) } }
            }
        }
    }
}

/** The folder tile on the home grid: preview icon and name. */
@Composable
fun FolderTile(f: Folder, apps: Map<String, AppEntry>, repo: AppRepository, iconSize: Dp, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FolderIcon(f, apps, repo, iconSize)
        Text(f.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 12.sp, shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f)),
            modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * An open folder: its name (tap to rename) and its apps. Tap an app to open it; hold for its menu; hold and drag
 * to take it out of the folder (the folder closes and the app follows the finger to the home grid).
 */
@Composable
fun FolderView(
    id: String,
    grid: HomeGrid,
    apps: Map<String, AppEntry>,
    repo: AppRepository,
    drag: DragController,
    onLaunch: (AppEntry) -> Unit,
    menu: @Composable (AppEntry, Boolean, () -> Unit) -> Unit,
    onRename: (String) -> Unit,
    onClose: () -> Unit,
) {
    val f = grid.folders[id] ?: run { onClose(); return }
    var field by remember(id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(f.name)) }
    var editing by remember(id) { mutableStateOf(false) }
    val name = field.text
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onRename(name); onClose() }
            .testTag("folder_view"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.86f).clip(RoundedCornerShape(28.dp)).background(Color(0xE61E2238))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!editing) Text(name, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable {
                    // Edit with the whole name selected, so typing replaces it.
                    field = androidx.compose.ui.text.input.TextFieldValue(name, androidx.compose.ui.text.TextRange(0, name.length)); editing = true
                }.testTag("folder_name"))
            else BasicTextField(
                value = field, onValueChange = { field = if (it.text.length <= 40) it else field }, singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = Color.White, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRename(name); editing = false; focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).focusRequester(focusRequester).testTag("folder_name_edit"),
            )
            if (editing) androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(f.keys.mapNotNull(apps::get), key = { it.key }) { app ->
                    var showMenu by remember(app.key) { mutableStateOf(false) }
                    Box(contentAlignment = Alignment.Center) {
                        AppTile(app, repo, Modifier.width(80.dp).clip(RoundedCornerShape(14.dp))
                            .appGestures(app, null, drag, onTap = { onClose(); onLaunch(app) }, onMenu = { showMenu = it },
                                onDragStarted = { onRename(name); onClose() })
                            .testTag("folder_app_${app.label}"), iconSize = 48.dp)
                        menu(app, showMenu) { showMenu = false }
                    }
                }
            }
        }
    }
}

/** Hold on a folder: rename it, or take it apart (its apps go back on the home screen where there's room). */
@Composable
fun FolderMenu(expanded: Boolean, onDismiss: () -> Unit, onOpen: () -> Unit, onUngroup: () -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.open_and_rename)) }, onClick = { onDismiss(); onOpen() })
        DropdownMenuItem(text = { Text(stringResource(R.string.ungroup)) }, onClick = { onDismiss(); onUngroup() }, modifier = Modifier.testTag("folder_ungroup"))
    }
}
