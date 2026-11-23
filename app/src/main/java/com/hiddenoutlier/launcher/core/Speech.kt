package com.hiddenoutlier.launcher.core

/** Turning what the assistant shows into what it says. */
object Speech {
    /** "San Diego" + "California" → "San Diego, California"; "New York" + "New York" → "New York". */
    fun placeName(name: String, region: String?): String {
        val r = region?.trim().orEmpty()
        val n = name.trim()
        if (r.isEmpty() || n.isEmpty()) return n.ifEmpty { r }
        val ln = n.lowercase(); val lr = r.lowercase()
        return if (ln == lr || ln.contains(lr) || lr.contains(ln)) n else "$n, $r"
    }

    /**
     * The reply plus its detail lines as one natural sentence stream: skips "Source:" lines and link-only lines,
     * drops bullets, reads "·" as a pause and degree signs as "degrees".
     */
    fun forSpeech(text: String, lines: List<String>, maxLines: Int = 4): String {
        val parts = (listOf(text) + lines.filterNot { it.startsWith("Source:", ignoreCase = true) || it.startsWith("http") }.take(maxLines))
            .map { clean(it) }.filter { it.isNotBlank() }
        return parts.joinToString(" ") { if (it.last() in ".!?:") it else "$it." }
    }

    fun clean(s: String) = s.replace("•", "").replace(" · ", ", ").replace("·", ", ")
        .replace(Regex("(\\d)\\s*°\\s*[FC]\\b")) { "${it.groupValues[1]} degrees" }
        .replace(Regex("(\\d)\\s*%")) { "${it.groupValues[1]} percent" }
        .replace(Regex("\\s+"), " ").trim()
}
