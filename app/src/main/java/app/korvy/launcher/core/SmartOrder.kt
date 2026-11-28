package app.korvy.launcher.core

/**
 * "Smart app order": apps the user is likely to open now (from Suggest, on the phone) come first.
 * - Empty search: likely apps in order, then everyone else alphabetically.
 * - Typed search: the match quality still decides first (AppSearch bands: exact, starts with, a word starts
 *   with, initials, contains, typo, letters in order); within a band the likely app comes first. A worse match
 *   never jumps above a better one.
 */
object SmartOrder {
    /** [likely] = app keys, most likely first. */
    fun <T> all(items: List<T>, likely: List<String>, key: (T) -> String, label: (T) -> String): List<T> {
        val pos = likely.withIndex().associate { (i, k) -> k to i }
        return items.sortedWith(compareBy<T>({ pos[key(it)] ?: Int.MAX_VALUE }, { label(it).lowercase() }))
    }

    fun <T> search(query: String, ranked: List<T>, likely: List<String>, key: (T) -> String, label: (T) -> String): List<T> {
        if (likely.isEmpty()) return ranked
        val pos = likely.withIndex().associate { (i, k) -> k to i }
        // Keep AppSearch's order inside each band apart from likelihood (stable sort).
        return ranked.sortedWith(compareBy<T>({ -(AppSearch.score(query, label(it)) / 100) }, { pos[key(it)] ?: Int.MAX_VALUE }))
    }
}
