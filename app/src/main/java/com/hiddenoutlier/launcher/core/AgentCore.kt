package com.hiddenoutlier.launcher.core

/** What the assistant can do. The model may only choose one of these (enforced by [AgentCore.GRAMMAR]). */
enum class ActionType(val id: String, val help: String) {
    ANSWER("answer", "reply in words; arg = the reply"),
    OPEN_APP("open_app", "open an app on this phone; arg = app name"),
    WEB_SEARCH("web_search", "look something up on the web; arg = search words"),
    WEATHER("weather", "current weather and the coming days; arg = a city or place name only (never a time or date), or empty for where the user is"),
    NEWS("news", "latest news headlines; arg = topic, or empty"),
    STOCK("stock", "stock price; arg = ticker symbol like AAPL"),
    TIMER("timer", "start a countdown timer; arg = length like \"5 minutes\""),
    TEXT("text", "send a text message; arg = \"recipient: message\""),
    CALL("call", "phone someone; arg = contact name or phone number"),
    RIDE("ride", "order a ride; arg = \"uber: destination\" or \"lyft: destination\""),
    NAVIGATE("navigate", "directions in the maps app; arg = destination"),
    PLAY("play", "play music in the user's music app; arg = song, artist, album, playlist or genre"),
    HELP("help", "list what you can do; arg empty"),
    REMEMBER("remember", "only when the user asks you to remember something about them: save a short note; arg = the note");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

data class Action(val type: ActionType, val arg: String)

/**
 * The assistant's logic that doesn't need Android: a plain-code parser for common requests (so they never touch
 * the model, in keeping with "less LLM"), the grammar that constrains the model's output to one valid action,
 * the system prompt, and parsing of the model's JSON.
 */
object AgentCore {

