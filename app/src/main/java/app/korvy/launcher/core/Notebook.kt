package app.korvy.launcher.core

/**
 * What the user has taught the assistant, kept as a small Markdown file they can read and edit:
 *
 *     ## Notes
 *     - I like temperatures in Celsius.
 *     ## Phrases
 *     - "goodnight" → timer 8 hours; play rain sounds
 *
 * Phrases are matched by plain code (instant, no model) and run existing actions in order. Only notes are shown to
 * the model, and only the few that fit [NOTE_BUDGET] for the question at hand, so the notebook can grow without
 * slowing answers down. The whole file is capped at [MAX_CHARS].
 */
data class Notebook(val notes: List<String> = emptyList(), val phrases: List<Phrase> = emptyList()) {
    data class Phrase(val trigger: String, val steps: List<String>)

    fun toMarkdown(): String = buildString {
        appendLine("# Notebook")
        appendLine()
        appendLine("## Notes")
        appendLine("<!-- Short things to keep in mind when answering. One per line. -->")
        notes.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## Phrases")
        appendLine("<!-- \"what you say\" → step; step. Each step is something you could ask, like \"open Spotify\". -->")
        phrases.forEach { appendLine("- \"${it.trigger}\" → ${it.steps.joinToString("; ")}") }
    }

    val size get() = toMarkdown().length
    fun hasRoomFor(extra: Int) = size + extra <= MAX_CHARS

    fun addNote(note: String): Notebook {
        val n = note.trim().trimEnd('.').replaceFirstChar { it.uppercase() } + "."
        return if (notes.any { key(it) == key(n) }) this else copy(notes = notes + n)
    }

    fun addPhrase(trigger: String, steps: List<String>): Notebook =
        copy(phrases = phrases.filterNot { key(it.trigger) == key(trigger) } + Phrase(trigger.trim(), steps.map { it.trim() }.filter { it.isNotEmpty() }))

    /** Removes notes mentioning [what] and phrases with that trigger. Returns the new notebook and how many went. */
    fun forget(what: String): Pair<Notebook, Int> {
        val k = key(what)
        if (k.isBlank()) return this to 0
        val n = notes.filterNot { key(it).contains(k) }
        val p = phrases.filterNot { key(it.trigger) == k || key(it.trigger).contains(k) }
        return copy(notes = n, phrases = p) to (notes.size - n.size + phrases.size - p.size)
    }

    /** The phrase the user just said, if it's one they taught (ignoring case, punctuation and "please"). */
    fun phraseFor(utterance: String): Phrase? {
        val k = key(utterance).removePrefix("please ").removeSuffix(" please").removePrefix("hey ").trim()
        return phrases.firstOrNull { key(it.trigger) == k }
    }

    /** Notes worth sending with [question]: those sharing words with it first, then the newest, within [budget]. */
    fun notesFor(question: String, budget: Int = NOTE_BUDGET): List<String> {
        val q = words(question)
        val ranked = notes.withIndex().sortedWith(compareByDescending<IndexedValue<String>> { (words(it.value) intersect q).size }.thenByDescending { it.index })
        val out = ArrayList<String>(); var used = 0
        for ((_, n) in ranked) { if (used + n.length + 2 > budget) continue; out += n; used += n.length + 2 }
        return out
    }

    /** Notes that are really phrases ("When I say “x”, do y", saved as notes before 0.11.4) turned into phrases. */
    fun repaired(): Notebook {
        var out = this
        for (n in notes) {
            val lesson = Lesson.parse(n)
            if (lesson is Lesson.NewPhrase && n.trimStart().startsWith("when", ignoreCase = true))
                out = out.copy(notes = out.notes - n).addPhrase(lesson.trigger, lesson.steps)
        }
        return out
    }

    /** Drops duplicate notes and phrases (same words, different case or punctuation). */
    fun tidy(): Notebook = copy(notes = notes.distinctBy(::key), phrases = phrases.reversed().distinctBy { key(it.trigger) }.reversed())

    companion object {
        const val MAX_CHARS = 4000
        const val NOTE_BUDGET = 600
        private val STOP = setOf("the", "and", "for", "you", "your", "are", "was", "with", "that", "this", "what", "when", "how", "can", "please", "about", "like", "have", "has", "not", "but", "all", "any", "its", "it's", "i'm", "me", "my")

        fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        private fun words(s: String) = key(s).split(' ').filter { it.length >= 3 && it !in STOP }.map { it.removeSuffix("s") }.toSet()

        fun parse(md: String?): Notebook {
            if (md.isNullOrBlank()) return Notebook()
            val notes = ArrayList<String>(); val phrases = ArrayList<Phrase>()
            var section = ""
            val phraseLine = Regex("^\"?(.+?)\"?\\s*(?:→|->|=>)\\s*(.+)$")
            for (raw in md.lines()) {
                val line = raw.trim()
                if (line.startsWith("#")) { section = line.trimStart('#').trim().lowercase(); continue }
                if (!line.startsWith("- ") && !line.startsWith("* ")) continue
                val item = line.drop(2).trim()
                if (item.isEmpty()) continue
                if (section.startsWith("phrase")) phraseLine.matchEntire(item)?.let { m ->
                    phrases += Phrase(m.groupValues[1].trim().trim('"', '“', '”'), m.groupValues[2].split(';').map { it.trim() }.filter { it.isNotEmpty() })
                } else notes += item
            }
            return Notebook(notes, phrases)
        }
    }
}

