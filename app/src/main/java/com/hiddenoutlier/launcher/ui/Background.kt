package com.hiddenoutlier.launcher.ui

import android.graphics.Bitmap
import android.os.PowerManager
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hiddenoutlier.launcher.data.Backgrounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The user's own background, or nothing (the system wallpaper shows through). With the slideshow on, images
 * crossfade every [minutes]; it only runs while the home screen is visible and not in battery saver.
 */
@Composable
fun LauncherBackground(files: List<File>, slideshow: Boolean, minutes: Int) {
    val animations = LocalAnimations.current
    if (files.isNotEmpty()) {
        var index by remember(files) { mutableIntStateOf(0) }
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        if (slideshow && files.size > 1) LaunchedEffect(files, minutes) {
            val power = context.getSystemService(PowerManager::class.java)
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(minutes * 60_000L)
                    if (!power.isPowerSaveMode) index = (index + 1) % files.size
                }
            }
        }
        val file = files[index.coerceIn(0, files.lastIndex)]
        Crossfade(targetState = file, animationSpec = Motion.fade(animations, 1500), label = "background") { f ->
            val bmp by produceState<Bitmap?>(null, f) { value = withContext(Dispatchers.IO) { Backgrounds.load(f) } }
            bmp?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
    // Soft shading top and bottom keeps the clock and labels readable on any picture.
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.35f), 0.25f to Color.Transparent,
                0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f),
            )
        )
    )
}
