package app.korvy.launcher.ui

import app.korvy.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material.icons.automirrored.outlined.KeyboardTab
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.korvy.launcher.data.AppEntry
import app.korvy.launcher.data.AppRepository
import app.korvy.launcher.data.HideLevel
import app.korvy.launcher.data.SearchEngine
import app.korvy.launcher.core.Calc
import app.korvy.launcher.core.Completion

/** All apps plus search. Typing filters apps; the last row searches the web with the chosen engine. */
@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    results: LauncherViewModel.Results,
    query: String,
    engine: SearchEngine,
    focusSignal: Int,
    repo: AppRepository,
    onQuery: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onLaunchHidden: (AppEntry, HideLevel) -> Unit,
    onWebSearch: (String) -> Unit,
    menu: @Composable (AppEntry, Boolean, () -> Unit) -> Unit,
    drag: DragController,
    modifier: Modifier = Modifier,
    onAsk: ((String) -> Unit)? = null,
    webSuggestions: List<String> = emptyList(),
    contacts: List<app.korvy.launcher.assistant.Person> = emptyList(),
    onCall: (app.korvy.launcher.assistant.Person) -> Unit = {},
    onText: (app.korvy.launcher.assistant.Person) -> Unit = {},
    /** Offer to turn on suggestions from this engine (its name), or null not to ask. */
    suggestOffer: String? = null,
    onEnableSuggestions: () -> Unit = {},
    onDismissSuggestOffer: () -> Unit = {},
    /** Columns of the app list when it should match the home grid; null = as many as fit. */
    columns: Int? = null,
) {
    // Inline autocomplete: the rest of the top app's name, drawn greyed after the cursor; → accepts it.
    val completion = Completion.suffix(query, results.apps.map { it.label })
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(query)) }
    LaunchedEffect(query) {
        if (query != field.text) field = androidx.compose.ui.text.input.TextFieldValue(query, androidx.compose.ui.text.TextRange(query.length))
    }
    val pctWords = stringResource(R.string.calc_percent); val tipWords = stringResource(R.string.calc_tip)
    val calc = remember(query, pctWords) {
        Calc.answer(query, Calc.Words({ p, n, r -> String.format(pctWords, p, n, r) }, { p, b, t, total -> String.format(tipWords, p, b, t, total) }))
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Close the keyboard before leaving for another app, or Android shows it again for a moment on return.
    fun leaving(action: () -> Unit) { keyboard?.hide(); focusManager.clearFocus(force = true); action() }
    val launch: (AppEntry) -> Unit = { app -> leaving { onLaunch(app) } }
    val launchHidden: (AppEntry, HideLevel) -> Unit = { app, level -> leaving { onLaunchHidden(app, level) } }
    val webSearch: (String) -> Unit = { q -> leaving { onWebSearch(q) } }
    val dragStarted: () -> Unit = { keyboard?.hide(); focusManager.clearFocus(force = true) }
    LaunchedEffect(focusSignal) { if (focusSignal > 0) { focus.requestFocus(); keyboard?.show() } }

    Column(
        modifier
            .fillMaxSize()
            .background(Color(0xE6101418)) // solid enough to read, light enough to feel layered
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag("drawer")
    ) {
        OutlinedTextField(
            value = field,
            onValueChange = { field = it; if (it.text != query) onQuery(it.text) },
            singleLine = true,
            visualTransformation = ghost(completion),
            placeholder = { Text(stringResource(R.string.search_apps_and_the_web)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (completion != null) IconButton(onClick = { onQuery(query + completion) }, modifier = Modifier.testTag("accept_completion")) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardTab, stringResource(R.string.complete_to, query + completion))
                } else if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                val first = results.apps.firstOrNull()
                when {
                    first != null -> launch(first)
                    query.isNotBlank() -> webSearch(query)
                }
            }),
            shape = RoundedCornerShape(26.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus).testTag("search_field"),
        )

        if (query.isBlank()) {
            LazyVerticalGrid(
                columns = columns?.let { GridCells.Fixed(it) } ?: GridCells.Adaptive(84.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.fillMaxSize().testTag("app_grid"),
            ) {
                items(apps, key = { it.key }) { app -> GridApp(app, repo, launch, menu, drag, dragStarted) }
            }
        } else {
            // The best match must stay in view: without this the list keeps whatever row was on top (e.g. "Search the
            // web") in place while new matches appear above it, pushing the top app off screen.
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            androidx.compose.runtime.LaunchedEffect(query, results.apps.firstOrNull()?.key) { listState.scrollToItem(0) }
            LazyColumn(Modifier.fillMaxSize().testTag("results"), state = listState) {
                if (calc != null) item(key = "calc") {
                    Text(calc, color = Color.White, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("calc_result"))
                }
                items(results.apps, key = { it.key }) { app -> ResultRow(app, repo, null, { launch(app) }, menu, drag, dragStarted) }
                items(results.revealed, key = { "hidden-" + it.first.key }) { (app, level) ->
                    ResultRow(app, repo, level, { launchHidden(app, level) }, menu, null, dragStarted)
                }
                items(contacts, key = { "contact-" + it.name + it.number }) { p ->
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { leaving { onCall(p) } }).padding(horizontal = 20.dp, vertical = 8.dp).testTag("contact_${p.name}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        ContactAvatar(p, 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                            Text(listOf(p.label, p.number).filter { it.isNotBlank() }.joinToString(" · "), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { leaving { onText(p) } }) { Icon(Icons.Outlined.Sms, stringResource(R.string.text_person, p.name), tint = Color.White) }
                        IconButton(onClick = { leaving { onCall(p) } }) { Icon(Icons.Outlined.Call, stringResource(R.string.call_person, p.name), tint = Color.White) }
                    }
                }
                if (onAsk != null) item(key = "ask") {
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { keyboard?.hide(); onAsk(query) })
                            .padding(horizontal = 20.dp, vertical = 14.dp).testTag("ask_assistant"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(40.dp).padding(6.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.ask_the_assistant, query), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                items(webSuggestions, key = { "sugg-$it" }) { s ->
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { webSearch(s) }).padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Search, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(40.dp).padding(8.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(s, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onQuery(s) }) { Icon(Icons.Outlined.NorthWest, stringResource(R.string.put_in_search, s), tint = Color.White.copy(alpha = 0.7f)) }
                    }
                }
                if (suggestOffer != null && query.length >= 2) item(key = "suggest_offer") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).testTag("suggest_offer")) {
                        Text(stringResource(R.string.suggest_offer, suggestOffer), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.suggest_offer_detail, suggestOffer), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            androidx.compose.material3.FilledTonalButton(onClick = onEnableSuggestions, modifier = Modifier.testTag("suggest_on")) { Text(stringResource(R.string.suggest_turn_on)) }
                            androidx.compose.material3.TextButton(onClick = onDismissSuggestOffer) { Text(stringResource(R.string.suggest_no_thanks), color = Color.White) }
                        }
                    }
                }
                item(key = "web") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = { webSearch(query) })
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                            .testTag("web_search"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Language, null, tint = Color.White, modifier = Modifier.size(40.dp).padding(6.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.search_for, engine.title, query), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridApp(app: AppEntry, repo: AppRepository, onLaunch: (AppEntry) -> Unit, menu: @Composable (AppEntry, Boolean, () -> Unit) -> Unit, drag: DragController, dragStarted: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .appGestures(app, null, drag, onTap = { onLaunch(app) }, onMenu = { showMenu = it }, onDragStarted = dragStarted)
                .padding(vertical = 10.dp, horizontal = 4.dp)
                .testTag("app_${app.label}"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app, repo, 52.dp)
            Text(
                app.label, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
            )
        }
        menu(app, showMenu) { showMenu = false }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(app: AppEntry, repo: AppRepository, hidden: HideLevel?, onClick: () -> Unit, menu: @Composable (AppEntry, Boolean, () -> Unit) -> Unit, drag: DragController?, dragStarted: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    // Hidden apps revealed by name can be opened but not menu'd or dragged onto the home screen.
                    if (hidden == null && drag != null) Modifier.appGestures(app, null, drag, onTap = onClick, onMenu = { showMenu = it }, onDragStarted = dragStarted)
                    else Modifier.combinedClickable(onClick = onClick)
                )
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("result_${app.label}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            AppIcon(app, repo, 40.dp)
            Spacer(Modifier.width(14.dp))
            Text(app.label, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            when (hidden) {
                HideLevel.HIDDEN -> Icon(Icons.Outlined.VisibilityOff, stringResource(R.string.hidden_app), tint = Color.White.copy(alpha = 0.6f))
                HideLevel.LOCKED -> Icon(Icons.Outlined.Lock, stringResource(R.string.locked_app), tint = Color.White.copy(alpha = 0.6f))
                null -> {}
            }
        }
        menu(app, showMenu) { showMenu = false }
    }
}

