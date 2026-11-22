package com.hiddenoutlier.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hiddenoutlier.launcher.data.AppEntry
import com.hiddenoutlier.launcher.data.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An app icon, loaded off the main thread and cached; a soft placeholder shows for the first frame. */
@Composable
fun AppIcon(app: AppEntry, repo: AppRepository, size: Dp = 52.dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, app.key, px) {
        value = withContext(Dispatchers.IO) { repo.icon(app.key, px) }
    }
    val bmp = icon
    if (bmp != null) Image(bmp, contentDescription = null, modifier = modifier.size(size))
    else Box(modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)))
}