    /** GBNF grammar: exactly {"action": "<one of the actions>", "arg": "<text>"}. */
    val GRAMMAR: String = buildString {
        appendLine("""root ::= "{" ws "\"action\":" ws action "," ws "\"arg\":" ws str ws "}"""")
        appendLine("action ::= " + ActionType.entries.joinToString(" | ") { "\"\\\"${it.id}\\\"\"" })
        appendLine("""str ::= "\"" ( [^"\\\x00-\x1f] | "\\" ["\\/bfnrt] )* "\""""")
        appendLine("""ws ::= [ \t\n]?""")
    }

    /** Short system prompt (small models slow down with long prompts). The personality can't override the rules. */
    fun systemPrompt(personality: String, now: String, home: String?, apps: String? = null): String = buildString {
        if (personality.isNotBlank()) {
            appendLine("Personality (affects only how answers are worded):")
            appendLine(personality.trim())
            appendLine()
        }
        appendLine("You are the assistant built into a phone's home screen. Today is $now. The time is given with each question.")
        if (!home.isNullOrBlank()) appendLine("The user's home area is $home.")
        if (!apps.isNullOrBlank()) appendLine("Apps the user picked for tasks: $apps. Actions use these automatically.")
        appendLine("Reply with JSON only: {\"action\": ..., \"arg\": ...}. Actions:")
        ActionType.entries.forEach { appendLine("- ${it.id}: ${it.help}") }
        appendLine("Rules: pick the action that does what the user asked. Use answer for chat, facts you are sure of, and maths.")
        appendLine("For news, weather, prices or anything recent, use the matching action instead of guessing.")
        appendLine("Keep answers short (1-3 sentences). Never claim to have done something you didn't.")
    }

    /** Few-shot examples, sent as earlier chat turns so even 0.5B models get the format right. */
    val EXAMPLES: List<Pair<String, String>> = listOf(
        "what's the weather like in Paris" to """{"action": "weather", "arg": "Paris"}""",
        "can you open my camera" to """{"action": "open_app", "arg": "Camera"}""",
        "how much is tesla trading at" to """{"action": "stock", "arg": "TSLA"}""",
        "what's 15% of 80" to """{"action": "answer", "arg": "15% of 80 is 12."}""",
        "let my sister know I'll be late" to """{"action": "text", "arg": "sister: I'll be late"}""",
    )

    // ---- Plain-code fast path ------------------------------------------------------------------------------

    private val OPEN = Regex("^(?:please\\s+)?(?:open|launch|start|run)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE)
    private val WEATHER = Regex("^(?:(?:what|how)(?:'?s|\\s+is|\\s+was|\\s+will)\\s+(?:be\\s+)?the\\s+|(?:tell\\s+me\\s+)?the\\s+)?weather(?:\\s+(?:be|been|going\\s+to\\s+be))?(?:\\s+like)?(?:\\s+(?:in|for|at)\\s+(.+?))?(?:\\s+(?:today|tomorrow|tonight|this\\s+(?:week|weekend|morning|afternoon|evening)|right\\s+now|now))?\\??$", RegexOption.IGNORE_CASE)
    private val NEWS = Regex("^(?:the\\s+|latest\\s+|today'?s\\s+)?(?:news|headlines)(?:\\s+(?:about|on)\\s+(.+?))?\\??$", RegexOption.IGNORE_CASE)
    private val STOCK = Regex("^(?:stock|quote|price)\\s+(?:of\\s+|for\\s+)?([A-Za-z.]{1,6})\\??$|^([A-Za-z.]{1,6})\\s+(?:stock|share)\\s*(?:price)?\\??$", RegexOption.IGNORE_CASE)
    private val TIMER = Regex("^(?:set\\s+(?:a\\s+)?)?timer\\s+(?:for\\s+)?(.+)$|^(.+?)\\s+timer$", RegexOption.IGNORE_CASE)
    private val RIDE = Regex("^(?:(?:please\\s+)?(?:get|call|book|order|find)\\s+(?:me\\s+)?)?(?:an?\\s+)?(uber|lyft|ride|taxi|cab)\\s+(?:to|for)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val CALL = Regex("^(?:please\\s+)?(?:call|dial|phone|ring)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val TEXT_SPLIT = Regex("^(?:please\\s+)?(?:text|message|sms|send\\s+(?:a\\s+)?(?:text|message)\\s+to)\\s+(.+?)\\s*(?::|\\bsaying\\b|\\bthat\\b)\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val TEXT_PLAIN = Regex("^(?:please\\s+)?(?:text|message|sms)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val PLAY = Regex("^(?:please\\s+)?(?:play|put\\s+on|listen\\s+to)\\s+(?:some\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val HELP = Regex("^(?:help|what\\s+can\\s+you\\s+do|what\\s+are\\s+you\\s+able\\s+to\\s+do|what\\s+do\\s+you\\s+do)\\??$", RegexOption.IGNORE_CASE)
    private val NAVIGATE = Regex("^(?:navigate|directions|drive|take\\s+me|how\\s+do\\s+i\\s+get)\\s+(?:me\\s+)?(?:to\\s+)?(.+?)\\??$", RegexOption.IGNORE_CASE)
    private val SEARCH = Regex("^(?:search(?:\\s+for)?|look\\s+up|google)\\s+(.+)$", RegexOption.IGNORE_CASE)

    /** Recognises common requests without the model. Null means "ask the model". */
    /**
     * A weather "place" that is really a time or date ("4:26 PM", "tomorrow", "Monday") means "here", not a place.
     * Small models sometimes copy the current time from the prompt into the place.
     */
    fun weatherPlace(arg: String): String {
        val t = arg.trim().trim('.', '?', '!', ',')
        val timeLike = Regex("(?i)^(?:\\d{1,2}(?::\\d{2})?\\s*(?:[ap]\\.?m\\.?)?|now|right now|today|tonight|tomorrow|yesterday|this\\s+\\w+|next\\s+\\w+|" +
            "(?:mon|tues|wednes|thurs|fri|satur|sun)day|here|my\\s+(?:area|location)|where\\s+i\\s+am|outside|current(?:ly)?|.*\\b\\d{4}-\\d{2}-\\d{2}\\b.*)$")
        return if (t.isEmpty() || timeLike.matches(t)) "" else t
    }

    fun quickParse(input: String): Action? {
        val t = input.trim().trimEnd('.', '!')
        if (t.isEmpty()) return null
        RIDE.matchEntire(t)?.let { m ->
            val app = m.groupValues[1].lowercase().let { if (it == "lyft") "lyft" else "uber" }
            return Action(ActionType.RIDE, "$app: ${m.groupValues[2].trim()}")
        }
        TEXT_SPLIT.matchEntire(t)?.let { return Action(ActionType.TEXT, "${it.groupValues[1].trim()}: ${it.groupValues[2].trim()}") }
        // "text mom I'm late": where the name ends is decided later against the contacts (TextArgs.split).
        TEXT_PLAIN.matchEntire(t)?.let { return Action(ActionType.TEXT, it.groupValues[1].trim()) }
        NAVIGATE.matchEntire(t)?.let { return Action(ActionType.NAVIGATE, it.groupValues[1].trim()) }
        HELP.matchEntire(t)?.let { return Action(ActionType.HELP, "") }
        PLAY.matchEntire(t)?.let { return Action(ActionType.PLAY, it.groupValues[1].trim()) }
        CALL.matchEntire(t)?.let { return Action(ActionType.CALL, it.groupValues[1].trim()) }
        TIMER.matchEntire(t)?.let { m -> parseDuration(m.groupValues[1].ifEmpty { m.groupValues[2] })?.let { return Action(ActionType.TIMER, it.toString()) } }
        WEATHER.matchEntire(t)?.let { return Action(ActionType.WEATHER, it.groupValues[1].trim()) }
        NEWS.matchEntire(t)?.let { return Action(ActionType.NEWS, it.groupValues[1].trim()) }
        STOCK.matchEntire(t)?.let { m -> return Action(ActionType.STOCK, m.groupValues[1].ifEmpty { m.groupValues[2] }.uppercase()) }
        SEARCH.matchEntire(t)?.let { return Action(ActionType.WEB_SEARCH, it.groupValues[1].trim()) }
        OPEN.matchEntire(t)?.let { return Action(ActionType.OPEN_APP, it.groupValues[1].trim()) }
        return null
    }

    /** "5 minutes", "90 sec", "1 hour 30 minutes", "1.5 hours" → seconds; null when not a duration. */
    fun parseDuration(text: String): Int? {
        val units = mapOf("h" to 3600, "hr" to 3600, "hrs" to 3600, "hour" to 3600, "hours" to 3600,
            "m" to 60, "min" to 60, "mins" to 60, "minute" to 60, "minutes" to 60,
            "s" to 1, "sec" to 1, "secs" to 1, "second" to 1, "seconds" to 1)
        val parts = Regex("(\\d+(?:\\.\\d+)?)\\s*([a-z]+)").findAll(text.lowercase()).toList()
        if (parts.isEmpty()) return text.trim().toIntOrNull()?.takeIf { it > 0 }?.let { it * 60 } // bare number = minutes
        var total = 0.0
        for (p in parts) {
            val mult = units[p.groupValues[2]] ?: return null
            total += p.groupValues[1].toDouble() * mult
        }
        return total.toInt().takeIf { it in 1..24 * 3600 }
    }

    // ---- Model output --------------------------------------------------------------------------------------

    // Note: Android's ICU regex engine requires the closing brace to be escaped (the desktop JVM doesn't).
    private val JSON = Regex("""\{\s*"action"\s*:\s*"([a-z_]+)"\s*,\s*"arg"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}""")

    fun parse(json: String): Action? {
        val m = JSON.find(json) ?: return null
        val type = ActionType.of(m.groupValues[1]) ?: return null
        return Action(type, unescape(m.groupValues[2]))
    }

    /** While an "answer" streams in, the reply text so far (for showing it live). Null until the arg starts. */
    fun partialAnswer(jsonSoFar: String): String? {
        if (!jsonSoFar.contains("\"answer\"")) return null
        val i = Regex("\"arg\"\\s*:\\s*\"").find(jsonSoFar) ?: return null
        val rest = jsonSoFar.substring(i.range.last + 1)
        val end = Regex("(?<!\\\\)\"").find(rest)?.range?.first ?: rest.length
        return unescape(rest.substring(0, end).removeSuffix("\\"))
    }

    /** An action as the model writes it, for chat history. */
    fun toJson(a: Action): String = "{\"action\": \"${a.type.id}\", \"arg\": \"${escape(a.arg)}\"}"

    fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\t' -> append("\\t")
            else -> if (c.code < 0x20) append(' ') else append(c)
        }
    }

    /**
     * Chat history for the model: earlier turns as (user question, assistant action JSON) pairs, newest last, at most
     * [maxTurns]. When the previous action fetched something (weather, news…), its result is put in front of the new
     * question, so follow-ups like "and tomorrow?" or "what about Seattle?" make sense to the model.
     */
    data class Turn(val question: String, val actionJson: String, val resultText: String, val wasTool: Boolean)

    fun historyMessages(turns: List<Turn>, newQuestion: String, maxTurns: Int = 4): List<Pair<String, String>> {
        val recent = turns.takeLast(maxTurns)
        val out = ArrayList<Pair<String, String>>()
        recent.forEachIndexed { i, t ->
            val prev = recent.getOrNull(i - 1)
            out += "user" to withResult(prev, t.question)
            out += "assistant" to t.actionJson
        }
        out += "user" to withResult(recent.lastOrNull(), newQuestion)
        return out
    }

    private fun withResult(prev: Turn?, question: String) =
        if (prev != null && prev.wasTool && prev.resultText.isNotBlank()) "(Result of your last action: ${prev.resultText.take(400)})\n$question" else question

    fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> append('\n'); 't' -> append('\t'); 'r' -> {}; 'b', 'f' -> {}
                    else -> append(n)
                }
                i += 2
            } else { append(c); i++ }
        }
    }
}
