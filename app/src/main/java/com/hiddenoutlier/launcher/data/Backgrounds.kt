package com.hiddenoutlier.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.RectF
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

    /** The screen's shape (width / height), used for the crop frame. */
    fun screenAspect(): Float {
        val dm = context.resources.displayMetrics
        return minOf(dm.widthPixels, dm.heightPixels).toFloat() / maxOf(dm.widthPixels, dm.heightPixels)
    }

    /** A small copy of a picked photo for the crop screen (long side ~1600 px). */
    suspend fun preview(uri: Uri): Bitmap? = withContext(Dispatchers.IO) { runCatching { decodeScaled(uri, 1600) }.getOrNull() }

    /** Pictures that aren't roughly screen-shaped get a crop step; these don't need one. */
    fun needsCrop(width: Int, height: Int): Boolean {
        val a = width.toFloat() / height
        return a > screenAspect() * 1.08f || a < screenAspect() * 0.85f
    }

    /** Imports pictures as they are (centre-cropped when shown). */
    suspend fun import(uris: List<Uri>) = withContext(Dispatchers.IO) {
        for ((i, uri) in uris.withIndex()) importOne(uri, null, i)
    }

    /**
     * Imports one picture, keeping only [crop] (fractions of the width and height, from the crop screen).
     * The saved copy is the crop itself, scaled to the screen, so it is shown exactly as framed.
     */
    suspend fun importOne(uri: Uri, crop: RectF?, n: Int = 0) = withContext(Dispatchers.IO) {
        val dm = context.resources.displayMetrics
        val longSide = max(dm.widthPixels, dm.heightPixels)
        val bmp = runCatching { if (crop == null) decodeScaled(uri, longSide) else decodeCropped(uri, crop, longSide) }.getOrNull() ?: return@withContext
        val out = File(dir, "bg-${System.currentTimeMillis()}-$n.webp")
        out.outputStream().use { s ->
            @Suppress("DEPRECATION")
            val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            bmp.compress(fmt, 88, s)
        }
        bmp.recycle()
        _files.value = list()
    }

    private fun decodeCropped(uri: Uri, crop: RectF, longSide: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { d, info, _ ->
                val w = info.size.width; val h = info.size.height
                val cropH = crop.height() * h
                val scale = minOf(1f, longSide / cropH)
                val tw = (w * scale).toInt().coerceAtLeast(1); val th = (h * scale).toInt().coerceAtLeast(1)
                d.setTargetSize(tw, th)
                d.crop = Rect((crop.left * tw).toInt(), (crop.top * th).toInt(), (crop.right * tw).toInt().coerceAtMost(tw), (crop.bottom * th).toInt().coerceAtMost(th))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val full = decodeScaled(uri, longSide * 2)
        val r = Rect((crop.left * full.width).toInt(), (crop.top * full.height).toInt(), (crop.right * full.width).toInt(), (crop.bottom * full.height).toInt())
        val cut = Bitmap.createBitmap(full, r.left, r.top, r.width().coerceAtLeast(1), r.height().coerceAtLeast(1))
        val scale = minOf(1f, longSide.toFloat() / cut.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(cut, (cut.width * scale).toInt(), (cut.height * scale).toInt(), true) else cut
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
