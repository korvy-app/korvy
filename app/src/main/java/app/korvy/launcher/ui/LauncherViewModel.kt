package app.korvy.launcher.ui

import kotlinx.coroutines.flow.first
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.korvy.launcher.LauncherApp
import app.korvy.launcher.core.AppSearch
import app.korvy.launcher.core.DeviceTiers
import app.korvy.launcher.core.Suggest
import app.korvy.launcher.core.Tier
import app.korvy.launcher.data.AppEntry
import app.korvy.launcher.data.HideLevel
import app.korvy.launcher.data.Privacy
import app.korvy.launcher.data.SearchEngine
import app.korvy.launcher.data.Settings
import app.korvy.launcher.platform.Device
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
import app.korvy.launcher.assistant.Card
import app.korvy.launcher.assistant.ModelDownloadService
import app.korvy.launcher.assistant.ModelInfo
import app.korvy.launcher.assistant.NeuralVoiceCatalog
import app.korvy.launcher.assistant.NeuralVoicePack
import app.korvy.launcher.assistant.Person
import app.korvy.launcher.assistant.Speaker
import app.korvy.launcher.core.HomeGrid
import app.korvy.launcher.data.SpeakMode
import app.korvy.launcher.platform.AppTask
import app.korvy.launcher.platform.Net
import app.korvy.launcher.platform.OnlineSource

enum class Screen { HOME, DRAWER, SETTINGS, HIDDEN, TUTORIAL, STATS, ASSISTANT, MODELS, PERSONALITY, CROP, VOICE, PEOPLE, APPS, WIDGETS, NOTEBOOK, ABOUT, DEVELOPER }

