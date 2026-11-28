package app.korvy.launcher.core

/** One model's Advanced settings (Models → Advanced): sampling temperature, reply length limit, context size. */
data class ModelTuning(val temp: Float = DEFAULT_TEMP, val maxTokens: Int = DEFAULT_MAX_TOKENS, val ctx: Int = DEFAULT_CTX) {
    /** Within the ranges the screen offers (a hand-edited or old value can't break the model). */
    fun clean() = ModelTuning(temp.coerceIn(0f, 1.5f), maxTokens.coerceIn(100, 1000), if (ctx == 8192) 8192 else DEFAULT_CTX)
    val isDefault get() = clean() == ModelTuning()

    companion object {
        const val DEFAULT_TEMP = 0.2f
        const val DEFAULT_MAX_TOKENS = 220
        const val DEFAULT_CTX = 4096

        /** Saved as one line per model: "id|temp|maxTokens|ctx" (model ids never contain '|' or a line break). */
        fun serialize(all: Map<String, ModelTuning>): String =
            all.filterValues { !it.isDefault }.entries.sortedBy { it.key }
                .joinToString("\n") { (id, t) -> "$id|${t.temp}|${t.maxTokens}|${t.ctx}" }

        fun parse(s: String?): Map<String, ModelTuning> = s.orEmpty().lines().mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 4 || p[0].isBlank()) return@mapNotNull null
            val t = ModelTuning(p[1].toFloatOrNull() ?: return@mapNotNull null, p[2].toIntOrNull() ?: return@mapNotNull null,
                p[3].toIntOrNull() ?: return@mapNotNull null).clean()
            p[0] to t
        }.toMap()
    }
}
