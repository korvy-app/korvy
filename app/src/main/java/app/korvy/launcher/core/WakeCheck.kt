package app.korvy.launcher.core

/**
 * Second step of the wake word: the small keyword model fires on anything close ("hey curvy", "hey coffee"), then
 * Whisper transcribes those ~2 seconds and this decides whether the wake word was really said.
 *
 * Words are compared by a simple sound key (k→c, ph→f, -y/-ie/-ee/-ey/-i endings → i, doubled letters once), and must
 * match exactly: Whisper writes "Korvy" as "Corvie" (key "corvi"), while near-misses come out as "Curvie", "Covey",
 * "Kirby", "Korea", "Cori", "Coffee", all different keys. (Measured with Whisper tiny.en on six voices, 2026-11-23.)
 */
object WakeCheck {
    fun key(word: String): String {
        var w = word.lowercase().filter { it.isLetter() }
        if (w.isEmpty()) return w
        w = w.replace("ph", "f").replace('k', 'c').replace("ck", "c").replace("cc", "c")
        w = w.replace(Regex("(.)\\1+"), "$1")
        w = w.replace(Regex("(y|ie|ee|ey|i)$"), "i")
        return w
    }

    /** True if [transcript] contains the wake phrase's name (its last word), e.g. "Hey, Corvie!" for "Hey Korvy". */
    fun matches(transcript: String, phrase: String): Boolean {
        val target = key(phrase.trim().split(Regex("[^A-Za-z']+")).lastOrNull { it.isNotBlank() } ?: return false)
        if (target.length < 3) return false
        val words = transcript.split(Regex("[^A-Za-z']+")).filter { it.isNotBlank() }
        if (words.any { key(it) == target }) return true
        // Run-together words ("Heycorvie"): the key of the whole thing ends with the name.
        return key(words.joinToString("")).endsWith(target)
    }

    /**
     * Extra spellings for the first step when Whisper double-checks: also the name without its r ("covie"), which
     * catches more accents but would fire on near-misses without the second step.
     */
    fun looseSpellings(spellings: List<String>): List<String> =
        (spellings + spellings.mapNotNull { s ->
            val words = s.split(' '); val last = words.last()
            last.replace(Regex("(?<=[aeiou])r"), "").takeIf { it != last }?.let { (words.dropLast(1) + it).joinToString(" ") }
        }).distinct()
}
