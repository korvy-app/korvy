package com.hiddenoutlier.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * The user's own background images. Each picked photo is decoded once, scaled to the screen size and saved
 * in private storage, so drawing it later is cheap and the original photo is never needed again.
 */
class Backgrounds(private val context: Context) {
    private val dir = File(context.filesDir, "backgrounds").apply { mkdirs() }
    private val _files = MutableStateFlow(list())
    val files: StateFlow<List<File>> = _files

    private fun list() = dir.listFiles { f -> f.extension == "webp" || f.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()

    suspend fun import(uris: List<Uri>) = withContext(Dispatchers.IO) {
        val dm = context.resources.displayMetrics
        val target = max(dm.widthPixels, dm.heightPixels)
        for ((i, uri) in uris.withIndex()) {
            val bmp = runCatching { decodeScaled(uri, target) }.getOrNull() ?: continue
            val out = File(dir, "bg-${System.currentTimeMillis()}-$i.webp")
            out.outputStream().use { s ->
                @Suppress("DEPRECATION")
                val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                bmp.compress(fmt, 88, s)
            }
            bmp.recycle()
        }
        _files.value = list()
    }

    fun remove(f: File) {
        f.delete(); _files.value = list()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }; _files.value = list()
    }

    private fun decodeScaled(uri: Uri, longSide: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { d, info, _ ->
                val s = info.size
                val scale = longSide.toFloat() / max(s.width, s.height)
                if (scale < 1f) d.setTargetSize((s.width * scale).toInt(), (s.height * scale).toInt())
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longSide) sample *= 2
        return context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })!!
        }
    }

    companion object {
        /** Decodes a saved (already screen-sized) background. Call off the main thread. */
        fun load(f: File): Bitmap? = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
    }
}
