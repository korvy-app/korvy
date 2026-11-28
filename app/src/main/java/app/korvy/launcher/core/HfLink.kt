package app.korvy.launcher.core

/** A file in a Hugging Face model repo, from a link the user pasted. */
data class HfFile(val repo: String, val rev: String, val path: String) {
    val fileName get() = path.substringAfterLast('/')
    val dir get() = path.substringBeforeLast('/', "")
}

/**
 * Reads Hugging Face links to one model file: the file page (…/blob/main/x.gguf), the download link
 * (…/resolve/main/x.gguf, optionally ?download=true) or the same on hf.co. Only huggingface.co / hf.co, only
 * .gguf files (llama.cpp's format). Anything else is an [Error] the screen can explain.
 */
object HfLink {
    sealed interface Result
    data class Ok(val file: HfFile) : Result
    enum class Error : Result { NOT_HUGGING_FACE, NOT_A_FILE, NOT_GGUF }

    private val LINK = Regex("^https?://(?:www\\.)?(?:huggingface\\.co|hf\\.co)/([A-Za-z0-9][\\w.-]*/[\\w.-]+)/(?:blob|resolve)/([^/]+)/(.+)$")

    fun parse(link: String): Result {
        val t = link.trim().substringBefore('#').substringBefore('?')
        val host = Regex("^https?://(?:www\\.)?([^/]+)").find(t)?.groupValues?.get(1)?.lowercase()
        if (host != "huggingface.co" && host != "hf.co") return Error.NOT_HUGGING_FACE
        val m = LINK.matchEntire(t) ?: return Error.NOT_A_FILE
        val path = java.net.URLDecoder.decode(m.groupValues[3], "UTF-8")
        if (!path.lowercase().endsWith(".gguf")) return Error.NOT_GGUF
        if (path.split('/').any { it == ".." || it.isEmpty() }) return Error.NOT_A_FILE
        return Ok(HfFile(m.groupValues[1], java.net.URLDecoder.decode(m.groupValues[2], "UTF-8"), path))
    }

    /** Model id for Korvy's lists and file names (stable for the same file). */
    fun id(f: HfFile) = "custom:" + f.repo + "/" + f.rev + "/" + f.path
}
