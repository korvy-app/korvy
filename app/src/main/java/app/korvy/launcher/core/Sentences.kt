package app.korvy.launcher.core

/**
 * Splits text that is still being written into finished sentences, so spoken replies can start before the
 * whole answer is ready. Feed it the full text so far; it returns only sentences it hasn't returned before.
 */
class SentenceSplitter {
    private var spokenUpTo = 0

    fun feed(textSoFar: String): List<String> {
        val out = ArrayList<String>()
        var i = spokenUpTo
        while (i < textSoFar.length) {
            val c = textSoFar[i]
            val end = (c == '.' || c == '!' || c == '?' || c == '\n') &&
                (i + 1 < textSoFar.length && textSoFar[i + 1].isWhitespace()) &&
                !isAbbreviationOrNumber(textSoFar, i)
            if (end) {
                textSoFar.substring(spokenUpTo, i + 1).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                spokenUpTo = i + 1
            }
            i++
        }
        return out
    }

    /** Whatever is left once the text is complete. */
    fun finish(fullText: String): List<String> {
        val rest = feed(fullText) + listOfNotNull(fullText.substring(spokenUpTo.coerceAtMost(fullText.length)).trim().takeIf { it.isNotEmpty() })
        spokenUpTo = fullText.length
        return rest
    }

    private fun isAbbreviationOrNumber(s: String, dot: Int): Boolean {
        if (s[dot] != '.') return false
        val word = s.substring(0, dot).takeLastWhile { it.isLetter() }
        return word.length == 1 || word.lowercase() in setOf("mr", "mrs", "ms", "dr", "st", "vs", "etc", "e.g", "i.e", "no")
    }
}
