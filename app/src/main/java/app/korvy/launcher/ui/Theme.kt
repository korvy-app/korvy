package app.korvy.launcher.ui

import android.os.Build
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Whether animations are on (Settings → Animations; default depends on the device tier). */
val LocalAnimations = staticCompositionLocalOf { true }

/** Animation specs that collapse to an instant change when animations are off. */
object Motion {
    fun <T> spring(on: Boolean): FiniteAnimationSpec<T> =
        if (on) spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow) else snap()
    fun fade(on: Boolean, millis: Int = 220): FiniteAnimationSpec<Float> = if (on) tween(millis) else snap()
}

@Composable
fun LauncherTheme(animations: Boolean, content: @Composable () -> Unit) {
    // Always dark: text and icons sit over wallpapers, and dark surfaces read best on top of photos.
    val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(LocalContext.current)
    else darkColorScheme(primary = Color(0xFF9AD1D4), secondary = Color(0xFFCCDBDC))
    CompositionLocalProvider(LocalAnimations provides animations) {
        MaterialTheme(colorScheme = scheme) {
            // Screens draw their own backgrounds (no Surface), so set the default text colour here.
            CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
        }
    }
}
