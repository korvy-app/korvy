package com.hiddenoutlier.launcher.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hiddenoutlier.launcher.core.Action
import com.hiddenoutlier.launcher.core.ActionType
import com.hiddenoutlier.launcher.core.AgentCore
import com.hiddenoutlier.launcher.core.Calc
import com.hiddenoutlier.launcher.data.AppEntry
import com.hiddenoutlier.launcher.data.AppRepository
import com.hiddenoutlier.launcher.data.HiddenStore
import com.hiddenoutlier.launcher.data.Settings
import com.hiddenoutlier.launcher.data.SpeakMode
import com.hiddenoutlier.launcher.data.UsageStore
import com.hiddenoutlier.launcher.ui.ChatItem
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
    val voices: VoiceStore = (context.applicationContext as com.hiddenoutlier.launcher.LauncherApp).voices
    private val pro get() = (context.applicationContext as com.hiddenoutlier.launcher.LauncherApp).pro
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
            val s = pick() ?: return onError("No offline speech recognition yet: download a Whisper model in Settings → Voice.")
            current = s; s.start(onPartial, onFinal, onError, onLevel)
        }
        override fun stop() { current?.stop() }
        override fun cancel() { current?.cancel() }
    }

    init {
        speaker.neural = object : Speaker.Neural {
            override fun say(engine: String, voice: String?, speed: Float, text: String, onStart: () -> Unit, onDone: () -> Unit): Boolean {
                if (!pro.unlocked.value) return false // natural voices are Pro: fall back to the phone's voice
                val pack = NeuralVoiceCatalog.byId(engine.removePrefix(Speaker.NEURAL_PREFIX)) ?: return false
                if (pack.id !in voices.installed.value) return false
                val speakerId = voice?.toIntOrNull() ?: pack.speakers.first().id
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
        appFor = { t -> com.hiddenoutlier.launcher.platform.AppChoices.chosen(context, settings.value.appChoices, t) },
    )

    private fun update(id: Long, f: (ChatItem) -> ChatItem) { chat.value = chat.value.map { if (it.id == id) f(it) else it } }

    private fun speaking(byVoice: Boolean): Boolean {
        val s = settings.value
        speaker.configure(Speaker.Config(s.ttsEngine, s.ttsVoice, s.ttsRate, s.ttsPitch))
        return s.speak == SpeakMode.ALWAYS || (s.speak == SpeakMode.AFTER_VOICE && byVoice)
    }

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
                // 1. Teaching ("remember …", "when I say …", "forget …"): plain code, no model.
                com.hiddenoutlier.launcher.core.Lesson.parse(q)?.let { lesson ->
                    val r = learn(lesson)
                    update(replyId) { it.copy(text = r.text, lines = r.lines, pending = false) }
                    if (speak) speaker.sayAll(r.spoken ?: com.hiddenoutlier.launcher.core.Speech.forSpeech(r.text, r.lines))
                    return@launch
                }
                // 2. A phrase the user taught: run its steps in order, each with its own reply.
                val phrase = notebook.book.value.phraseFor(q)
                if (phrase != null) {
                    phrase.steps.forEachIndexed { i, step ->
                        val id = if (i == 0) replyId else nextId++.also { chat.value = chat.value + ChatItem(it, false, "", pending = true) }
                        runOne(step, id, speak)
                    }
                } else runOne(q, replyId, speak)
            } finally {
                busy.value = false
            }
        }
    }

    /** One request: maths, a quick command, or the model; then the tool it picked. */
    private suspend fun runOne(q: String, replyId: Long, speak: Boolean) {
            var streamed = false
            try {
                val action = Calc.answer(q)?.let { Action(ActionType.ANSWER, it) }
                    ?: AgentCore.quickParse(q)
                    ?: modelAction(q, replyId, speak).also { streamed = it?.type == ActionType.ANSWER }
                if (action == null) { if (speak) speaker.say(chat.value.last { it.id == replyId }.text); return }
                update(replyId) { it.copy(actionJson = AgentCore.toJson(action)) }
                if (action.type == ActionType.OPEN_APP) appsCache = visibleApps()
                if (action.type != ActionType.ANSWER) update(replyId) { it.copy(text = "…") }
                val r = tools.run(action)
                update(replyId) { it.copy(text = r.text, lines = r.lines, link = r.link, card = r.card, lineLinks = r.lineLinks, spoken = r.spoken, pending = false) }
                if (speak) {
                    // Say the details too (forecast, headlines), not just the first line.
                    val words = r.card?.let { c -> "${c.title}?" } ?: r.spoken ?: com.hiddenoutlier.launcher.core.Speech.forSpeech(r.text, r.lines)
                    if (streamed) speaker.endStream(r.text) else speaker.sayAll(words)
                }
            } catch (e: CancellationException) {
                update(replyId) { it.copy(text = it.text.ifBlank { "Stopped." }, pending = false) }
                throw e
            } catch (e: Exception) {
                update(replyId) { it.copy(text = "Sorry: ${e.message}", pending = false) }
            }
    }

    /** Applies something the user taught. People go to the encrypted shortcuts; the rest to the notebook. */
    private suspend fun learn(lesson: com.hiddenoutlier.launcher.core.Lesson): ToolResult {
        val nb = notebook.book.value
        val full = "My notebook is full. Tidy it in Settings → Assistant → Notebook first."
        return when (lesson) {
            is com.hiddenoutlier.launcher.core.Lesson.Note ->
                if (notebook.save(nb.addNote(lesson.text))) ToolResult("Got it. I'll keep in mind: ${lesson.text}.") else ToolResult(full)
            is com.hiddenoutlier.launcher.core.Lesson.NewPhrase -> {
                val unknown = lesson.steps.filter { Calc.answer(it) == null && AgentCore.quickParse(it) == null && notebook.book.value.phraseFor(it) == null }
                if (!notebook.save(nb.addPhrase(lesson.trigger, lesson.steps))) ToolResult(full)
                else ToolResult("Okay. When you say “${lesson.trigger}”, I'll:", lesson.steps.map { "• $it" } +
                    (if (unknown.isEmpty()) emptyList() else listOf("(I'll ask the model for: ${unknown.joinToString(", ")})")),
                    spoken = "Okay. When you say ${lesson.trigger}, I'll ${lesson.steps.joinToString(", then ")}.")
            }
            is com.hiddenoutlier.launcher.core.Lesson.Alias -> alias(lesson.word, lesson.target, full)
            is com.hiddenoutlier.launcher.core.Lesson.Forget -> {
                val (after, n) = nb.forget(lesson.what)
                val person = shortcuts.find(lesson.what)?.let { shortcuts.remove(shortcuts.all.value.first { s -> com.hiddenoutlier.launcher.core.AppSearch.normalize(s.alias) == com.hiddenoutlier.launcher.core.AppSearch.normalize(lesson.what) }.alias); 1 } ?: 0
                notebook.save(after)
                ToolResult(if (n + person == 0) "I don't have anything about “${lesson.what}”." else "Forgotten.")
            }
            com.hiddenoutlier.launcher.core.Lesson.Show -> {
                val lines = nb.notes.map { "• $it" } + nb.phrases.map { "• “${it.trigger}” → ${it.steps.joinToString("; ")}" } +
                    shortcuts.all.value.map { "• “${it.alias}” → ${it.person.name}" }
                if (lines.isEmpty()) ToolResult("Nothing yet. Try “remember I'm vegetarian” or “when I say goodnight, set a timer for 8 hours”.")
                else ToolResult("Here's what I remember (edit it in Settings → Assistant → Notebook):", lines, spoken = "I remember ${lines.size} things. They're listed on screen.")
            }
        }
    }

    /** "When I say Jen I mean Jennifer Lopez": a contact → people shortcut; an app → a phrase that opens it; else a note. */
    private suspend fun alias(word: String, target: String, full: String): ToolResult {
        if (contacts.granted()) {
            val people = contacts.candidates(target)
            val exact = people.filter { com.hiddenoutlier.launcher.core.AppSearch.normalize(it.name) == com.hiddenoutlier.launcher.core.AppSearch.normalize(target) }
            val p = exact.singleOrNull() ?: people.singleOrNull()
            if (p != null) { shortcuts.set(word, p); return ToolResult("Got it: “$word” means ${p.name}.") }
        }
        val apps = visibleApps()
        val app = apps.maxByOrNull { com.hiddenoutlier.launcher.core.AppSearch.score(target, it.label) }
            ?.takeIf { com.hiddenoutlier.launcher.core.AppSearch.score(target, it.label) >= 800 }
        if (app != null) return if (notebook.save(notebook.book.value.addPhrase(word, listOf("open ${app.label}"))))
            ToolResult("Got it: “$word” opens ${app.label}.") else ToolResult(full)
        return if (notebook.save(notebook.book.value.addNote("When I say “$word”, I mean $target")))
            ToolResult("Got it. When you say “$word”, I'll know you mean $target.") else ToolResult(full)
    }

    private suspend fun modelAction(q: String, replyId: Long, speak: Boolean): Action? {
        val s = settings.value
        val m = ModelCatalog.chatById(s.modelId)
        if (m == null || m.id !in models.installed.value) {
            update(replyId) { it.copy(pending = false, text = "To answer questions I need a model. Pick one in Settings → Assistant → Model. " +
                "Without one I can still open apps, set timers, do maths, and (with Online features on) get the weather, news and stock prices.") }
            return null
        }
        // The prompt starts identically every time (system prompt with today's date, examples, earlier turns), so the
        // model process can reuse what it already read and only process the new question.
        val today = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
        val time = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a"))
        val roles = ArrayList<String>(); val contents = ArrayList<String>()
        val apps = com.hiddenoutlier.launcher.platform.AppTask.entries.mapNotNull { t ->
            com.hiddenoutlier.launcher.platform.AppChoices.chosen(context, s.appChoices, t)?.let { "${t.title}: ${com.hiddenoutlier.launcher.platform.AppChoices.label(context, it)}" }
        }.joinToString("; ")
        roles += "system"; contents += AgentCore.systemPrompt(withContext(Dispatchers.IO) { personality.load() }, today, s.homePlace.ifBlank { null }, apps)
        AgentCore.EXAMPLES.forEach { (u, a) -> roles += "user"; contents += u; roles += "assistant"; contents += a }
        // Only the notes that matter for this question, sent with it (not in the system prompt, so the model can
        // keep reusing its cached work for the unchanged start of the prompt).
        val notes = notebook.book.value.notesFor(q)
        val prefix = if (notes.isEmpty()) "(It's $time.)" else "(It's $time. Keep in mind: ${notes.joinToString(" ")})"
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
            return Action(ActionType.ANSWER, "That was taking too long, so I stopped. A smaller model answers faster on this phone.")
        }
        return AgentCore.parse(out) ?: Action(ActionType.ANSWER, "Sorry, I got confused. Could you rephrase that?")
    }

    /** Earlier finished exchanges (user question + assistant action), oldest first, before item [excludeFrom]. */
    private fun history(excludeFrom: Long): List<AgentCore.Turn> {
        val items = chat.value.filter { it.id < excludeFrom }
        val turns = ArrayList<AgentCore.Turn>()
        for (i in 0 until items.size - 1) {
            val u = items[i]; val a = items[i + 1]
            if (u.fromUser && !a.fromUser && a.actionJson != null && !a.pending) {
                val wasTool = AgentCore.parse(a.actionJson)?.type?.let { it != ActionType.ANSWER } ?: false
                turns += AgentCore.Turn(u.text, a.actionJson, (listOf(a.text) + a.lines).joinToString(" "), wasTool)
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
        speaker.sayAll(item.spoken ?: com.hiddenoutlier.launcher.core.Speech.forSpeech(item.text, item.lines))
        warnIfQuiet()
    }

    fun stopSpeaking() { speaker.stop(); readingAloud.value = null }

    fun sayHello() {
        val s = settings.value
        speaker.configure(Speaker.Config(s.ttsEngine, s.ttsVoice, s.ttsRate, s.ttsPitch))
        speaker.stop(); speaker.say("Okay, I'll read my answers out loud.")
        warnIfQuiet()
    }

    /** Spoken replies use the media volume; say so on screen if it's too low to hear. */
    fun warnIfQuiet() {
        if (speaker.volumeLow()) android.widget.Toast.makeText(context,
            "Media volume is low: turn it up to hear replies.", android.widget.Toast.LENGTH_LONG).show()
    }

    /** Called when the assistant opens: load the chosen model now so the first answer starts sooner. */
    fun warmUp() {
        val m = ModelCatalog.chatById(settings.value.modelId) ?: return
        if (m.id in models.installed.value) client.preload(models.fileFor(m).path)
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

    fun cancel(id: Long) = update(id) { it.copy(card = null, text = "Cancelled.") }

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
                speaker.sayAll(r.card?.let { c -> "${c.title}?" } ?: r.spoken ?: com.hiddenoutlier.launcher.core.Speech.forSpeech(r.text, r.lines))
            }
        }
    }
    fun clear() { if (!busy.value) chat.value = emptyList() }

    companion object { const val STALL_MS = 90_000L }
}
