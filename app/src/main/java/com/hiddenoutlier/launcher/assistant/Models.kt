package com.hiddenoutlier.launcher.assistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hiddenoutlier.launcher.platform.Net
import com.hiddenoutlier.launcher.platform.OnlineSource
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
) {
    val url get() = "https://huggingface.co/$repo/resolve/main/$file"
    val sizeText get() = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
}

object ModelCatalog {
    val all = listOf(
        ModelInfo("qwen2.5-0.5b", "Qwen 2.5 · 0.5B", "Alibaba", "Apache 2.0", "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf", 491_400_032, "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            2.5, "Smallest and fastest. Good at picking actions, basic at chat."),
        ModelInfo("qwen2.5-1.5b", "Qwen 2.5 · 1.5B", "Alibaba", "Apache 2.0", "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf", 1_117_320_736, "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            4.0, "Recommended balance of speed and smarts."),
        ModelInfo("qwen2.5-3b", "Qwen 2.5 · 3B", "Alibaba", "Qwen Research", "Qwen/Qwen2.5-3B-Instruct-GGUF",
            "qwen2.5-3b-instruct-q4_k_m.gguf", 2_104_932_768, "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
            7.0, "Smarter; best on newer phones."),
        ModelInfo("llama3.2-1b", "Llama 3.2 · 1B", "Meta", "Llama 3.2 Community", "bartowski/Llama-3.2-1B-Instruct-GGUF",
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf", 807_694_464, "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
            3.0, "Small and quick."),
        ModelInfo("llama3.2-3b", "Llama 3.2 · 3B", "Meta", "Llama 3.2 Community", "bartowski/Llama-3.2-3B-Instruct-GGUF",
            "Llama-3.2-3B-Instruct-Q4_K_M.gguf", 2_019_377_696, "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
            7.0, "Good conversation; newer phones."),
        ModelInfo("gemma3-1b", "Gemma 3 · 1B", "Google", "Gemma Terms", "ggml-org/gemma-3-1b-it-GGUF",
            "gemma-3-1b-it-Q4_K_M.gguf", 806_058_240, "8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135",
            3.0, "Strong for its size."),
        ModelInfo("gemma3-4b", "Gemma 3 · 4B", "Google", "Gemma Terms", "ggml-org/gemma-3-4b-it-GGUF",
            "gemma-3-4b-it-Q4_K_M.gguf", 2_489_757_856, "882e8d2db44dc554fb0ea5077cb7e4bc49e7342a1f0da57901c0802ea21a0863",
            8.0, "Smartest here; flagship phones."),
        ModelInfo("smollm2-1.7b", "SmolLM2 · 1.7B", "Hugging Face (independent)", "Apache 2.0", "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF",
            "smollm2-1.7b-instruct-q4_k_m.gguf", 1_055_609_536, "decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33",
            4.0, "Fully open model from an independent lab."),
        ModelInfo("phi3.5-mini", "Phi 3.5 mini · 3.8B", "Microsoft", "MIT", "bartowski/Phi-3.5-mini-instruct-GGUF",
            "Phi-3.5-mini-instruct-Q4_K_M.gguf", 2_393_232_672, "e4165e3a71af97f1b4820da61079826d8752a2088e313af0c7d346796c38eff5",
            8.0, "Good at reasoning; flagship phones."),
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id }
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val id: String, val done: Long, val total: Long) : DownloadState
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
    private fun scan() = ModelCatalog.all.filter { File(dir, it.file).length() == it.bytes }.map { it.id }.toSet()

    fun freeBytes() = dir.usableSpace

    fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    suspend fun download(m: ModelInfo, allowed: (OnlineSource) -> Boolean) = withContext(Dispatchers.IO) {
        val part = File(dir, m.file + ".part")
        try {
            if (freeBytes() < m.bytes - part.length() + 200_000_000) throw IOException("Not enough free space (needs ${m.sizeText})")
            var done = part.length()
            _download.value = DownloadState.Running(m.id, done, m.bytes)
            if (done < m.bytes) {
                val c = Net.open(OnlineSource.MODELS, m.url, allowed, range = done)
                try {
                    if (done > 0 && c.responseCode != 206) { part.delete(); done = 0 } // server ignored the range
                    if (c.responseCode !in 200..299) throw IOException("Download failed: HTTP ${c.responseCode}")
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
                } finally { c.disconnect() }
            }
            _download.value = DownloadState.Verifying(m.id)
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().buffered(1 shl 20).use { s ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = s.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != m.sha256) { part.delete(); throw IOException("The download was damaged (checksum mismatch). Try again.") }
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

    fun delete(m: ModelInfo) {
        fileFor(m).delete(); File(dir, m.file + ".part").delete()
        _installed.value = scan()
    }
}