/** Things the user says to teach the assistant, recognised by plain code. */
sealed interface Lesson {
    data class Note(val text: String) : Lesson
    data class NewPhrase(val trigger: String, val steps: List<String>) : Lesson
    /** "When I say Jen I mean Jennifer Lopez": a contact, an app or a note, decided by what [target] turns out to be. */
    data class Alias(val word: String, val target: String) : Lesson
    data class Forget(val what: String) : Lesson
    data object Show : Lesson

    companion object {
        private const val WHEN = "^(?:from now on,?\\s+)?when(?:ever)?\\s+i\\s+say\\s+[\"“']?(.+?)[\"”']?,?\\s+"
        private val ALIAS = Regex("$WHEN(?:i\\s+mean|i'?m\\s+talking\\s+about|that\\s+means|it\\s+means)\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val SAY = Regex("^(?:from now on,?\\s+)?when(?:ever)?\\s+i\\s+say\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val QUOTED = Regex("^[\"“'](.+?)[\"”'],?\\s+(.+)$")
        private val REMEMBER = Regex("^(?:please\\s+)?(?:remember|note|keep\\s+in\\s+mind)(?:\\s+that)?:?\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val FORGET = Regex("^(?:please\\s+)?forget(?:\\s+about)?\\s+[\"“']?(.+?)[\"”']?$", RegexOption.IGNORE_CASE)
        private val SHOW = Regex("^(?:what\\s+do\\s+you\\s+(?:remember|know\\s+about\\s+me)|what\\s+have\\s+you\\s+learn(?:ed|t)|show\\s+(?:me\\s+)?(?:my|your)\\s+notebook)\\??$", RegexOption.IGNORE_CASE)
        /** Words that start a new step in "set a timer for 8 hours and play rain sounds". */
        private val VERB = "(?:open|launch|start|play|put\\s+on|set|text|message|call|phone|ring|navigate|directions|take\\s+me|get\\s+me|search|look\\s+up|show|tell|what|turn|weather|news|timer|remind)"
        private val SPLIT = Regex("\\s*;\\s*|,?\\s+(?:and\\s+)?then\\s+|,\\s+(?=$VERB\\b)|\\s+and\\s+(?:also\\s+)?(?=$VERB\\b)", RegexOption.IGNORE_CASE)

        /** "Remember (that) when I say …" is a phrase, not a note. */
        private val REMEMBER_WHEN = Regex("^(?:please\\s+)?(?:remember|note|keep\\s+in\\s+mind)(?:\\s+that)?[:,]?\\s+(?=(?:from\\s+now\\s+on,?\\s+)?when(?:ever)?\\s+i\\s+say\\b)", RegexOption.IGNORE_CASE)

        fun parse(input: String): Lesson? {
            val t = input.trim().trimEnd('.', '!').replace(REMEMBER_WHEN, "")
            if (SHOW.matches(t)) return Show
            ALIAS.matchEntire(t)?.let { return Alias(it.groupValues[1].trim(), it.groupValues[2].trim()) }
            SAY.matchEntire(t)?.let { m ->
                // Where the trigger ends: a closing quote, else the first comma, else the first command word.
                val rest = m.groupValues[1].trim()
                val (trigger, body) = QUOTED.matchEntire(rest)?.let { it.groupValues[1] to it.groupValues[2] }
                    ?: rest.indexOf(',').takeIf { it > 0 }?.let { rest.substring(0, it) to rest.substring(it + 1) }
                    ?: Regex("\\s(?=$VERB\\b)", RegexOption.IGNORE_CASE).find(rest)?.let { rest.substring(0, it.range.first) to rest.substring(it.range.last + 1) }
                    ?: return null
                val steps = body.trim().removePrefix("then ").removePrefix("please ").split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
                if (trigger.isBlank() || steps.isEmpty()) return null
                return NewPhrase(trigger.trim(), steps)
            }
            FORGET.matchEntire(t)?.let { return Forget(it.groupValues[1].trim()) }
            REMEMBER.matchEntire(t)?.let { return Note(it.groupValues[1].trim()) }
            return null
        }
    }
}
