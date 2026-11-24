package app.korvy.launcher.ui

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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.korvy.launcher.core.slideshowIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.korvy.launcher.data.Backgrounds
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
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        // The picture follows the clock (which slot of the interval we're in), not a countdown: a countdown
        // restarts every time the home screen is left, so with frequent app switching it never advanced.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        if (slideshow && files.size > 1) LaunchedEffect(files.size, minutes) {
            val power = context.getSystemService(PowerManager::class.java)
            val period = minutes * 60_000L
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    // Battery saver: keep the current picture until battery saver is off.
                    if (!power.isPowerSaveMode) now = System.currentTimeMillis()
                    delay(period - System.currentTimeMillis() % period + 50) // wake just after the next switch
                }
            }
        }
        val index = if (slideshow && files.size > 1) slideshowIndex(now, minutes, files.size) else 0
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
