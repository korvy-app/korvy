package app.korvy.launcher.core

/**
 * Ranks app names against what the user typed. Higher is better; 0 means no match.
 * Order: exact > starts with > a word starts with > initials ("gm" → "Google Maps") > contains >
 * close with a typo (edit distance) > letters in order.
 */
object AppSearch {
    fun score(query: String, label: String): Int {
        val q = normalize(query)
        val l = normalize(label)
        if (q.isEmpty()) return 0
        if (l == q) return 1000
        if (l.startsWith(q)) return 900 - l.length.coerceAtMost(100)
        val words = l.split(' ').filter { it.isNotEmpty() }
        if (words.any { it.startsWith(q) }) return 800 - l.length.coerceAtMost(100)
        val initials = words.joinToString("") { it.take(1) }
        if (q.length >= 2 && initials.startsWith(q)) return 700
        if (l.contains(q)) return 600 - l.indexOf(q)
        typoScore(q, l, words).let { if (it > 0) return it }
        if (q.length >= 3 && isSubsequence(q.replace(" ", ""), l.replace(" ", ""))) return 300
        return 0
    }

    /** Mistakes allowed for a query of this length: none for very short queries (too many false hits). */
    fun allowedTypos(len: Int) = when { len < 4 -> 0; len < 7 -> 1; else -> 2 }

    /**
     * Typo-tolerant match ("chorme" → Chrome, "calcualtor" → Calculator). Compares the query with the start
     * of the name and of each word, so a partly typed name still matches. 450 for one mistake, 400 for two.
     */
    private fun typoScore(q: String, l: String, words: List<String>): Int {
        val max = allowedTypos(q.length)
        if (max == 0) return 0
        val candidates = (listOf(l) + words).flatMap { w ->
            // The name's start at the query's length, give or take a letter (for a missing or extra letter).
            (q.length - 1..q.length + 1).filter { it in 1..w.length }.map { w.substring(0, it) }
        }
        val best = candidates.minOfOrNull { editDistance(q, it, max) } ?: return 0
        return if (best <= max) 500 - 50 * best else 0
    }

    /**
     * Damerau–Levenshtein distance (optimal string alignment): insert, delete, replace, or swap two
     * neighbouring letters each count as one. Stops early once every path exceeds [limit].
     */
    fun editDistance(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return limit + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Exact name match, used to reveal a hidden app only when the user types its whole name. */
    fun isExact(query: String, label: String) = normalize(query).isNotEmpty() && normalize(query) == normalize(label)

    fun <T> rank(query: String, items: List<T>, label: (T) -> String): List<T> =
        items.map { it to score(query, label(it)) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy { label(it.first).lowercase() })
            .map { it.first }

    fun normalize(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "") // "Café" matches "cafe"
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun isSubsequence(q: String, s: String): Boolean {
        var i = 0
        for (c in s) if (i < q.length && c == q[i]) i++
        return i == q.length
    }
}
