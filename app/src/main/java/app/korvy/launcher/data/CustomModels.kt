package app.korvy.launcher.data

import android.content.Context
import app.korvy.launcher.assistant.ModelCatalog
import app.korvy.launcher.assistant.ModelInfo
import app.korvy.launcher.core.HfFile
import app.korvy.launcher.core.HfLink
import app.korvy.launcher.platform.Net
import app.korvy.launcher.platform.OnlineSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Chat models the user added from Hugging Face links (Pro). Kept on the phone only. Their size and SHA-256 come
 * from Hugging Face's own file record, so downloads are checked just like the catalog's.
 */
class CustomModels(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("custom_models", Context.MODE_PRIVATE)
    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<ModelInfo>> = _list

    init { ModelCatalog.custom = _list.value }

    sealed interface AddResult
    data class Added(val model: ModelInfo) : AddResult
    data class Problem(val error: HfLink.Error?) : AddResult // null error = couldn't reach or find the file

    /** Looks the file up on Hugging Face (Model downloads must be allowed) and adds it to the list. */
    suspend fun add(link: String, allowed: (OnlineSource) -> Boolean): AddResult = withContext(Dispatchers.IO) {
        val f = when (val r = HfLink.parse(link)) { is HfLink.Ok -> r.file; is HfLink.Error -> return@withContext Problem(r) }
        val info = runCatching {
            val url = "https://huggingface.co/api/models/${f.repo}/tree/${android.net.Uri.encode(f.rev)}" +
                (if (f.dir.isEmpty()) "" else "/" + f.dir.split('/').joinToString("/") { android.net.Uri.encode(it) })
            val arr = JSONArray(Net.getText(OnlineSource.MODELS, url, allowed))
            (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("path") == f.path }
        }.getOrNull() ?: return@withContext Problem(null)
        val bytes = info.optLong("size")
        val sha = info.optJSONObject("lfs")?.optString("oid").orEmpty()
        if (bytes <= 0 || sha.length != 64) return@withContext Problem(null)
        val m = model(f, bytes, sha)
        save(_list.value.filterNot { it.id == m.id } + m)
        Added(m)
    }

    fun remove(id: String) = save(_list.value.filterNot { it.id == id })

    private fun model(f: HfFile, bytes: Long, sha: String) = ModelInfo(
        id = HfLink.id(f), name = f.fileName.removeSuffix(".gguf"), maker = f.repo.substringBefore('/'),
        license = "", repo = f.repo, file = "custom-" + sha.take(16) + ".gguf", bytes = bytes, sha256 = sha,
        // A rough guide: the file plus room for the conversation and Android.
        minRamGb = bytes / 1e9 * 1.3 + 2, note = "", path = f.path, rev = f.rev, custom = true,
    )

    private fun load(): List<ModelInfo> = prefs.getString("list", "").orEmpty().lines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size != 5) return@mapNotNull null
        val bytes = p[3].toLongOrNull() ?: return@mapNotNull null
        model(HfFile(p[0], p[1], p[2]), bytes, p[4])
    }

    private fun save(list: List<ModelInfo>) {
        prefs.edit().putString("list", list.joinToString("\n") { "${it.repo}\t${it.rev}\t${it.path}\t${it.bytes}\t${it.sha256}" }).apply()
        ModelCatalog.custom = list
        _list.value = list
    }
}
