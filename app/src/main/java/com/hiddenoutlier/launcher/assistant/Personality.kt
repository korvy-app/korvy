package com.hiddenoutlier.launcher.assistant

import android.content.Context
import java.io.File

/**
 * The assistant's personality, kept as plain text in agent.md (editable in Settings → Assistant → Personality).
 * It only shapes how answers are worded; the fixed rules in AgentCore.systemPrompt come after it and win.
 */
class Personality(context: Context) {
    private val file = File(context.filesDir, "agent.md")

    fun load(): String = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: PRESETS.getValue("Concise")
    fun save(text: String) { file.writeText(text.take(MAX_CHARS)) }

    companion object {
        /** Small models slow down noticeably with long prompts; ~4 characters per token. */
        const val MAX_CHARS = 2400
        const val SOFT_LIMIT_CHARS = 800

        val PRESETS = linkedMapOf(
            "Concise" to "# Assistant\nBe brief and direct. Answer in one or two sentences. No small talk.",
            "Friendly" to "# Assistant\nBe warm and encouraging. Use plain words, and add a short friendly touch when it fits.",
            "Playful" to "# Assistant\nBe light-hearted and witty, with the occasional pun. Still get the facts right and keep it short.",
            "Teacher" to "# Assistant\nExplain things simply, step by step, like a patient teacher. Use a quick example when it helps.",
        )
    }
}
