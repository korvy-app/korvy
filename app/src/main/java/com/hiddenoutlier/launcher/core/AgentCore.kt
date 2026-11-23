package com.hiddenoutlier.launcher.core

/** What the assistant can do. The model may only choose one of these (enforced by [AgentCore.GRAMMAR]). */
enum class ActionType(val id: String, val help: String) {
    ANSWER("answer", "reply in words; arg = the reply"),
    OPEN_APP("open_app", "open an app on this phone; arg = app name"),
    WEB_SEARCH("web_search", "look something up on the web; arg = search words"),
    WEATHER("weather", "current weather and today's forecast; arg = place, or empty for the user's home"),
    NEWS("news", "latest news headlines; arg = topic, or empty"),
    STOCK("stock", "stock price; arg = ticker symbol like AAPL"),
    TIMER("timer", "start a countdown timer; arg = length like \"5 minutes\""),
    DIAL("dial", "open the phone dialer; arg = phone number");

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
    fun systemPrompt(personality: String, now: String, home: String?): String = buildString {
        if (personality.isNotBlank()) {
            appendLine("Personality (affects only how answers are worded):")
            appendLine(personality.trim())
            appendLine()
        }
        appendLine("You are the assistant built into a phone's home screen. Today is $now. The time is given with each question.")
        if (!home.isNullOrBlank()) appendLine("The user's home area is $home.")
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
    )

    // ---- Plain-code fast path ------------------------------------------------------------------------------

    private val OPEN = Regex("^(?:please\\s+)?(?:open|launch|start|run)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE)
    private val WEATHER = Regex("^(?:what'?s\\s+the\\s+|how'?s\\s+the\\s+)?weather(?:\\s+(?:like\\s+)?(?:in|for|at)\\s+(.+?))?(?:\\s+today)?\\??$", RegexOption.IGNORE_CASE)
    private val NEWS = Regex("^(?:the\\s+|latest\\s+|today'?s\\s+)?(?:news|headlines)(?:\\s+(?:about|on)\\s+(.+?))?\\??$", RegexOption.IGNORE_CASE)
    private val STOCK = Regex("^(?:stock|quote|price)\\s+(?:of\\s+|for\\s+)?([A-Za-z.]{1,6})\\??$|^([A-Za-z.]{1,6})\\s+(?:stock|share)\\s*(?:price)?\\??$", RegexOption.IGNORE_CASE)
    private val TIMER = Regex("^(?:set\\s+(?:a\\s+)?)?timer\\s+(?:for\\s+)?(.+)$|^(.+?)\\s+timer$", RegexOption.IGNORE_CASE)
    private val DIAL = Regex("^(?:call|dial)\\s+([+0-9][0-9 ()\\-]{2,})$", RegexOption.IGNORE_CASE)
    private val SEARCH = Regex("^(?:search(?:\\s+for)?|look\\s+up|google)\\s+(.+)$", RegexOption.IGNORE_CASE)

    /** Recognises common requests without the model. Null means "ask the model". */
    fun quickParse(input: String): Action? {
        val t = input.trim().trimEnd('.', '!')
        if (t.isEmpty()) return null
        DIAL.matchEntire(t)?.let { return Action(ActionType.DIAL, it.groupValues[1].trim()) }
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
