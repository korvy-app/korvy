package app.korvy.launcher.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.korvy.launcher.data.PlacedWidget
import app.korvy.launcher.data.WidgetView
import app.korvy.launcher.data.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The home screen's widgets, stacked full width. Hold one to resize, move or remove it. */
@Composable
fun WidgetStack(vm: LauncherViewModel) {
    val placed by vm.widgets.placed.collectAsStateWithLifecycle()
    if (placed.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("widgets")) {
        placed.forEachIndexed { i, w -> key(w.id) { WidgetCell(vm, w, first = i == 0, last = i == placed.lastIndex) } }
    }
}

@Composable
private fun WidgetCell(vm: LauncherViewModel, w: PlacedWidget, first: Boolean, last: Boolean) {
    val store = vm.widgets
    val info = remember(w.id) { store.info(w.id) } ?: return
    var menu by remember { mutableStateOf(false) }
    val widthDp = LocalConfiguration.current.screenWidthDp - 40
    val heightDp = Widgets.HEIGHTS_DP[w.size.coerceIn(0, 2)]
    Box(Modifier.fillMaxWidth().height(heightDp.dp).padding(vertical = 4.dp)) {
        AndroidView(
            factory = { ctx ->
                (store.host.createView(ctx, w.id, info) as WidgetView).apply { onLongPress = { menu = true } }
            },
            update = { v ->
                // Tell the widget how much room it has so it lays itself out to fit.
                if (Build.VERSION.SDK_INT >= 31) v.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp.toFloat(), heightDp.toFloat() - 8)))
                else @Suppress("DEPRECATION") v.updateAppWidgetSize(null, widthDp, heightDp - 8, widthDp, heightDp - 8)
            },
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
        )
        DropdownMenu(menu, { menu = false }) {
            listOf("Small", "Medium", "Large").forEachIndexed { i, label ->
                if (i != w.size) DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; store.resize(w.id, i) })
            }
            if (!first) DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; store.move(w.id, -1) })
            if (!last) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; store.move(w.id, 1) })
            DropdownMenuItem(text = { Text("Add another widget") }, onClick = { menu = false; vm.open(Screen.WIDGETS) })
            DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; store.remove(w.id) }, modifier = Modifier.testTag("widget_remove"))
        }
    }
}

/** Pick a widget to add: grouped by app, searchable. Widgets of hidden apps aren't offered. */
@Composable
fun WidgetPickerScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = vm.widgets
    var query by remember { mutableStateOf("") }
    val providers by produceState<List<AppWidgetProviderInfo>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            val hidden = vm.hiddenPackages()
            store.providers(hidden).sortedWith(compareBy({ appLabel(context, it).lowercase() }, { it.loadLabel(context.packageManager).lowercase() }))
        }
    }
    var pendingId by remember { mutableStateOf<Int?>(null) }

    fun configureOrAdd(id: Int) {
        val info = store.info(id) ?: run { store.host.deleteAppWidgetId(id); return }
        val optional = Build.VERSION.SDK_INT >= 28 && (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        val activity = context as? Activity
        if (info.configure != null && !optional && activity != null) {
            // The widget's own setup screen; MainActivity.onActivityResult adds it when that finishes.
            store.pendingConfigure = id
            runCatching { store.host.startAppWidgetConfigureActivityForResult(activity, id, 0, Widgets.REQ_CONFIGURE, null) }
                .onFailure { store.pendingConfigure = null; store.add(id, info) }
        } else store.add(id, info)
        onBack()
    }
    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = pendingId ?: return@rememberLauncherForActivityResult
        pendingId = null
        if (r.resultCode == Activity.RESULT_OK) configureOrAdd(id) else store.host.deleteAppWidgetId(id)
    }
    fun pick(info: AppWidgetProviderInfo) {
        val id = store.host.allocateAppWidgetId()
        if (store.manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) configureOrAdd(id)
        else {
            // First time only: Android asks the user to let this launcher add widgets.
            pendingId = id
            bind.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile))
        }
    }

    val shown = providers.filter { query.isBlank() || appLabel(context, it).contains(query, true) || it.loadLabel(context.packageManager).contains(query, true) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().imePadding().testTag("widget_picker")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Add a widget", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search widgets") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("widget_search"))
        if (providers.isEmpty()) Text("Loading…", modifier = Modifier.padding(20.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            var lastApp: String? = null
            shown.forEach { info ->
                val app = appLabel(context, info)
                if (app != lastApp) {
                    lastApp = app
                    item(key = "h_${info.provider.packageName}_$app") {
                        Text(app, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                }
                item(key = info.provider.flattenToString()) { ProviderRow(info) { pick(info) } }
            }
        }
    }
}

@Composable
private fun ProviderRow(info: AppWidgetProviderInfo, onPick: () -> Unit) {
    val context = LocalContext.current
    val preview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                (info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0))?.let { d ->
                    val w = d.intrinsicWidth.coerceIn(1, 600); val h = d.intrinsicHeight.coerceIn(1, 400)
                    d.toBitmap(w, h).asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val dm = context.resources.displayMetrics
    val cells = "${((info.minWidth / dm.density + 30) / 70).toInt().coerceAtLeast(1)} × ${((info.minHeight / dm.density + 30) / 70).toInt().coerceAtLeast(1)}"
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onPick).padding(8.dp).testTag("widget_${info.provider.className}"),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            preview?.let { Image(it, contentDescription = null, modifier = Modifier.heightIn(max = 90.dp)) }
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(info.loadLabel(context.packageManager))
            Text(cells, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun appLabel(context: android.content.Context, info: AppWidgetProviderInfo): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
}.getOrDefault(info.provider.packageName)
