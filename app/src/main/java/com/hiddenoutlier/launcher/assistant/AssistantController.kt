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
    /** Set when a request opened another app (the power-button panel closes itself then). */
    val leftForApp = MutableStateFlow(0)
    val speaker = Speaker(context)
    private val client = AssistantClient(context, scope)
    private var nextId = 0L
    private var job: Job? = null

    private suspend fun visibleApps(): List<AppEntry> {
        if (repo.apps.value.isEmpty()) repo.refresh()
        val hidden = withContext(Dispatchers.IO) { privacy.load().hidden.keys } // the assistant never sees hidden apps
        return repo.apps.value.filter { it.key !in hidden }
    }

    private var appsCache: List<AppEntry> = emptyList()
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
        speaker.stop()
        val speak = speaking(byVoice)
        chat.value = chat.value + ChatItem(nextId++, true, q)
        val replyId = nextId++
        chat.value = chat.value + ChatItem(replyId, false, "", pending = true)
        busy.value = true
        job = scope.launch {
            var streamed = false
            try {
                val action = Calc.answer(q)?.let { Action(ActionType.ANSWER, it) }
                    ?: AgentCore.quickParse(q)
                    ?: modelAction(q, replyId, speak).also { streamed = it?.type == ActionType.ANSWER }
                if (action == null) { if (speak) speaker.say(chat.value.last { it.id == replyId }.text); return@launch }
                if (action.type == ActionType.OPEN_APP) appsCache = visibleApps()
                if (action.type != ActionType.ANSWER) update(replyId) { it.copy(text = "…") }
                val r = tools.run(action)
                update(replyId) { it.copy(text = r.text, lines = r.lines, link = r.link, pending = false) }
                if (speak) { if (streamed) speaker.endStream(r.text) else speaker.say(r.text) }
            } catch (e: CancellationException) {
                update(replyId) { it.copy(text = it.text.ifBlank { "Stopped." }, pending = false) }
                throw e
            } catch (e: Exception) {
                update(replyId) { it.copy(text = "Sorry: ${e.message}", pending = false) }
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun modelAction(q: String, replyId: Long, speak: Boolean): Action? {
        val s = settings.value
        val m = ModelCatalog.byId(s.modelId)
        if (m == null || m.id !in models.installed.value) {
            update(replyId) { it.copy(pending = false, text = "To answer questions I need a model. Pick one in Settings → Assistant → Model. " +
                "Without one I can still open apps, set timers, do maths, and (with Online features on) get the weather, news and stock prices.") }
            return null
        }
        val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, h:mm a"))
        val roles = ArrayList<String>(); val contents = ArrayList<String>()
        roles += "system"; contents += AgentCore.systemPrompt(withContext(Dispatchers.IO) { personality.load() }, now, s.homePlace.ifBlank { null })
        AgentCore.EXAMPLES.forEach { (u, a) -> roles += "user"; contents += u; roles += "assistant"; contents += a }
        roles += "user"; contents += q
        val sb = StringBuilder()
        if (speak) speaker.beginStream()
        val out = client.complete(
            models.fileFor(m).path, roles, contents, AgentCore.GRAMMAR, maxTokens = 220, temp = 0.2f,
            onStatus = { st -> update(replyId) { it.copy(text = st) } },
            onToken = { piece ->
                sb.append(piece)
                AgentCore.partialAnswer(sb.toString())?.let { t ->
                    update(replyId) { it.copy(text = t) }
                    if (speak) speaker.stream(t)
                }
            },
        )
        return AgentCore.parse(out) ?: Action(ActionType.ANSWER, "Sorry, I got confused. Could you rephrase that?")
    }

    fun stop() { client.stop(); speaker.stop(); job?.cancel() }
    fun clear() { if (!busy.value) chat.value = emptyList() }
}
