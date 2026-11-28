package app.korvy.launcher.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.korvy.launcher.platform.Net
import app.korvy.launcher.platform.OnlineSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import app.korvy.launcher.platform.OfflineException

/** A model the user can download. Sizes and SHA-256 checked against Hugging Face on 2026-11-22. */
data class ModelInfo(
    val id: String,
    val name: String,
    val maker: String,
    val license: String,
    val repo: String,
    val file: String,
    val bytes: Long,
    val sha256: String,
    /** Total phone memory (as Android reports it) below which we warn. */
    val minRamGb: Double,
    val note: String,
    /** CHAT models answer; SPEECH models (whisper.cpp) turn speech into text. */
    val kind: Kind = Kind.CHAT,
    /** No longer offered: shown only to people who already downloaded it (so they can keep using or delete it). */
    val retired: Boolean = false,
    /** The file's path in the repo and the revision, when they differ from [file] and "main" (custom models). */
    val path: String = file,
    val rev: String = "main",
    /** Added by the user from a Hugging Face link (see CustomModels). */
    val custom: Boolean = false,
) {
    enum class Kind { CHAT, SPEECH }
    /** Speech models: English-only models are faster and more accurate for English. */
    val englishOnly get() = file.contains(".en")
    val url get() = "https://huggingface.co/$repo/resolve/${android.net.Uri.encode(rev)}/" + path.split('/').joinToString("/") { android.net.Uri.encode(it) }
    val sizeText get() = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
}