/** One line of the assistant conversation. */
data class ChatItem(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val lines: List<String> = emptyList(),
    val link: String? = null,
    val pending: Boolean = false,
    /** For assistant replies: the action that produced it, as the model's JSON (used as chat history). */
    val actionJson: String? = null,
    /** A confirmation (or contact question) waiting for the user. */
    val card: Card? = null,
    /** A web page per detail line (e.g. each headline's article), or null. */
    val lineLinks: List<String?> = emptyList(),
    /** What to say when read aloud, when different from the text plus lines. */
    val spoken: String? = null,
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
    val homeGridStored: StateFlow<HomeGrid> =
        la.settings.homeGrid.stateIn(viewModelScope, SharingStarted.Eagerly, HomeGrid())
    val homeGrid: StateFlow<HomeGrid> = combine(homeGridStored, visibleApps) { g, v ->
        if (v.isEmpty()) g else g.keepOnly(v.mapTo(HashSet()) { it.key })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeGrid())

    private fun updateGrid(f: (HomeGrid) -> HomeGrid) {
        viewModelScope.launch { la.settings.setHomeGrid(f(homeGridStored.value).compact()) } // empty pages close
    }
    fun placeOnHome(app: AppEntry, target: Int, from: Int?, combine: Boolean = false) {
        widgetId(app)?.let { moveWidget(it, target); return }
        updateGrid { it.place(app.key, target, from, combine) }
    }

    // ---- Widgets on the home grid --------------------------------------------------------------------------

    /** A dragged widget travels as a stand-in AppEntry with key "widget:<id>". */
    fun widgetId(app: AppEntry): Int? = app.key.takeIf { it.startsWith(WIDGET) }?.removePrefix(WIDGET)?.toIntOrNull()

    /** Dropped with the finger on [cell]: the widget's middle row goes there. */
    fun moveWidget(id: Int, cell: Int) = updateGrid { g ->
        val rows = g.widgets[id]?.rows ?: return@updateGrid g
        val local = cell % g.pageSize
        g.placeWidget(id, cell / g.pageSize, local / g.cols - (rows - 1) / 2, rows)
    }
    fun resizeWidget(id: Int, rows: Int) = updateGrid { it.resizeWidget(id, rows) }

    /** Keeps the grid's widgets in step with the widgets Android hosts for us (added, removed, uninstalled). */
    private fun syncWidgets(placed: List<app.korvy.launcher.data.PlacedWidget>) = viewModelScope.launch {
        val store = la.widgets
        val g0 = la.settings.homeGrid.first() // the saved layout, never the empty default before it loads
        var g = g0
        val ids = placed.mapTo(HashSet()) { it.id }
        g.widgets.keys.filter { it !in ids }.forEach { g = g.removeWidget(it) }
        val moving = !store.movedIntoGrid // first run since widgets moved into the grid: keep them on top of page 1
        var nextRow = 0
        for (w in placed.filter { it.id !in g.widgets }) {
            val rows = store.startRows(w)
            g = if (moving && nextRow + rows <= g.rows) g.placeWidget(w.id, 0, nextRow, rows).also { nextRow += rows }
                else g.addWidget(w.id, rows)
        }
        if (moving) store.markMovedIntoGrid()
        if (g != g0) la.settings.setHomeGrid(g.compact())
    }

    /** The folder open on the home screen (its id), or null. */
    val openFolder = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun renameFolder(id: String, name: String) = updateGrid { it.renameFolder(id, name) }
    /** Ungroup: every app goes back on the grid where there's room (the rest stay in the folder). */
    fun ungroup(folderKey: String) = updateGrid { g ->
        var out = g
        g.folder(folderKey)?.keys?.forEach { k -> out = out.takeOutOfFolder(k) }
        out
    }
    fun addToHome(app: AppEntry): Boolean {
        val g = homeGridStored.value
        if (!g.add(app.key).contains(app.key)) return false // every page full and no room for another
        updateGrid { it.add(app.key) }; return true
    }
    fun removeFromHome(app: AppEntry) {
        widgetId(app)?.let { la.widgets.remove(it); return } // the grid follows through syncWidgets
        updateGrid { it.remove(app.key) }
    }
    fun isOnHome(app: AppEntry) = homeGridStored.value.contains(app.key)

    /** A drag that started in the app list: show the home screen underneath, keep the list (and the
     *  gesture) alive until the drop. */
    fun startDragFromList() { _screen.value = Screen.HOME }
    fun endDrag() { query.value = "" }

    /** Suggestions from the user's search engine for the search box (opt-in source; nothing is fetched unless it's on). */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val webSuggestions: StateFlow<List<String>> = combine(query, settings) { q, s -> q to s }
        .debounce(250)
        .mapLatest { (q, s) ->
            if (q.isBlank() || q.length > 100 || !s.allows(OnlineSource.SUGGESTIONS)) emptyList()
            else runCatching {
                val body = Net.getText(OnlineSource.SUGGESTIONS,
                    s.engine.suggestions + Uri.encode(q)) { settings.value.allows(it) }
                val arr = org.json.JSONArray(body).getJSONArray(1)
                (0 until arr.length()).map { arr.getString(it) }.filter { !it.equals(q, ignoreCase = true) }.take(5)
            }.getOrElse { emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Contacts matching the search (on the phone; only with the permission and the setting on). */
    val contactsGranted = MutableStateFlow(false)
    fun refreshContactsGranted() { contactsGranted.value = la.assistant.contacts.granted() }
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val contactResults: StateFlow<List<Person>> = combine(query, settings, contactsGranted) { q, s, g -> Triple(q, s, g) }
        .debounce(120)
        .mapLatest { (q, s, g) ->
            if (q.isBlank() || !g || !s.contactsInSearch) emptyList()
            else withContext(Dispatchers.IO) { la.assistant.contacts.find(q, 4).distinctBy { it.name } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun setContactsInSearch(v: Boolean) = save { la.settings.setContactsInSearch(v) }
    fun setUseLocation(v: Boolean) = save { la.settings.setUseLocation(v) }
    fun setAppChoice(task: AppTask, pkg: String?) = save { la.settings.setAppChoice(task.name, pkg) }
    fun dial(p: Person) { leftForApp = true; runCatching {
        getApplication<Application>().startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    fun textTo(p: Person) { leftForApp = true; runCatching {
        getApplication<Application>().startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

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
    fun open(s: Screen) { closeInstantly.value = false; _screen.value = s; if (s != Screen.HIDDEN) lockedListOpen.value = false }
    fun openSearch() { open(Screen.DRAWER); focusSearch.value++ }
    /** True when the last close should be instant (coming back from an app): no closing animation to tap through. */
    val closeInstantly = MutableStateFlow(false)
    /** Bumped when Home is pressed while the home screen is already showing: back to the first page. */
    val firstPageRequests = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun goHome(instant: Boolean = false, pressedOnHome: Boolean = false) {
        if (pressedOnHome && _screen.value == Screen.HOME && openFolder.value == null) firstPageRequests.value++
        // Only a close the user can see (from the list itself) animates. If the home screen is already the target
        // (e.g. coming back from an app), keep an earlier "instant" request: no frames have been drawn since.
        if (instant) closeInstantly.value = true else if (_screen.value != Screen.HOME) closeInstantly.value = false
        _screen.value = Screen.HOME; query.value = ""; lockedListOpen.value = false; openFolder.value = null; refreshSuggestions()
    }

    private var leftForApp = false

    /** Called when the launcher goes out of view. Only opening an app resets it to the home screen;
     *  the photo picker, lock prompt and system dialogs come back to where the user was. */
    fun onStopped() { if (leftForApp) { leftForApp = false; goHome(instant = true) } }

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
    val pro get() = la.pro
    /** A Pro feature the user just tried to use (shows ProDialog), or null. */
    val proPrompt = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** True if Pro (or the trial) is on; otherwise asks with ProDialog and returns false. */
    fun requirePro(what: String): Boolean = pro.unlocked.value.also { if (!it) proPrompt.value = what }
    val widgets get() = la.widgets
    /** Packages of hidden apps (their widgets aren't offered). */
    fun hiddenPackages(): Set<String> = la.privacy.load().hidden.keys.map { it.substringBefore('/') }.toSet()
    val personality = la.personality
    val chat = assistant.chat
    val assistantBusy = assistant.busy

    fun openAssistant(question: String? = null) {
        _screen.value = Screen.ASSISTANT
        query.value = ""
        assistant.warmUp()
        if (!question.isNullOrBlank()) ask(question)
    }

    fun ask(text: String, byVoice: Boolean = false) = assistant.ask(text, byVoice)
    fun stopAssistant() = assistant.stop()
    fun clearChat() = assistant.clear()

    /** Runs in a foreground service so it survives the screen turning off (see ModelDownloadService). */
    fun downloadModel(m: ModelInfo) =
        ModelDownloadService.start(getApplication(), m)
    fun cancelDownload() { ModelDownloadService.cancel(getApplication()) }
    fun deleteModel(m: ModelInfo) {
        models.delete(m)
        if (settings.value.sttModel == m.id) setSttModel(null)
        if (settings.value.modelId == m.id) save { la.settings.setModelId(null) }
    }
    fun useModel(m: ModelInfo) = save { la.settings.setModelId(m.id) }
    fun setAssistant(v: Boolean) = save { la.settings.setAssistant(v) }
    fun setOnline(v: Boolean) = save { la.settings.setOnline(v) }
    fun enableSuggestions() = save { la.settings.enableSuggestionsOnly() }
    fun dismissSuggestOffer() = save { la.settings.dismissSuggestOffer() }
    fun setSource(src: OnlineSource, on: Boolean) = save { la.settings.setSource(src, on) }
    fun setHomePlace(v: String) = save { la.settings.setHomePlace(v) }
    fun setFahrenheit(v: Boolean) = save { la.settings.setFahrenheit(v) }
    fun setModelsOnMobileData(v: Boolean) = save { la.settings.setModelsOnMobileData(v) }
    val voices = la.voices
    fun downloadVoice(p: NeuralVoicePack) =
        ModelDownloadService.startVoice(getApplication(), p)
    fun deleteVoice(p: NeuralVoicePack) {
        voices.delete(p)
        if (settings.value.ttsEngine == Speaker.NEURAL_PREFIX + p.id) setTtsEngine(null)
    }
    fun setSpeak(v: SpeakMode) = save { la.settings.setSpeak(v) }
    fun setTtsEngine(v: String?) = save { la.settings.setTtsEngine(v) }
    fun setSttModel(v: String?) = save { la.settings.setSttModel(v) }
    fun setFlag(f: app.korvy.launcher.core.Feature, on: Boolean) = save { la.settings.setFlag(f, on); la.syncWakeWord() }
    fun setDeveloper(v: Boolean) = save { la.settings.setDeveloper(v) }
    fun setOrientation(v: Int) = save { la.settings.setOrientation(v) }
    fun setWake(on: Boolean? = null, phrase: String? = null, sensitivity: Int? = null, screenOnly: Boolean? = null) =
        save { la.settings.setWake(on, phrase, sensitivity, screenOnly); la.syncWakeWord() }
    fun downloadWakeModel() = ModelDownloadService.startVoice(getApplication(), NeuralVoiceCatalog.wakeWord)
    fun setTtsVoice(v: String?) = save { la.settings.setTtsVoice(v) }
    fun setTtsRate(v: Float) = save { la.settings.setTtsRate(v) }
    fun setTtsPitch(v: Float) = save { la.settings.setTtsPitch(v) }
    fun setListenOnAssist(v: Boolean) = save { la.settings.setListenOnAssist(v) }
    fun setReadScreen(v: Boolean) = save { la.settings.setReadScreen(v) }
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

    init { viewModelScope.launch { la.widgets.placed.collect { syncWidgets(it) } } }

    companion object { const val WIDGET = "widget:" }
}
