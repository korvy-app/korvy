package com.hiddenoutlier.launcher.core

/**
 * Inline autocomplete for the search box: the rest of the best-matching name, shown greyed after what the user
 * typed ("spo" → "tify"). Only offered when the top name actually starts with the typed text, so accepting it
 * never changes what was typed.
 */
object Completion {
    fun suffix(query: String, rankedLabels: List<String>): String? {
        if (query.isBlank()) return null
        val top = rankedLabels.firstOrNull() ?: return null
        if (top.length <= query.length || !top.startsWith(query, ignoreCase = true)) return null
        return top.substring(query.length)
    }
}