object ModelCatalog {
    /**
     * Chat models, chosen 2026-11-25 by scripts/model-eval (Korvy's real prompt and grammar, 44 requests in English
     * and Spanish) and llama-bench on a Pixel 4a; results in docs/models.md. Only licences that allow commercial use.
     */
    val all = listOf(
        ModelInfo("gemma3-1b", "Gemma 3 · 1B", "Google", "Gemma Terms", "ggml-org/gemma-3-1b-it-GGUF",
            "gemma-3-1b-it-Q4_K_M.gguf", 806_058_240, "8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135",
            3.0, "Smallest. For older phones; simpler answers."),
        ModelInfo("qwen2.5-1.5b", "Qwen 2.5 · 1.5B", "Alibaba", "Apache 2.0", "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf", 1_117_320_736, "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            4.0, "Fast and light. Good on older phones."),
        ModelInfo("gemma4-e2b", "Gemma 4 · E2B", "Google", "Apache 2.0", "google/gemma-4-E2B-it-qat-q4_0-gguf",
            "gemma-4-E2B_q4_0-it.gguf", 3_349_516_256, "fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634",
            5.0, "Recommended: accurate, good at Spanish, quick to start."),
        ModelInfo("smollm3-3b", "SmolLM3 · 3B", "Hugging Face (independent)", "Apache 2.0", "ggml-org/SmolLM3-3B-GGUF",
            "SmolLM3-Q4_K_M.gguf", 1_915_305_312, "8334b850b7bd46238c16b0c550df2138f0889bf433809008cc17a8b05761863e",
            6.0, "Fully open model from an independent lab. Newer phones."),
        ModelInfo("gemma4-e4b", "Gemma 4 · E4B", "Google", "Apache 2.0", "google/gemma-4-E4B-it-qat-q4_0-gguf",
            "gemma-4-E4B_q4_0-it.gguf", 5_154_941_280, "676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee",
            12.0, "Bigger Gemma 4 for flagship phones (12 GB+ memory)."),
        ModelInfo("qwen3.5-4b", "Qwen 3.5 · 4B", "Alibaba", "Apache 2.0", "bartowski/Qwen_Qwen3.5-4B-GGUF",
            "Qwen_Qwen3.5-4B-Q4_K_M.gguf", 3_013_027_808, "13c16f426047e2de38cd075bdade4a7bcbc8c774384876f677740cda65f8a983",
            8.0, "Smartest here; flagship phones."),
        // Retired 2026-11-25 (less accurate, slower, or a licence that doesn't allow commercial use).
        ModelInfo("qwen2.5-0.5b", "Qwen 2.5 · 0.5B", "Alibaba", "Apache 2.0", "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf", 491_400_032, "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            2.5, "Smallest and fastest. Good at picking actions, basic at chat.", retired = true),
        ModelInfo("qwen2.5-3b", "Qwen 2.5 · 3B", "Alibaba", "Qwen Research", "Qwen/Qwen2.5-3B-Instruct-GGUF",
            "qwen2.5-3b-instruct-q4_k_m.gguf", 2_104_932_768, "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
            7.0, "Smarter; best on newer phones.", retired = true),
        ModelInfo("llama3.2-1b", "Llama 3.2 · 1B", "Meta", "Llama 3.2 Community", "bartowski/Llama-3.2-1B-Instruct-GGUF",
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf", 807_694_464, "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
            3.0, "Small and quick.", retired = true),
        ModelInfo("llama3.2-3b", "Llama 3.2 · 3B", "Meta", "Llama 3.2 Community", "bartowski/Llama-3.2-3B-Instruct-GGUF",
            "Llama-3.2-3B-Instruct-Q4_K_M.gguf", 2_019_377_696, "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
            7.0, "Good conversation; newer phones.", retired = true),
        ModelInfo("gemma3-4b", "Gemma 3 · 4B", "Google", "Gemma Terms", "ggml-org/gemma-3-4b-it-GGUF",
            "gemma-3-4b-it-Q4_K_M.gguf", 2_489_757_856, "882e8d2db44dc554fb0ea5077cb7e4bc49e7342a1f0da57901c0802ea21a0863",
            8.0, "Smartest here; flagship phones.", retired = true),
        ModelInfo("smollm2-1.7b", "SmolLM2 · 1.7B", "Hugging Face (independent)", "Apache 2.0", "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF",
            "smollm2-1.7b-instruct-q4_k_m.gguf", 1_055_609_536, "decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33",
            4.0, "Fully open model from an independent lab.", retired = true),
        ModelInfo("phi3.5-mini", "Phi 3.5 mini · 3.8B", "Microsoft", "MIT", "bartowski/Phi-3.5-mini-instruct-GGUF",
            "Phi-3.5-mini-instruct-Q4_K_M.gguf", 2_393_232_672, "e4165e3a71af97f1b4820da61079826d8752a2088e313af0c7d346796c38eff5",
            8.0, "Good at reasoning; flagship phones.", retired = true),
    )

    /** Whisper speech-recognition models (ggerganov/whisper.cpp on Hugging Face, MIT). Checked 2026-11-23. */
    val speech = listOf(
        ModelInfo("whisper-tiny.en", "Whisper tiny · English", "OpenAI (open model)", "MIT", "ggerganov/whisper.cpp",
            "ggml-tiny.en-q5_1.bin", 32_166_155, "c77c5766f1cef09b6b7d47f21b546cbddd4157886b3b5d6d4f709e91e66c7c2b", 2.0,
            "Fastest. Good for short commands on older phones.", ModelInfo.Kind.SPEECH),
        ModelInfo("whisper-base.en", "Whisper base · English", "OpenAI (open model)", "MIT", "ggerganov/whisper.cpp",
            "ggml-base.en-q5_1.bin", 59_721_011, "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f", 3.0,
            "Recommended: accurate and quick on most phones.", ModelInfo.Kind.SPEECH),
        ModelInfo("whisper-small.en", "Whisper small · English", "OpenAI (open model)", "MIT", "ggerganov/whisper.cpp",
            "ggml-small.en-q5_1.bin", 190_098_681, "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30", 6.0,
            "Most accurate. Takes a few seconds on mid-range phones.", ModelInfo.Kind.SPEECH),
        ModelInfo("whisper-base", "Whisper base · 99 languages", "OpenAI (open model)", "MIT", "ggerganov/whisper.cpp",
            "ggml-base-q5_1.bin", 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", 3.0,
            "Understands many languages (detects which one you speak).", ModelInfo.Kind.SPEECH),
        ModelInfo("whisper-small", "Whisper small · 99 languages", "OpenAI (open model)", "MIT", "ggerganov/whisper.cpp",
            "ggml-small-q5_1.bin", 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", 6.0,
            "Many languages, more accurate, slower.", ModelInfo.Kind.SPEECH),
    )
    /** Models the user added from Hugging Face links (kept by CustomModels; set at start and when they change). */
    @Volatile var custom: List<ModelInfo> = emptyList()
    /** Every chat model: the catalog and the custom ones. */
    val chats get() = all + custom
    val everything get() = chats + speech
    fun byId(id: String?) = everything.firstOrNull { it.id == id }
    /** Only chat models (a speech model id must never be used to answer). */
    fun chatById(id: String?) = chats.firstOrNull { it.id == id }
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val id: String, val done: Long, val total: Long) : DownloadState
    /** Waiting [inSeconds] before resuming after a dropped connection. */
    data class Retrying(val id: String, val done: Long, val total: Long, val inSeconds: Int) : DownloadState
    data class Verifying(val id: String) : DownloadState
    data class Failed(val id: String, val message: String) : DownloadState
}

/** Downloaded models live in the app's private storage (never backed up). Downloads resume after interruption. */
class ModelStore(private val context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<Set<String>> = _installed
    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download

    fun fileFor(m: ModelInfo) = File(dir, m.file)
    private fun scan() = ModelCatalog.everything.filter { File(dir, it.file).length() == it.bytes }.map { it.id }.toSet()

    fun freeBytes() = dir.usableSpace

    fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Downloads [m], resuming from any partial file. Dropped connections are retried with backoff (2 s … 60 s,
     * up to [MAX_TRIES] tries in a row without progress), so a flaky network or a sleeping phone doesn't lose
     * work. Run it from [ModelDownloadService] so Android keeps it alive with the screen off.
     */
    suspend fun download(m: ModelInfo, allowed: (OnlineSource) -> Boolean) = withContext(Dispatchers.IO) {
        val part = File(dir, m.file + ".part")
        try {
            if (freeBytes() < m.bytes - part.length() + 200_000_000) throw IOException("Not enough free space (needs ${m.sizeText})")
            var failures = 0
            while (part.length() < m.bytes) {
                val before = part.length()
                try {
                    transfer(m, part, allowed)
                } catch (e: OfflineException) {
                    throw e
                } catch (e: IOException) {
                    if (part.length() > before) failures = 0 // made progress: start counting again
                    if (++failures >= MAX_TRIES) throw IOException("Connection kept dropping (${e.message}). Tap Download to resume.")
                    val wait = minOf(60L, 1L shl failures) // 2, 4, 8, 16, 32, 60 s
                    _download.value = DownloadState.Retrying(m.id, part.length(), m.bytes, wait.toInt())
                    kotlinx.coroutines.delay(wait * 1000)
                }
            }
            _download.value = DownloadState.Verifying(m.id)
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().buffered(1 shl 20).use { s ->
                val buf = ByteArray(1 shl 20)
                while (true) { currentCoroutineContext().ensureActive(); val n = s.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != m.sha256) { part.delete(); throw IOException("The download was damaged (checksum mismatch). Try again.") }
            // Custom models: make sure it really is a model file llama.cpp can read (it starts with "GGUF").
            if (m.custom && part.inputStream().use { input -> ByteArray(4).also { b -> input.read(b) }.toString(Charsets.US_ASCII) } != "GGUF") {
                part.delete(); throw IOException("That file isn't a GGUF model.")
            }
            part.renameTo(fileFor(m))
            _installed.value = scan()
            _download.value = DownloadState.Idle
        } catch (e: kotlinx.coroutines.CancellationException) {
            _download.value = DownloadState.Idle // the partial file stays, so the next try resumes
            throw e
        } catch (e: Exception) {
            _download.value = DownloadState.Failed(m.id, e.message ?: "Download failed")
        }
    }

    /** One connection: appends to [part] from where it stopped. Returns when the server closes the stream. */
    private suspend fun transfer(m: ModelInfo, part: File, allowed: (OnlineSource) -> Boolean) {
        var done = part.length()
        _download.value = DownloadState.Running(m.id, done, m.bytes)
        val c = Net.open(OnlineSource.MODELS, m.url, allowed, range = done)
        try {
            if (done > 0 && c.responseCode != 206) { part.delete(); done = 0 } // server ignored the range
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { input ->
                FileOutputStream(part, done > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 4_000_000) { lastReport = done; _download.value = DownloadState.Running(m.id, done, m.bytes) }
                    }
                }
            }
            if (done < m.bytes) throw IOException("connection closed early")
        } finally { c.disconnect() }
    }

    companion object { const val MAX_TRIES = 6 }

    /** The model's saved reading of the fixed prompt (see Native.prefill); rebuilt when missing or stale. */
    fun stateFor(m: ModelInfo) = File(dir, m.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".prompt-state")

    /** Re-reads which models are downloaded (after custom models are added or removed). */
    fun rescan() { _installed.value = scan() }

    fun delete(m: ModelInfo) {
        fileFor(m).delete(); File(dir, m.file + ".part").delete(); stateFor(m).delete()
        _installed.value = scan()
    }
}
