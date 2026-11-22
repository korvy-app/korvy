package com.hiddenoutlier.launcher.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hiddenoutlier.launcher.LauncherApp
import com.hiddenoutlier.launcher.core.AppSearch
import com.hiddenoutlier.launcher.core.DeviceTiers
import com.hiddenoutlier.launcher.core.Suggest
import com.hiddenoutlier.launcher.core.Tier
import com.hiddenoutlier.launcher.data.AppEntry
import com.hiddenoutlier.launcher.data.HideLevel
import com.hiddenoutlier.launcher.data.Privacy
import com.hiddenoutlier.launcher.data.SearchEngine
import com.hiddenoutlier.launcher.data.Settings
import com.hiddenoutlier.launcher.platform.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

enum class Screen { HOME, DRAWER, SETTINGS, HIDDEN, TUTORIAL, STATS }

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    private val la = app as LauncherApp
    val repo = la.apps
    val backgrounds = la.backgrounds
    val tier: Tier = Device.tier(app)

    val apps: StateFlow<List<AppEntry>> = repo.apps
    private val _privacy = MutableStateFlow(Privacy())
    val privacy: StateFlow<Privacy> = _privacy
    val settings: StateFlow<Settings> = la.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings(tutorialDone = true))

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen
    val query = MutableStateFlow("")
    /** Bumped when the drawer should take keyboard focus (opened from the search bar). */
    val focusSearch = MutableStateFlow(0)
    private val _suggested = MutableStateFlow<List<String>>(emptyList())
    /** Whether this is the phone's default home app; refreshed every time the launcher comes into view. */
    val isDefaultHome = MutableStateFlow(Device.isDefaultHome(app))
    fun refreshDefaultHome() { isDefaultHome.value = Device.isDefaultHome(getApplication()) }

    /** Unlocked for this visit to Settings → Hidden apps; locks again when the user leaves. */
    val lockedListOpen = MutableStateFlow(false)

    init {
        repo.onChanged = { viewModelScope.launch { repo.refresh(); refreshSuggestions() } }
        viewModelScope.launch {
            _privacy.value = withContext(Dispatchers.IO) { la.privacy.load() }
            repo.refresh()
            refreshSuggestions()
        }
        viewModelScope.launch {
            // First run shows the tutorial.
            la.settings.settings.collect { if (!it.tutorialDone && _screen.value == Screen.HOME) _screen.value = Screen.TUTORIAL }
        }
    }

    fun animationsOn(s: Settings) = s.animations ?: DeviceTiers.animationsDefault(tier)

    /** Apps shown anywhere normal: hidden and locked apps are left out. */
    val visibleApps: StateFlow<List<AppEntry>> = combine(apps, privacy) { a, p -> a.filter { it.key !in p.hidden } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val suggestions: StateFlow<List<AppEntry>> = combine(apps, privacy, _suggested, settings) { a, p, keys, s ->
        if (!s.suggestions) emptyList()
        else {
            val byKey = a.associateBy { it.key }
            keys.mapNotNull { byKey[it] }.filter { it.key !in p.hidden && it.key !in p.neverSuggest }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    data class Results(val apps: List<AppEntry>, val revealed: List<Pair<AppEntry, HideLevel>>)

    val results: StateFlow<Results> = combine(query, apps, privacy, settings) { q, a, p, s ->
        if (q.isBlank()) Results(emptyList(), emptyList())
        else {
            val visible = a.filter { it.key !in p.hidden }
            // A hidden app appears only when its whole name is typed (and the user allows it).
            val revealed = if (s.revealHiddenByName) a.filter { it.key in p.hidden && AppSearch.isExact(q, it.label) }
                .map { it to p.hidden.getValue(it.key) } else emptyList()
            Results(AppSearch.rank(q, visible) { it.label }, revealed)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Results(emptyList(), emptyList()))

    fun refreshSuggestions() {
        viewModelScope.launch {
            val p = _privacy.value
            _suggested.value = withContext(Dispatchers.IO) {
                Suggest.rank(la.usage.recent(), ZonedDateTime.now(), exclude = p.hidden.keys + p.neverSuggest, limit = 5)
            }
        }
    }

    // Navigation
    fun open(s: Screen) { _screen.value = s; if (s != Screen.HIDDEN) lockedListOpen.value = false }
    fun openSearch() { open(Screen.DRAWER); focusSearch.value++ }
    fun goHome() { _screen.value = Screen.HOME; query.value = ""; lockedListOpen.value = false; refreshSuggestions() }

    private var leftForApp = false

    /** Called when the launcher goes out of view. Only opening an app resets it to the home screen;
     *  the photo picker, lock prompt and system dialogs come back to where the user was. */
    fun onStopped() { if (leftForApp) { leftForApp = false; goHome() } }

    fun launch(app: AppEntry) {
        leftForApp = true
        runCatching { repo.launch(app) }
        // Never record hidden apps: they must not shape suggestions.
        if (app.key !in _privacy.value.hidden) viewModelScope.launch(Dispatchers.IO) { la.usage.record(app.key) }
        query.value = ""
    }

    fun webSearch(q: String) {
        leftForApp = true
        val s = settings.value
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(s.engine.url + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
        query.value = ""
    }

    private fun updatePrivacy(f: (Privacy) -> Privacy) {
        val p = f(_privacy.value)
        _privacy.value = p
        viewModelScope.launch(Dispatchers.IO) { la.privacy.save(p) }
    }

    fun hide(app: AppEntry, level: HideLevel) {
        updatePrivacy { it.copy(hidden = it.hidden + (app.key to level)) }
        viewModelScope.launch(Dispatchers.IO) { la.usage.forget(app.key) } // and forget its history
        refreshSuggestions()
    }

    fun unhide(key: String) = updatePrivacy { it.copy(hidden = it.hidden - key) }

    fun setNeverSuggest(key: String, never: Boolean) {
        updatePrivacy { it.copy(neverSuggest = if (never) it.neverSuggest + key else it.neverSuggest - key) }
        refreshSuggestions()
    }

    fun forgetHistory() {
        viewModelScope.launch(Dispatchers.IO) { la.usage.forgetAll(); _suggested.value = emptyList() }
    }

    fun uninstall(app: AppEntry) {
        val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(i) }
    }

    fun appInfo(app: AppEntry) = runCatching { repo.openAppInfo(app) }

    // Settings
    private fun save(block: suspend () -> Unit) { viewModelScope.launch { block() } }
    fun setAnimations(v: Boolean) = save { la.settings.setAnimations(v) }
    fun setEngine(v: SearchEngine) = save { la.settings.setEngine(v) }
    fun setSuggestions(v: Boolean) = save { la.settings.setSuggestions(v) }
    fun setRevealHiddenByName(v: Boolean) = save { la.settings.setRevealHiddenByName(v) }
    fun setSlideshow(v: Boolean) = save { la.settings.setSlideshow(v) }
    fun setSlideshowMinutes(v: Int) = save { la.settings.setSlideshowMinutes(v) }
    fun finishTutorial() { save { la.settings.setTutorialDone(true) }; goHome() }

    /** Asking to become the home app from the tour counts as finishing it: Android restarts the launcher
     *  once it's the default, and the tour must not start over. */
    fun tourSetDefaultHome(request: () -> Unit) {
        viewModelScope.launch { la.settings.setTutorialDone(true); request() }
    }

    fun dismissHomePrompt() = save { la.settings.setHomePromptDismissed(true) }
    fun importBackgrounds(uris: List<Uri>) = save { backgrounds.import(uris) }
}
