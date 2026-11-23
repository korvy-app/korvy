package com.hiddenoutlier.launcher.core

/**
 * Splits a text-message request into recipient and message. "mom: I'm late" is explicit. "mom I'm late" is
 * ambiguous, so the longest leading words that name a known contact win (a single word if none match).
 */
object TextArgs {
    data class Parts(val recipient: String, val message: String)

    fun split(arg: String, isKnownName: (String) -> Boolean = { false }): Parts {
        val colon = arg.indexOf(':')
        if (colon > 0) return Parts(arg.substring(0, colon).trim(), arg.substring(colon + 1).trim())
        val words = arg.trim().split(Regex("\\s+"))
        for (n in minOf(4, words.size - 1) downTo 1) {
            val name = words.take(n).joinToString(" ")
            if (isKnownName(name)) return Parts(name, words.drop(n).joinToString(" "))
        }
        return Parts(words.first(), words.drop(1).joinToString(" "))
    }

    /** Looks like a phone number rather than a name. */
    fun isNumber(s: String) = s.count { it.isDigit() } >= 3 && s.all { it.isDigit() || it in "+-() ." }
}
