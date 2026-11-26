package app.korvy.launcher

import app.korvy.launcher.core.AgentCore
import app.korvy.launcher.core.Calc
import app.korvy.launcher.core.Lesson
import app.korvy.launcher.core.Spanish
import org.junit.Test
import java.io.File

/**
 * Exports what the on-device model sees (system prompt, examples, grammar) for scripts/model-eval, and marks which
 * test requests plain code already handles (those never reach the model, so they don't count). Writes
 * build/model-eval.json; does nothing when the cases file isn't there.
 */
class ModelEvalExport {
    private fun js(s: String) = "\"" + AgentCore.escape(s) + "\""

    @Test fun export() {
        val cases = File("../scripts/model-eval/cases.json").takeIf { it.exists() } ?: return
        val questions = Regex("\"q\":\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(cases.readText()).map { AgentCore.unescape(it.groupValues[1]) }.toList()
        val personality = "# Assistant\nBe brief and direct. Answer in one or two sentences. No small talk."
        val apps = "Texting: Messages; Calls: Phone; Maps and directions: Maps; Rides: Uber; Music: Spotify"
        fun handled(q: String) = Calc.answer(q) != null || AgentCore.quickParse(q) != null || Lesson.parse(q) != null ||
            Spanish.toEnglish(q)?.let { Calc.answer(it) != null || AgentCore.quickParse(it) != null || Lesson.parse(it) != null } == true
        val json = buildString {
            append("{\"system_en\":").append(js(AgentCore.systemPrompt(personality, null, apps, null)))
            append(",\"system_es\":").append(js(AgentCore.systemPrompt(personality, null, apps, "Spanish")))
            append(",\"examples\":[").append(AgentCore.EXAMPLES.joinToString(",") { "[" + js(it.first) + "," + js(it.second) + "]" }).append("]")
            append(",\"grammar\":").append(js(AgentCore.GRAMMAR))
            append(",\"handled\":{").append(questions.joinToString(",") { js(it) + ":" + handled(it) }).append("}}")
        }
        File("build/model-eval.json").writeText(json)
    }
}
