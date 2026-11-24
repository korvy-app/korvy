package app.korvy.launcher.assistant

import android.content.Context
import app.korvy.launcher.platform.Net
import app.korvy.launcher.platform.OnlineSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import app.korvy.launcher.platform.OfflineException

/** One speaker inside a voice pack. */
data class NeuralSpeaker(val id: Int, val name: String, val accent: String)

/**
 * A downloadable natural-sounding voice, run entirely on the phone by sherpa-onnx. Archives come from the
 * sherpa-onnx project's GitHub releases; sizes and SHA-256 checked on 2026-11-22.
 */
data class NeuralVoicePack(
    val id: String,
    val name: String,
    val kind: Kind,
    val license: String,
    val archive: String,
    val bytes: Long,
    val sha256: String,
    /** Folder inside the archive. */
    val folder: String,
    val modelFile: String,
    val speakers: List<NeuralSpeaker>,
    val note: String,
    /** GitHub release tag the archive is attached to. */
    val release: String = "tts-models",
) {
    /** KOKORO and VITS are voices; KWS is the wake-word (keyword spotting) model. */
    enum class Kind { KOKORO, VITS, KWS }
    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/$release/$archive"
    val sizeText get() = "${bytes / 1_000_000} MB"
}

object NeuralVoiceCatalog {
    val all = listOf(
        NeuralVoicePack(
            "kokoro-en", "Kokoro (English)", NeuralVoicePack.Kind.KOKORO, "Apache 2.0",
            "kokoro-int8-en-v0_19.tar.bz2", 103_248_205, "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd",
            "kokoro-int8-en-v0_19", "model.int8.onnx",
            listOf(
                NeuralSpeaker(1, "Bella", "American, female"), NeuralSpeaker(2, "Nicole", "American, female"),
                NeuralSpeaker(3, "Sarah", "American, female"), NeuralSpeaker(4, "Sky", "American, female"),
                NeuralSpeaker(0, "Default", "American, female"),
                NeuralSpeaker(5, "Adam", "American, male"), NeuralSpeaker(6, "Michael", "American, male"),
                NeuralSpeaker(7, "Emma", "British, female"), NeuralSpeaker(8, "Isabella", "British, female"),
                NeuralSpeaker(9, "George", "British, male"), NeuralSpeaker(10, "Lewis", "British, male"),
            ),
            "Most natural. Best on newer phones.",
        ),
        NeuralVoicePack(
            "piper-lessac", "Piper · Lessac (English)", NeuralVoicePack.Kind.VITS, "MIT (Piper) · voice: Blizzard 2013 Lessac",
            "vits-piper-en_US-lessac-medium-int8.tar.bz2", 20_969_179, "f1c6d0295cf16087b05f80fdca5b44daca5cd78e2c425d419a42ba34929805f9",
            "vits-piper-en_US-lessac-medium-int8", "en_US-lessac-medium.onnx",
            listOf(NeuralSpeaker(0, "Lessac", "American, female")),
            "Small and fast. Good for older phones.",
        ),
    )

    /** The wake-word model: a 3.3M-parameter open-vocabulary keyword spotter (Apache 2.0), checked 2026-11-23. */
    val wakeWord = NeuralVoicePack(
        "kws-en", "Wake word (English)", NeuralVoicePack.Kind.KWS, "Apache 2.0",
        "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2", 17_626_723,
        "f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a",
        "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01", "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        emptyList(), "Listens for your wake word only; nothing else is recognised or kept.", release = "kws-models",
    )
    val packs get() = all + wakeWord
    fun byId(id: String?) = packs.firstOrNull { it.id == id }
}

/** Downloaded voice packs, unpacked in private storage. Downloads resume and are checksum-verified. */
class VoiceStore(context: Context) {
    private val dir = File(context.filesDir, "voices").apply { mkdirs() }
    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<Set<String>> = _installed
    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download

    fun folderFor(p: NeuralVoicePack) = File(dir, p.folder)
    private fun ready(p: NeuralVoicePack) = File(folderFor(p), ".ready").exists()
    private fun scan() = NeuralVoiceCatalog.packs.filter(::ready).map { it.id }.toSet()

    suspend fun download(p: NeuralVoicePack, allowed: (OnlineSource) -> Boolean) = withContext(Dispatchers.IO) {
        val part = File(dir, p.archive + ".part")
        try {
            if (dir.usableSpace < p.bytes * 3) throw IOException("Not enough free space (needs about ${p.bytes * 3 / 1_000_000} MB while unpacking)")
            var failures = 0
            while (part.length() < p.bytes) {
                val before = part.length()
                try { transfer(p, part, allowed) } catch (e: OfflineException) { throw e } catch (e: IOException) {
                    if (part.length() > before) failures = 0
                    if (++failures >= ModelStore.MAX_TRIES) throw IOException("Connection kept dropping (${e.message}). Tap Download to resume.")
                    val wait = minOf(60L, 1L shl failures)
                    _download.value = DownloadState.Retrying(p.id, part.length(), p.bytes, wait.toInt())
                    kotlinx.coroutines.delay(wait * 1000)
                }
            }
            _download.value = DownloadState.Verifying(p.id)
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().buffered(1 shl 20).use { s -> val b = ByteArray(1 shl 20); while (true) { val n = s.read(b); if (n < 0) break; digest.update(b, 0, n) } }
            if (digest.digest().joinToString("") { "%02x".format(it) } != p.sha256) { part.delete(); throw IOException("The download was damaged (checksum mismatch). Try again.") }
            unpack(part, p)
            part.delete()
            File(folderFor(p), ".ready").writeText("ok")
            _installed.value = scan()
            _download.value = DownloadState.Idle
        } catch (e: kotlinx.coroutines.CancellationException) {
            _download.value = DownloadState.Idle; throw e
        } catch (e: Exception) {
            _download.value = DownloadState.Failed(p.id, e.message ?: "Download failed")
        }
    }

    private suspend fun transfer(p: NeuralVoicePack, part: File, allowed: (OnlineSource) -> Boolean) {
        var done = part.length()
        _download.value = DownloadState.Running(p.id, done, p.bytes)
        val c = Net.open(OnlineSource.VOICES, p.url, allowed, range = done)
        try {
            if (done > 0 && c.responseCode != 206) { part.delete(); done = 0 }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { input ->
                FileOutputStream(part, done > 0).use { out ->
                    val buf = ByteArray(256 * 1024); var last = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done - last > 2_000_000) { last = done; _download.value = DownloadState.Running(p.id, done, p.bytes) }
                    }
                }
            }
            if (done < p.bytes) throw IOException("connection closed early")
        } finally { c.disconnect() }
    }

    /** Unpacks the archive into private storage, refusing any entry that would land outside its folder. */
    private fun unpack(archive: File, p: NeuralVoicePack) {
        val root = dir.canonicalFile
        folderFor(p).deleteRecursively()
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 16))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val out = File(dir, e.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw IOException("Bad path in voice archive: ${e.name}")
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it, 1 shl 16) }
            }
        }
        if (!File(folderFor(p), p.modelFile).exists()) throw IOException("The voice pack is missing its model file.")
    }

    fun delete(p: NeuralVoicePack) {
        folderFor(p).deleteRecursively(); File(dir, p.archive + ".part").delete()
        _installed.value = scan()
    }
}
