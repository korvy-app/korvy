package app.korvy.launcher.assistant

import app.korvy.launcher.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.korvy.launcher.core.Action
import app.korvy.launcher.core.ActionType
import app.korvy.launcher.core.AgentCore
import app.korvy.launcher.core.Calc
import app.korvy.launcher.data.AppEntry
import app.korvy.launcher.data.AppRepository
import app.korvy.launcher.data.HiddenStore
import app.korvy.launcher.data.Settings
import app.korvy.launcher.data.SpeakMode
import app.korvy.launcher.data.UsageStore
import app.korvy.launcher.ui.ChatItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import app.korvy.launcher.LauncherApp
import app.korvy.launcher.core.AppSearch
import app.korvy.launcher.core.Lesson
import app.korvy.launcher.core.Speech
import app.korvy.launcher.platform.AppChoices
import app.korvy.launcher.platform.AppTask

/**
 * The assistant, shared app-wide: the home screen's assistant screen and the power-button panel
 * (AssistActivity) show the same conversation. Order per request: maths in code, common commands in code, then
 * the user's chosen model (grammar-constrained to one action). Results are formatted in code and optionally spoken.
 */
class AssistantController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: StateFlow<Settings>,
    private val repo: AppRepository,
    private val privacy: HiddenStore,
    private val usage: UsageStore,
    val models: ModelStore,
    val personality: Personality,
) {
    val chat = MutableStateFlow<List<ChatItem>>(emptyList())
    val busy = MutableStateFlow(false)
    /** The reply being spoken (by its 🔊 button or because spoken replies are on); null when none. */
    val readingAloud = MutableStateFlow<Long?>(null)
    /** Set when a request opened another app (the power-button panel closes itself then). */
    val leftForApp = MutableStateFlow(0)
    val speaker = Speaker(context)
    private val client = AssistantClient(context, scope)
    val voices: VoiceStore = (context.applicationContext as LauncherApp).voices
    private val pro get() = (context.applicationContext as LauncherApp).pro
    private val phoneRecognizer by lazy { VoiceInput(context) }
    private val whisper by lazy { WhisperInput(context, client, scope) }

    /**
     * The speech recognizer to use now: the Whisper model chosen in Settings → Voice, else the phone's on-device
     * recognizer, else any downloaded Whisper model.
     */
    val speechInput: SpeechInput = object : SpeechInput {
        private var current: SpeechInput? = null
        private fun pick(): SpeechInput? {
            val installed = if (pro.unlocked.value) models.installed.value else emptySet() // Whisper is Pro
            val chosen = ModelCatalog.byId(settings.value.sttModel)?.takeIf { it.id in installed }
                ?: if (phoneRecognizer.available()) null else ModelCatalog.speech.firstOrNull { it.id in installed }
            if (chosen == null) return phoneRecognizer.takeIf { it.available() }
            whisper.model = chosen; whisper.modelFile = models.fileFor(chosen)
            return whisper
        }
        override fun available() = pick() != null
        override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit) {
            val s = pick() ?: return onError(t(R.string.c_no_offline_stt))
            current = s; s.start(onPartial, onFinal, onError, onLevel)
        }
        override fun stop() { current?.stop() }
        override fun cancel() { current?.cancel() }
    }

    init {
        speaker.neural = object : Speaker.Neural {
            override fun say(engine: String, voice: String?, speed: Float, text: String, onStart: () -> Unit, onDone: () -> Unit): Boolean {
                if (!pro.unlocked.value) return false // natural voices are Pro: fall back to the phone's voice
                val pack = NeuralVoiceCatalog.voiceById(engine.removePrefix(Speaker.NEURAL_PREFIX)) ?: return false
                if (pack.id !in voices.installed.value) return false
                val speakerId = voice?.toIntOrNull() ?: pack.speakers.firstOrNull()?.id ?: return false
                client.speak(voices.folderFor(pack).path, pack.kind.name, pack.modelFile, speakerId, speed, text, onStart, onDone)
                return true
            }
            override fun stop() = client.stopSpeaking()
        }
    }
    init {
        // The reply's button goes back to 🔊 once nothing is queued and the reply has finished arriving.
        scope.launch {
            kotlinx.coroutines.flow.combine(speaker.state, busy) { st, b -> st == Speaker.State.IDLE && !b }
                .collect { idle -> if (idle) readingAloud.value = null }
        }
    }
    private var nextId = 0L
    private var job: Job? = null

    private suspend fun visibleApps(): List<AppEntry> {
        if (repo.apps.value.isEmpty()) repo.refresh()
        val hidden = withContext(Dispatchers.IO) { privacy.load().hidden.keys } // the assistant never sees hidden apps
        return repo.apps.value.filter { it.key !in hidden }
    }

    private var appsCache: List<AppEntry> = emptyList()
    val contacts = Contacts(context)
    val shortcuts = PeopleShortcuts(context)
    val notebook = NotebookStore(context)
    private val tools = Tools(
        context,
        apps = { appsCache },
        launch = { app ->
            runCatching { repo.launch(app) }
            scope.launch(Dispatchers.IO) { usage.record(app.key) }
            leftForApp.value++
        },
        webSearch = { q ->
            val s = settings.value
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.engine.url + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            leftForApp.value++
        },
        allowed = { settings.value.allows(it) },
        homePlace = { settings.value.homePlace },
        fahrenheit = { settings.value.fahrenheit },
        contacts = contacts,
        opened = { leftForApp.value++ },
        useLocation = { settings.value.useLocation },
        shortcuts = shortcuts,
        notebook = notebook,
        appFor = { t -> AppChoices.chosen(context, settings.value.appChoices, t) },
    )

    private fun update(id: Long, f: (ChatItem) -> ChatItem) { chat.value = chat.value.map { if (it.id == id) f(it) else it } }

    private fun speaking(byVoice: Boolean): Boolean {
        val s = settings.value
        speaker.configure(Speaker.Config(s.ttsEngine, s.ttsVoice, s.ttsRate, s.ttsPitch))
        return s.speak == SpeakMode.ALWAYS || (s.speak == SpeakMode.AFTER_VOICE && byVoice)
    }

    /** The screen the user called the assistant over (power button), for requests about "this"; null otherwise. */
    val screen = MutableStateFlow<app.korvy.launcher.core.ScreenContext?>(null)

    fun ask(text: String, byVoice: Boolean = false) {
        val q = text.trim()
        if (q.isEmpty() || busy.value) return
        stopSpeaking()
        val speak = speaking(byVoice)
        chat.value = chat.value + ChatItem(nextId++, true, q)
        val replyId = nextId++
        if (speak) readingAloud.value = replyId
        chat.value = chat.value + ChatItem(replyId, false, "", pending = true)
        busy.value = true
        job = scope.launch {
            try {
                // 0. About the screen the user called the assistant over ("text this to Jen", "summarise this"):
                //    the model reads the screen's text with the question.
                screen.value?.takeIf { app.korvy.launcher.core.ScreenText.refersToScreen(q) }?.let { sc ->
                    runOne(q, replyId, speak, sc)
                    return@launch
                }
                // 1. Teaching ("remember …", "when I say …", "forget …"): plain code, no model.
                val lessonFor = parseLesson(q)
                lessonFor?.takeIf { !pro.unlocked.value }?.let {
                    update(replyId) { it.copy(text = context.getString(R.string.assistant_needs_pro_teach), pending = false,
                        card = Card(Card.Kind.PRO, context.getString(R.string.pro_locked_title), context.getString(R.string.pro_feature_teach))) }
                    return@launch
                }
                lessonFor?.let { lesson ->
                    val r = learn(lesson)
                    update(replyId) { it.copy(text = r.text, lines = r.lines, pending = false) }
                    if (speak) speaker.sayAll(r.spoken ?: Speech.forSpeech(r.text, r.lines))
                    return@launch
                }
                // 2. A phrase the user taught: run its steps in order, each with its own reply.
                val phrase = if (pro.unlocked.value) notebook.book.value.phraseFor(q) else null
                if (phrase != null) {
                    phrase.steps.forEachIndexed { i, step ->
                        val id = if (i == 0) replyId else nextId++.also { chat.value = chat.value + ChatItem(it, false, "", pending = true) }
                        runOne(step, id, speak)
                    }
                } else runOne(q, replyId, speak)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failing lesson or phrase must not leave the assistant stuck.
                android.util.Log.w("Assistant", "request failed", e)
                update(replyId) { it.copy(text = t(R.string.c_sorry_error, e.message ?: t(R.string.c_didnt_work)), pending = false) }
            } finally {
                busy.value = false
            }
        }
    }

    /** One request: maths, a quick command, or the model; then the tool it picked. */
    private suspend fun runOne(q: String, replyId: Long, speak: Boolean, onScreen: app.korvy.launcher.core.ScreenContext? = null) {
            var streamed = false
            try {
                val action = (if (onScreen != null) null else calc(q)?.let { Action(ActionType.ANSWER, it) } ?: parseCommand(q))
                    ?: modelAction(q, replyId, speak, onScreen).also { streamed = it?.type == ActionType.ANSWER }
                        ?.let { a -> if (AgentCore.grounded(a, q)) a else Action(ActionType.ANSWER, t(R.string.c_not_sure)) }
                if (action == null) { if (speak) speaker.say(chat.value.last { it.id == replyId }.text); return }
                update(replyId) { it.copy(actionJson = AgentCore.toJson(action)) }
                if (action.type == ActionType.OPEN_APP) appsCache = visibleApps()
                if (action.type != ActionType.ANSWER) update(replyId) { it.copy(text = "…") }
                val r = tools.run(action)
                update(replyId) { it.copy(text = r.text, lines = r.lines, link = r.link, card = r.card, lineLinks = r.lineLinks, spoken = r.spoken, pending = false) }
                if (speak) {
                    // Say the details too (forecast, headlines), not just the first line.
                    val words = r.card?.let { c -> "${c.title}?" } ?: r.spoken ?: Speech.forSpeech(r.text, r.lines)
                    if (streamed) speaker.endStream(r.text) else speaker.sayAll(words)
                }
            } catch (e: CancellationException) {
                update(replyId) { it.copy(text = it.text.ifBlank { t(R.string.c_stopped) }, pending = false) }
                throw e
            } catch (e: Exception) {
                update(replyId) { it.copy(text = t(R.string.c_sorry_error, e.message ?: t(R.string.c_didnt_work)), pending = false) }
            }
    }

    /** Applies something the user taught. People go to the encrypted shortcuts; the rest to the notebook. */
    private suspend fun learn(lesson: Lesson): ToolResult {
        val nb = notebook.book.value
        val full = t(R.string.c_notebook_full)
        return when (lesson) {
            is Lesson.Note ->
                if (notebook.save(nb.addNote(lesson.text))) ToolResult(t(R.string.c_noted, lesson.text)) else ToolResult(full)
            is Lesson.NewPhrase -> {
                val unknown = lesson.steps.filter { calc(it) == null && parseCommand(it) == null && notebook.book.value.phraseFor(it) == null }
                if (!notebook.save(nb.addPhrase(lesson.trigger, lesson.steps))) ToolResult(full)
                else ToolResult(t(R.string.c_phrase_saved, lesson.trigger), lesson.steps.map { "• $it" } +
                    (if (unknown.isEmpty()) emptyList() else listOf(t(R.string.c_phrase_model, unknown.joinToString(", ")))),
                    spoken = t(R.string.c_phrase_spoken, lesson.trigger, lesson.steps.joinToString(t(R.string.c_then))))
            }
            is Lesson.Alias -> alias(lesson.word, lesson.target, full)
            is Lesson.Forget -> {
                val (after, n) = nb.forget(lesson.what)
                val person = shortcuts.find(lesson.what)?.let { shortcuts.remove(shortcuts.all.value.first { s -> AppSearch.normalize(s.alias) == AppSearch.normalize(lesson.what) }.alias); 1 } ?: 0
                notebook.save(after)
                ToolResult(if (n + person == 0) t(R.string.c_nothing_about, lesson.what) else t(R.string.c_forgotten))
            }
            Lesson.Show -> {
                val lines = nb.notes.map { "• $it" } + nb.phrases.map { "• “${it.trigger}” → ${it.steps.joinToString("; ")}" } +
                    shortcuts.all.value.map { "• “${it.alias}” → ${it.person.name}" }
                if (lines.isEmpty()) ToolResult(t(R.string.c_nothing_yet))
                else ToolResult(t(R.string.c_remember_list), lines, spoken = context.resources.getQuantityString(R.plurals.c_remember_count, lines.size, lines.size))
            }
        }
    }

    /** "When I say Jen I mean Jennifer Lopez": a contact → people shortcut; an app → a phrase that opens it; else a note. */
    private suspend fun alias(word: String, target: String, full: String): ToolResult {
        if (contacts.granted()) {
            val people = contacts.candidates(target)
            val exact = people.filter { AppSearch.normalize(it.name) == AppSearch.normalize(target) }
            val p = exact.singleOrNull() ?: people.singleOrNull()
            if (p != null) { shortcuts.set(word, p); return ToolResult(t(R.string.c_alias_person, word, p.name)) }
        }
        val apps = visibleApps()
        val app = apps.maxByOrNull { AppSearch.score(target, it.label) }
            ?.takeIf { AppSearch.score(target, it.label) >= 800 }
        if (app != null) return if (notebook.save(notebook.book.value.addPhrase(word, listOf("open ${app.label}"))))
            ToolResult(t(R.string.c_alias_app, word, app.label)) else ToolResult(full)
        return if (notebook.save(notebook.book.value.addNote("When I say “$word”, I mean $target")))
            ToolResult(t(R.string.c_alias_note, word, target)) else ToolResult(full)
    }

    private suspend fun modelAction(q: String, replyId: Long, speak: Boolean, onScreen: app.korvy.launcher.core.ScreenContext? = null): Action? {
        val s = settings.value
        if (!pro.unlocked.value) {
            update(replyId) { it.copy(pending = false, text = context.getString(R.string.assistant_needs_pro_ai),
                card = Card(Card.Kind.PRO, context.getString(R.string.pro_locked_title), context.getString(R.string.pro_feature_ai))) }
            return null
        }
        val m = ModelCatalog.chatById(s.modelId)
        if (m == null || m.id !in models.installed.value) {
            update(replyId) { it.copy(pending = false, text = t(R.string.c_need_model)) }
            return null
        }
        // The prompt starts identically every time (system prompt, examples, earlier turns as they were sent), so the
        // model process reuses what it already read (warmUp reads the fixed part ahead) and only reads the new question.
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, h:mm a", java.util.Locale.ENGLISH))
        val (roles, contents) = fixedMessages()
        // Only the notes that matter for this question, sent with it (not in the system prompt, so the model can
        // keep reusing its cached work for the unchanged start of the prompt).
        val notes = notebook.book.value.notesFor(q)
        val prefix = (if (notes.isEmpty()) "(It's $now.)" else "(It's $now. Keep in mind: ${notes.joinToString(" ")})") +
            (onScreen?.let { "\n" + app.korvy.launcher.core.ScreenText.forPrompt(it) } ?: "")
        sentAs[replyId - 1] = "$prefix $q"
        AgentCore.historyMessages(history(excludeFrom = replyId - 1), "$prefix $q").forEach { (r, c) -> roles += r; contents += c }
        val sb = StringBuilder()
        if (speak) speaker.beginStream()
        // Watchdog: if nothing at all happens for a while (no status, no text), stop instead of "thinking" forever.
        var lastProgress = System.currentTimeMillis()
        val watchdog = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                if (System.currentTimeMillis() - lastProgress > STALL_MS) { client.stop(); break }
            }
        }
        val out = try {
            client.complete(
                models.fileFor(m).path, roles, contents, AgentCore.GRAMMAR, maxTokens = 220, temp = 0.2f,
                onStatus = { st -> lastProgress = System.currentTimeMillis(); update(replyId) { it.copy(text = st) } },
                onToken = { piece ->
                    lastProgress = System.currentTimeMillis()
                    sb.append(piece)
                    AgentCore.partialAnswer(sb.toString())?.let { t ->
                        update(replyId) { it.copy(text = t) }
                        if (speak) speaker.stream(t)
                    }
                },
            )
        } finally { watchdog.cancel() }
        if (System.currentTimeMillis() - lastProgress > STALL_MS && AgentCore.parse(out) == null) {
            return Action(ActionType.ANSWER, t(R.string.c_too_long))
        }
        return AgentCore.parse(out) ?: Action(ActionType.ANSWER, t(R.string.c_confused))
    }

    /** Earlier finished exchanges (user question + assistant action), oldest first, before item [excludeFrom]. */
    private fun history(excludeFrom: Long): List<AgentCore.Turn> {
        val items = chat.value.filter { it.id < excludeFrom }
        val turns = ArrayList<AgentCore.Turn>()
        for (i in 0 until items.size - 1) {
            val u = items[i]; val a = items[i + 1]
            if (u.fromUser && !a.fromUser && a.actionJson != null && !a.pending) {
                val wasTool = AgentCore.parse(a.actionJson)?.type?.let { it != ActionType.ANSWER } ?: false
                turns += AgentCore.Turn(sentAs[u.id] ?: u.text, a.actionJson, (listOf(a.text) + a.lines).joinToString(" "), wasTool)
            }
        }
        return turns
    }

    /** Short confirmation when the user turns spoken replies on (also proves the volume is up). */
    /** LOADING / SPEAKING for [readingAloud]'s reply: its button shows a spinner, then Stop. */
    val speech get() = speaker.state

    /** Reads one reply aloud on request, whatever the "speak replies" setting. Tapping it again stops it. */
    fun readAloud(id: Long) {
        val item = chat.value.firstOrNull { it.id == id } ?: return
        val wasThis = readingAloud.value == id
        stopSpeaking()
        if (wasThis) return
        val s = settings.value
        speaker.configure(Speaker.Config(s.ttsEngine, s.ttsVoice, s.ttsRate, s.ttsPitch))
        readingAloud.value = id
        speaker.sayAll(item.spoken ?: Speech.forSpeech(item.text, item.lines))
        warnIfQuiet()
    }

    fun stopSpeaking() { speaker.stop(); readingAloud.value = null }

    fun sayHello() {
        val s = settings.value
        speaker.configure(Speaker.Config(s.ttsEngine, s.ttsVoice, s.ttsRate, s.ttsPitch))
        speaker.stop(); speaker.say(t(R.string.c_read_aloud_on))
        warnIfQuiet()
    }

    /** Spoken replies use the media volume; say so on screen if it's too low to hear. */
    fun warnIfQuiet() {
        if (speaker.volumeLow()) android.widget.Toast.makeText(context,
            t(R.string.c_volume_low), android.widget.Toast.LENGTH_LONG).show()
    }

    /** Called when the assistant opens: load the chosen model now so the first answer starts sooner. */
    fun warmUp() {
        val m = ModelCatalog.chatById(settings.value.modelId) ?: return
        if (m.id !in models.installed.value || !pro.unlocked.value) return
        scope.launch {
            val (roles, contents) = fixedMessages()
            client.preload(models.fileFor(m).path, roles, contents, models.stateFor(m).path)
        }
    }

    /** Questions as the model saw them (with the time and notes), so earlier turns are replayed word for word. */
    private val sentAs = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** The part of the prompt that's the same for every question: system prompt and examples. */
    private suspend fun fixedMessages(): Pair<ArrayList<String>, ArrayList<String>> {
        val s = settings.value
        val roles = ArrayList<String>(); val contents = ArrayList<String>()
        val apps = AppTask.entries.mapNotNull { t ->
            AppChoices.chosen(context, s.appChoices, t)?.let { "${t.title}: ${AppChoices.label(context, it)}" }
        }.joinToString("; ")
        roles += "system"; contents += AgentCore.systemPrompt(withContext(Dispatchers.IO) { personality.load() }, s.homePlace.ifBlank { null }, apps, userLanguage())
        AgentCore.EXAMPLES.forEach { (u, a) -> roles += "user"; contents += u; roles += "assistant"; contents += a }
        return roles to contents
    }

    fun stop() { client.stop(); stopSpeaking(); job?.cancel() }

    // ---- Confirmation cards ---------------------------------------------------------------------------------
    private fun cardOf(id: Long) = chat.value.firstOrNull { it.id == id }?.card

    /** The user tapped Send / Call / Open on a card (with the message as edited). */
    fun confirm(id: Long, message: String?) {
        val c = cardOf(id) ?: return
        val status = c.run(message)
        update(id) { it.copy(card = null, text = status) }
    }

    fun cancel(id: Long) = update(id) { it.copy(card = null, text = t(R.string.c_cancelled)) }

    /** A contact was chosen in the picker for a "who should I text/call?" card. */
    fun withContact(id: Long, p: Person) {
        val c = cardOf(id) ?: return
        val r = c.withContact(p)
        update(id) { it.copy(card = r.card, text = r.text) }
    }

    /** The user picked one of several matching people; [remember] saves them as the default for that name. */
    fun choose(id: Long, p: Person, remember: Boolean) {
        val c = cardOf(id) ?: return
        if (remember) c.alias?.let { shortcuts.set(it, p) }
        val r = c.withContact(p)
        update(id) { it.copy(card = r.card, text = r.text) }
    }

    /** Contacts permission was just granted: try that request again. */
    fun retry(id: Long) {
        val item = chat.value.firstOrNull { it.id == id } ?: return
        val action = item.actionJson?.let { AgentCore.parse(it) } ?: return
        scope.launch {
            val r = tools.run(action)
            update(id) { it.copy(text = r.text, lines = r.lines, card = r.card, lineLinks = r.lineLinks) }
            if (speaking(byVoice = false)) {
                stopSpeaking(); readingAloud.value = id
                speaker.sayAll(r.card?.let { c -> "${c.title}?" } ?: r.spoken ?: Speech.forSpeech(r.text, r.lines))
            }
        }
    }
    fun clear() { if (!busy.value) { chat.value = emptyList(); sentAs.clear() } }

    companion object { const val STALL_MS = 90_000L }

    // Plain-code understanding: English first, then Spanish rewritten to the English form (core/Spanish).
    private fun parseCommand(q: String) = AgentCore.quickParse(q) ?: app.korvy.launcher.core.Spanish.toEnglish(q)?.let { AgentCore.quickParse(it) }
    private fun parseLesson(q: String) = Lesson.parse(q) ?: app.korvy.launcher.core.Spanish.toEnglish(q)?.let { Lesson.parse(it) }
    private val calcWords = Calc.Words(
        percentOf = { p, n, r -> t(R.string.calc_percent, p, n, r) },
        tip = { p, b, tip, total -> t(R.string.calc_tip, p, b, tip, total) },
    )
    private fun calc(q: String) = Calc.answer(q, calcWords) ?: app.korvy.launcher.core.Spanish.toEnglish(q)?.let { Calc.answer(it, calcWords) }

    /** The phone's (or Korvy's per-app) language in English, e.g. "Spanish"; null for English. */
    private fun userLanguage(): String? = context.resources.configuration.locales[0].takeIf { it.language != "en" }
        ?.getDisplayLanguage(java.util.Locale.ENGLISH)

    private fun t(id: Int, vararg a: Any): String = if (a.isEmpty()) context.getString(id) else context.getString(id, *a)
}
