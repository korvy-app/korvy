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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

enum class Screen { HOME, DRAWER, SETTINGS, HIDDEN, TUTORIAL, STATS, ASSISTANT, MODELS, PERSONALITY, CROP, VOICE }

/** One line of the assistant conversation. */
data class ChatItem(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val lines: List<String> = emptyList(),
    val link: String? = null,
    val pending: Boolean = false,
)

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

    /** The home grid as stored, and as shown (apps that were uninstalled or hidden are left out). */
    val homeGridStored: StateFlow<com.hiddenoutlier.launcher.core.HomeGrid> =
        la.settings.homeGrid.stateIn(viewModelScope, SharingStarted.Eagerly, com.hiddenoutlier.launcher.core.HomeGrid())
    val homeGrid: StateFlow<com.hiddenoutlier.launcher.core.HomeGrid> = combine(homeGridStored, visibleApps) { g, v ->
        if (v.isEmpty()) g else g.keepOnly(v.mapTo(HashSet()) { it.key })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, com.hiddenoutlier.launcher.core.HomeGrid())

    private fun updateGrid(f: (com.hiddenoutlier.launcher.core.HomeGrid) -> com.hiddenoutlier.launcher.core.HomeGrid) {
        viewModelScope.launch { la.settings.setHomeGrid(f(homeGridStored.value)) }
    }
    fun placeOnHome(app: AppEntry, target: Int, from: Int?) = updateGrid { it.place(app.key, target, from) }
    fun addToHome(app: AppEntry): Boolean {
        val g = homeGridStored.value
        if (g.cellOf(app.key) == null && g.firstEmpty() == null) return false
        updateGrid { it.add(app.key) }; return true
    }
    fun removeFromHome(app: AppEntry) = updateGrid { it.remove(app.key) }
    fun isOnHome(app: AppEntry) = homeGridStored.value.cellOf(app.key) != null

    /** A drag that started in the app list: show the home screen underneath, keep the list (and the
     *  gesture) alive until the drop. */
    fun startDragFromList() { _screen.value = Screen.HOME }
    fun endDrag() { query.value = "" }

    /** DuckDuckGo suggestions for the search box (opt-in source; nothing is fetched unless it's on). */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val webSuggestions: StateFlow<List<String>> = combine(query, settings) { q, s -> q to s }
        .debounce(250)
        .mapLatest { (q, s) ->
            if (q.isBlank() || q.length > 100 || !s.allows(com.hiddenoutlier.launcher.platform.OnlineSource.SUGGESTIONS)) emptyList()
            else runCatching {
                val body = com.hiddenoutlier.launcher.platform.Net.getText(com.hiddenoutlier.launcher.platform.OnlineSource.SUGGESTIONS,
                    "https://duckduckgo.com/ac/?type=list&q=" + Uri.encode(q)) { settings.value.allows(it) }
                val arr = org.json.JSONArray(body).getJSONArray(1)
                (0 until arr.length()).map { arr.getString(it) }.filter { !it.equals(q, ignoreCase = true) }.take(5)
            }.getOrElse { emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        updateGrid { it.remove(app.key) } // never keep a hidden app's key in the (unencrypted) layout
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

    // ---- Assistant (shared with the power-button panel: see AssistantController) -----------------------------
    val assistant = la.assistant
    val models = la.models
    val personality = la.personality
    val chat = assistant.chat
    val assistantBusy = assistant.busy

    fun openAssistant(question: String? = null) {
        _screen.value = Screen.ASSISTANT
        query.value = ""
        if (!question.isNullOrBlank()) ask(question)
    }

    fun ask(text: String, byVoice: Boolean = false) = assistant.ask(text, byVoice)
    fun stopAssistant() = assistant.stop()
    fun clearChat() = assistant.clear()

    /** Runs in a foreground service so it survives the screen turning off (see ModelDownloadService). */
    fun downloadModel(m: com.hiddenoutlier.launcher.assistant.ModelInfo) =
        com.hiddenoutlier.launcher.assistant.ModelDownloadService.start(getApplication(), m)
    fun cancelDownload() { com.hiddenoutlier.launcher.assistant.ModelDownloadService.cancel(getApplication()) }
    fun deleteModel(m: com.hiddenoutlier.launcher.assistant.ModelInfo) {
        models.delete(m)
        if (settings.value.modelId == m.id) save { la.settings.setModelId(null) }
    }
    fun useModel(m: com.hiddenoutlier.launcher.assistant.ModelInfo) = save { la.settings.setModelId(m.id) }
    fun setAssistant(v: Boolean) = save { la.settings.setAssistant(v) }
    fun setOnline(v: Boolean) = save { la.settings.setOnline(v) }
    fun setSource(src: com.hiddenoutlier.launcher.platform.OnlineSource, on: Boolean) = save { la.settings.setSource(src, on) }
    fun setHomePlace(v: String) = save { la.settings.setHomePlace(v) }
    fun setFahrenheit(v: Boolean) = save { la.settings.setFahrenheit(v) }
    fun setModelsOnMobileData(v: Boolean) = save { la.settings.setModelsOnMobileData(v) }
    fun setSpeak(v: com.hiddenoutlier.launcher.data.SpeakMode) = save { la.settings.setSpeak(v) }
    fun setTtsEngine(v: String?) = save { la.settings.setTtsEngine(v) }
    fun setTtsVoice(v: String?) = save { la.settings.setTtsVoice(v) }
    fun setTtsRate(v: Float) = save { la.settings.setTtsRate(v) }
    fun setTtsPitch(v: Float) = save { la.settings.setTtsPitch(v) }
    fun setListenOnAssist(v: Boolean) = save { la.settings.setListenOnAssist(v) }
    fun savePersonality(text: String) = viewModelScope.launch(Dispatchers.IO) { personality.save(text) }

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
    /** Pictures waiting for the crop step, in order. */
    val pendingCrops = MutableStateFlow<List<Uri>>(emptyList())
    var cropTotal = 0
        private set
    fun importBackgrounds(uris: List<Uri>) {
        if (uris.isEmpty()) return
        cropTotal = uris.size
        pendingCrops.value = uris
        _screen.value = Screen.CROP
    }
    /** Saves the current picture (cropped to [crop], or as it is when null) and moves to the next. */
    fun finishCrop(crop: android.graphics.RectF?) {
        val uri = pendingCrops.value.firstOrNull() ?: return
        viewModelScope.launch { backgrounds.importOne(uri, crop, cropTotal - pendingCrops.value.size) }
        skipCrop()
    }
    fun skipCrop() {
        pendingCrops.value = pendingCrops.value.drop(1)
        if (pendingCrops.value.isEmpty()) _screen.value = Screen.SETTINGS
    }
}
