package app.korvy.launcher.core

/**
 * Splits words into the keyword model's word pieces ("HEY OKUVI" → "▁HE Y ▁O K U VI"), the way SentencePiece's
 * unigram model does: the split whose pieces have the highest total log-probability. [scores] maps each piece to
 * its log-probability (from the model's bpe.model, shipped as assets/kws-pieces.tsv).
 */
class WordPieces(private val scores: Map<String, Float>) {
    private val longest = scores.keys.maxOf { it.length }

    /** Null if some character can't be spelled with the model's pieces. */
    fun encode(text: String): String? {
        val out = ArrayList<String>()
        for (word in text.uppercase().split(Regex("[^A-Z']+")).filter { it.isNotEmpty() }) {
            val w = "▁$word"
            val n = w.length
            val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
            val from = IntArray(n + 1) { -1 }
            best[0] = 0.0
            for (i in 1..n) for (j in maxOf(0, i - longest) until i) {
                if (best[j] == Double.NEGATIVE_INFINITY) continue
                val s = scores[w.substring(j, i)] ?: continue
                if (best[j] + s > best[i]) { best[i] = best[j] + s; from[i] = j }
            }
            if (from[n] < 0) return null
            val seg = ArrayList<String>()
            var i = n
            while (i > 0) { val j = from[i]; seg += w.substring(j, i); i = j }
            out += seg.asReversed()
        }
        return out.joinToString(" ").ifEmpty { null }
    }

    /**
     * The keyword model hears an invented name like "Korvy" as "korvi" or "corvie", not as it's spelled, so a wake
     * phrase is listened for in a few sounds-alike spellings of its last word: endings -y/-ie/-i, and k/c at the
     * start. Tested against speech in several voices and near-misses ("hey curvy", "hey Korea", "hey coffee").
     */
    fun spellings(phrase: String): List<String> {
        val words = phrase.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val last = words.last()
        val stems = linkedSetOf(last)
        Regex("^(.{3,}?)(y|ie|ee|i)$").matchEntire(last)?.let { m -> listOf("y", "ie", "i").forEach { stems += m.groupValues[1] + it } }
        val all = stems.flatMap { w -> listOfNotNull(w, when { w.startsWith("k") -> "c" + w.drop(1); w.startsWith("c") -> "k" + w.drop(1); else -> null }) }
        return all.distinct().map { (words.dropLast(1) + it).joinToString(" ") }.take(8)
    }

    companion object {
        fun parse(tsv: String) = WordPieces(tsv.lineSequence().mapNotNull { l ->
            val t = l.split('\t'); if (t.size == 2) t[0] to t[1].toFloat() else null
        }.toMap())

        /** Wake words should be distinctive: at least 3 syllables (rough vowel-group count) and not everyday words. */
        fun syllables(text: String): Int = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.sumOf { w ->
            val n = Regex("[aeiouy]+").findAll(w).count()
            (if (w.endsWith("e") && !w.endsWith("le") && n > 1) n - 1 else n).coerceAtLeast(1)
        }
    }
}