/** Long-press menu for an app. */
@Composable
fun AppMenu(
    app: AppEntry,
    expanded: Boolean,
    neverSuggest: Boolean,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
    onLock: () -> Unit,
    onToggleSuggest: () -> Unit,
    onInfo: () -> Unit,
    onUninstall: () -> Unit,
    onHome: Boolean = false,
    onAddHome: () -> Unit = {},
    onRemoveHome: () -> Unit = {},
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(app.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (onHome) DropdownMenuItem(text = { Text(stringResource(R.string.remove_from_home)) }, onClick = { onDismiss(); onRemoveHome() }, modifier = Modifier.testTag("menu_remove_home"))
        else DropdownMenuItem(text = { Text(stringResource(R.string.add_to_home)) }, onClick = { onDismiss(); onAddHome() }, modifier = Modifier.testTag("menu_add_home"))
        DropdownMenuItem(text = { Text(stringResource(R.string.hide)) }, onClick = { onDismiss(); onHide() }, modifier = Modifier.testTag("menu_hide"))
        DropdownMenuItem(text = { Text(stringResource(R.string.hide_and_lock)) }, onClick = { onDismiss(); onLock() }, modifier = Modifier.testTag("menu_lock"))
        DropdownMenuItem(
            text = { Text(if (neverSuggest) stringResource(R.string.allow_in_suggestions) else stringResource(R.string.never_suggest)) },
            onClick = { onDismiss(); onToggleSuggest() },
        )
        DropdownMenuItem(text = { Text(stringResource(R.string.app_info)) }, onClick = { onDismiss(); onInfo() })
        if (!app.isWorkProfile) DropdownMenuItem(text = { Text(stringResource(R.string.uninstall)) }, onClick = { onDismiss(); onUninstall() })
    }
}

/** Shows [suffix] greyed after the typed text without making it part of the text (the cursor can't enter it). */
private fun ghost(suffix: String?): androidx.compose.ui.text.input.VisualTransformation =
    if (suffix.isNullOrEmpty()) androidx.compose.ui.text.input.VisualTransformation.None
    else androidx.compose.ui.text.input.VisualTransformation { text ->
        val shown = androidx.compose.ui.text.buildAnnotatedString {
            append(text)
            pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.White.copy(alpha = 0.4f))); append(suffix); pop()
        }
        androidx.compose.ui.text.input.TransformedText(shown, object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset
            override fun transformedToOriginal(offset: Int) = minOf(offset, text.length)
        })
    }
