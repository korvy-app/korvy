package com.hiddenoutlier.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiddenoutlier.launcher.data.AppEntry
import com.hiddenoutlier.launcher.data.HideLevel

/** Actions that need the Activity (the lock prompt, system dialogs). */
class Host(
    val authenticate: (title: String, onSuccess: () -> Unit) -> Unit,
    val canLock: () -> Boolean,
    val toast: (String) -> Unit,
    val setDefaultHome: () -> Unit,
    val isDefaultHome: () -> Boolean,
    val stats: com.hiddenoutlier.launcher.platform.StatsSampler,
)

@Composable
fun Root(vm: LauncherViewModel, host: Host) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val visible by vm.visibleApps.collectAsStateWithLifecycle()
    val privacy by vm.privacy.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val focus by vm.focusSearch.collectAsStateWithLifecycle()
    val bgFiles by vm.backgrounds.files.collectAsStateWithLifecycle()
    val lockedOpen by vm.lockedListOpen.collectAsStateWithLifecycle()
    val isDefaultHome by vm.isDefaultHome.collectAsStateWithLifecycle()
    val closeInstantly by vm.closeInstantly.collectAsStateWithLifecycle()
    val homeGrid by vm.homeGrid.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val busy by vm.assistantBusy.collectAsStateWithLifecycle()
    val installedModels by vm.models.installed.collectAsStateWithLifecycle()
    val download by vm.models.download.collectAsStateWithLifecycle()
    val pendingCrops by vm.pendingCrops.collectAsStateWithLifecycle()
    val webSuggestions by vm.webSuggestions.collectAsStateWithLifecycle()
    val contactResults by vm.contactResults.collectAsStateWithLifecycle()
    val drag = remember(vm) {
        DragController(
            grid = { vm.homeGrid.value },
            onDrop = { app, target, from -> vm.placeOnHome(app, target, from); vm.endDrag() },
            onRemove = { app -> vm.removeFromHome(app); vm.endDrag() },
            onStart = { d -> if (d.fromCell == null) vm.startDragFromList() },
        )
    }
    val animations = vm.animationsOn(settings)

    fun lockApp(app: AppEntry) {
        if (host.canLock()) vm.hide(app, HideLevel.LOCKED)
        else host.toast("Set a screen lock (PIN, pattern or password) in Android settings to lock apps.")
    }

    fun launchHidden(app: AppEntry, level: HideLevel) {
        if (level == HideLevel.LOCKED) host.authenticate("Open ${app.label}") { vm.launch(app) } else vm.launch(app)
    }

    val menu: @Composable (AppEntry, Boolean, () -> Unit) -> Unit = { app, expanded, dismiss ->
        AppMenu(
            app, expanded, neverSuggest = app.key in privacy.neverSuggest, onDismiss = dismiss,
            onHide = { vm.hide(app, HideLevel.HIDDEN); host.toast("${app.label} hidden. Find it in Settings → Hidden apps.") },
            onLock = { lockApp(app) },
            onToggleSuggest = { vm.setNeverSuggest(app.key, app.key !in privacy.neverSuggest) },
            onInfo = { vm.appInfo(app) },
            onUninstall = { vm.uninstall(app) },
            onHome = app.key in homeGrid.cells.values,
            onAddHome = { if (!vm.addToHome(app)) host.toast("The home screen is full. Remove an app first.") },
            onRemoveHome = { vm.removeFromHome(app) },
        )
    }

    LauncherTheme(animations) {
        Box(Modifier.fillMaxSize()) {
            LauncherBackground(bgFiles, settings.slideshow, settings.slideshowMinutes)
            // The home screen fades out under the app list so its clock doesn't show through.
            val homeAlpha by androidx.compose.animation.core.animateFloatAsState(
                if (screen == Screen.HOME) 1f else 0f, Motion.fade(animations), label = "home")
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = homeAlpha }) { HomeScreen(
                suggestions, vm.repo,
                onOpenDrawer = { vm.open(Screen.DRAWER) },
                onOpenSearch = { vm.openSearch() },
                onOpenSettings = { vm.open(Screen.SETTINGS) },
                onLaunch = vm::launch,
                menu = menu,
                showHomePrompt = !isDefaultHome && !settings.homePromptDismissed && settings.tutorialDone,
                onSetDefaultHome = host.setDefaultHome,
                onDismissHomePrompt = vm::dismissHomePrompt,
                drag = drag,
                grid = { m -> HomeGridView(homeGrid, visible.associateBy { it.key }, vm.repo, drag, vm::launch, menu, m) },
                onOpenAssistant = if (settings.assistant) ({ vm.openAssistant() }) else null,
            ) }
            // During a drag that started in the list, the list stays composed (it owns the gesture) but invisible.
            val listDrag = drag.drag?.let { it.fromCell == null } == true
            AnimatedVisibility(
                visible = screen == Screen.DRAWER || listDrag,
                // Swallow taps on the outer layer while the list is closing (it is still drawn on top for a moment).
                modifier = Modifier.swallowTouches(screen != Screen.DRAWER && !listDrag),
                enter = slideInVertically(Motion.spring<IntOffset>(animations)) { it / 3 } + fadeIn(Motion.fade(animations)),
                exit = if (closeInstantly) androidx.compose.animation.ExitTransition.None
                    else slideOutVertically(Motion.spring<IntOffset>(animations)) { it / 3 } + fadeOut(Motion.fade(animations)),
            ) {
                AppDrawer(
                    visible, results, query, settings.engine, focus, vm.repo,
                    onQuery = { vm.query.value = it },
                    onLaunch = vm::launch,
                    onLaunchHidden = ::launchHidden,
                    onWebSearch = vm::webSearch,
                    menu = menu,
                    drag = drag,
                    // While closing (or hidden under a drag), the list must not take taps meant for the home screen.
                    modifier = Modifier.graphicsLayer { alpha = if (listDrag) 0f else 1f }
                        .swallowTouches(screen != Screen.DRAWER && !listDrag),
                    onAsk = if (settings.assistant) ({ q -> vm.openAssistant(q) }) else null,
                    webSuggestions = webSuggestions,
                    contacts = contactResults, onCall = vm::dial, onText = vm::textTo,
                )
            }
            AnimatedVisibility(screen == Screen.SETTINGS, enter = fadeIn(Motion.fade(animations)), exit = fadeOut(Motion.fade(animations))) {
                SettingsScreen(
                    settings, animations, vm.tier, bgFiles, isDefaultHome, vm,
                    onSetDefaultHome = host.setDefaultHome, onBack = { vm.goHome() },
                )
            }
            AnimatedVisibility(screen == Screen.HIDDEN, enter = fadeIn(Motion.fade(animations)), exit = fadeOut(Motion.fade(animations))) {
                val (hidden, locked) = splitHidden(apps, privacy.hidden)
                HiddenAppsScreen(
                    hidden, locked, lockedOpen, vm.repo,
                    onUnlockList = { host.authenticate("Show locked apps") { vm.lockedListOpen.value = true } },
                    onLaunch = { vm.launch(it) },
                    onUnhide = { vm.unhide(it.key) },
                    onLock = ::lockApp,
                    onUnlockApp = { vm.hide(it, HideLevel.HIDDEN) },
                    onBack = { vm.open(Screen.SETTINGS) },
                )
            }
            AnimatedVisibility(screen == Screen.STATS, enter = fadeIn(Motion.fade(animations)), exit = fadeOut(Motion.fade(animations))) {
                StatsScreen(host.stats, onBack = { vm.open(Screen.SETTINGS) })
            }
            AnimatedVisibility(screen == Screen.ASSISTANT, enter = fadeIn(Motion.fade(animations)), exit = fadeOut(Motion.fade(animations))) {
                AssistantScreen(
                    chat, busy, com.hiddenoutlier.launcher.assistant.ModelCatalog.byId(settings.modelId)?.takeIf { it.id in installedModels }?.name,
                    onSend = { vm.ask(it) }, onVoice = { vm.ask(it, byVoice = true) }, onStop = vm::stopAssistant, onClear = vm::clearChat,
                    onOpenModels = { vm.open(Screen.MODELS) }, onBack = { vm.goHome() },
                    speak = settings.speak, onSpeak = vm::setSpeak, onSpeakOn = { vm.assistant.sayHello() },
                    cardActions = rememberCardActions(vm.assistant),
                )
            }
            AnimatedVisibility(screen == Screen.MODELS, enter = fadeIn(Motion.fade(animations)), exit = fadeOut(Motion.fade(animations))) {
                ModelsScreen(
                    settings, installedModels, download, vm.models.freeBytes(), vm.models.isMetered(),
                    onDownload = vm::downloadModel, onCancel = vm::cancelDownload, onUse = vm::useModel, onDelete = vm::deleteModel,
                    onOpenOnline = { vm.open(Screen.SETTINGS) }, onBack = { vm.open(Screen.SETTINGS) },
                )
            }
            AnimatedVisibility(screen == Screen.PERSONALITY, enter = fadeIn(Motion.fade(animations)), exit = fadeOut(Motion.fade(animations))) {
                PersonalityScreen(vm.personality, onSave = { vm.savePersonality(it) }, onBack = { vm.open(Screen.SETTINGS) })
            }
            AnimatedVisibility(screen == Screen.CROP && pendingCrops.isNotEmpty(), enter = fadeIn(Motion.fade(animations)), exit = fadeOut(Motion.fade(animations))) {
                pendingCrops.firstOrNull()?.let { uri ->
                    CropScreen(uri, vm.cropTotal - pendingCrops.size + 1, vm.cropTotal, vm.backgrounds,
                        onUse = vm::finishCrop, onSkip = vm::skipCrop)
                }
            }
            AnimatedVisibility(screen == Screen.VOICE, enter = fadeIn(Motion.fade(animations)), exit = fadeOut(Motion.fade(animations))) {
                VoiceSettingsScreen(settings, vm, onBack = { vm.open(Screen.SETTINGS) })
            }
            AnimatedVisibility(screen == Screen.PEOPLE, enter = fadeIn(Motion.fade(animations)), exit = fadeOut(Motion.fade(animations))) {
                PeopleScreen(vm, onBack = { vm.open(Screen.SETTINGS) })
            }
            AnimatedVisibility(screen == Screen.APPS, enter = fadeIn(Motion.fade(animations)), exit = fadeOut(Motion.fade(animations))) {
                AppsForTasksScreen(settings, vm, onBack = { vm.open(Screen.SETTINGS) })
            }
            DragOverlay(drag, vm.repo)
            AnimatedVisibility(screen == Screen.TUTORIAL, enter = fadeIn(Motion.fade(animations)), exit = fadeOut(Motion.fade(animations))) {
                Tutorial(isDefaultHome, { vm.tourSetDefaultHome(host.setDefaultHome) }, onDone = { vm.finishTutorial() })
            }
        }
        BackHandler(enabled = screen != Screen.HOME) {
            when (screen) {
                Screen.HIDDEN, Screen.STATS, Screen.MODELS, Screen.PERSONALITY, Screen.VOICE, Screen.PEOPLE, Screen.APPS -> vm.open(Screen.SETTINGS)
                Screen.CROP -> vm.skipCrop()
                Screen.TUTORIAL -> vm.finishTutorial()
                else -> vm.goHome()
            }
        }
    }
}

/**
 * While [active], eats every touch before the content sees it. Used on screens that are animating away, which
 * are still drawn on top for a moment: without this, a tap meant for the home screen (e.g. the Settings gear)
 * would land on whatever app icon of the closing list sits under the finger.
 */
fun Modifier.swallowTouches(active: Boolean): Modifier =
    if (!active) this else this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
