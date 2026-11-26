package app.korvy.launcher.core

/**
 * What the user was looking at when they called the assistant (Android's assist API gives the foreground app's
 * visible text). Plain code: cleans it up for the on-device model and decides which requests are about it.
 * Only text, only when the user summons the assistant, never password or card fields (filtered before this),
 * never hidden apps (checked by the caller), and it's kept only for that assistant session.
 */
data class ScreenContext(val appLabel: String, val text: String, val selected: Boolean = false)

object ScreenText {
    const val MAX_CHARS = 1500

    /**
     * Visible texts in reading order → one compact block: trimmed, blank and one-character bits dropped, repeats
     * (lists often show the same label many times) kept once, capped at [max] characters (cut at a line end).
     */
    fun compact(texts: List<String>, max: Int = MAX_CHARS): String {
        val seen = HashSet<String>()
        val out = StringBuilder()
        for (raw in texts) {
            val t = raw.replace(Regex("\\s+"), " ").trim()
            if (t.length < 2 || t.none { it.isLetterOrDigit() }) continue
            if (!seen.add(t.lowercase())) continue
            if (out.length + t.length + 1 > max) {
                if (out.isEmpty()) out.append(t.take(max))
                break
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(t)
        }
        return out.toString()
    }

    // Whole words only. Written with explicit "not next to a letter or digit" checks instead of \b: \b ignores
    // accented letters ("aquí") and Android's regex engine (ICU) rejects the (?U) flag that would fix it.
    private const val W0 = "(?<![\\p{L}\\p{N}])"
    private const val W1 = "(?![\\p{L}\\p{N}])"
    private val DEICTIC = Regex(
        W0 + "(this|that|these|those|here|screen|page|article|recipe|post|email|message above|on screen|" +
            "esto|eso|esta|este|estos|estas|aqu[ií]|pantalla|p[aá]gina|art[ií]culo|receta|" +
            "summari[sz]e|tl;?dr|resume|resumir|resúmeme|explica(?:me)?|translate|traduce|" +
            // Portuguese, French, German
            "isto|isso|aqui|tela|resum[aoi]r?|traduz(?:a|ir)?|ceci|cela|ici|écran|résum(?:e|er|é)|tradui(?:s|re)|" +
            "dies|diese[rsmn]?|hier|bildschirm|seite|zusammenfass(?:en|ung)|übersetze[n]?)" + W1,
        RegexOption.IGNORE_CASE,
    )

    /** True when the request is about what's on screen ("text this to Jen", "summarise this", "¿qué dice aquí?"). */
    fun refersToScreen(question: String): Boolean = DEICTIC.containsMatchIn(question)

    /** How the screen goes to the model, in front of the question. */
    fun forPrompt(s: ScreenContext): String =
        if (s.selected) "(Text the user selected in ${s.appLabel}: \"\"\"${s.text}\"\"\")"
        else "(What's on the user's screen in ${s.appLabel}: \"\"\"${s.text}\"\"\")"
}
