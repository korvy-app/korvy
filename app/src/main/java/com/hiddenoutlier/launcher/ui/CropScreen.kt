package com.hiddenoutlier.launcher.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hiddenoutlier.launcher.core.CropFrame
import com.hiddenoutlier.launcher.data.Backgrounds

/**
 * Choose which screen-shaped part of a picture becomes the background. Drag the frame; pinch to zoom in.
 * Pictures that are already about screen-shaped skip this step.
 */
@Composable
fun CropScreen(
    uri: Uri,
    position: Int,
    total: Int,
    backgrounds: Backgrounds,
    onUse: (RectF?) -> Unit,
    onSkip: () -> Unit,
) {
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    val aspect = remember { backgrounds.screenAspect() }
    LaunchedEffect(uri) {
        val b = backgrounds.preview(uri)
        when {
            b == null -> failed = true
            !backgrounds.needsCrop(b.width, b.height) -> onUse(null) // already screen-shaped
            else -> bmp = b
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().testTag("crop")) {
        Text(
            if (total > 1) "Picture $position of $total: drag the frame, pinch to zoom" else "Drag the frame, pinch to zoom",
            color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp),
        )
        val b = bmp
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                failed -> Text("Couldn't open this picture.", color = Color.White)
                b == null -> CircularProgressIndicator()
                else -> CropArea(b, aspect) { frame -> onUse(RectF(frame.left, frame.top, frame.right, frame.bottom)) }
            }
        }
        if (b == null || failed) Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onSkip) { Text("Skip") }
        }
    }
}

@Composable
private fun CropArea(b: Bitmap, aspect: Float, onUse: (CropFrame) -> Unit) {
    var frame by remember(b) { mutableStateOf(CropFrame.of(b.width, b.height, aspect)) }
    Column {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            val cw = constraints.maxWidth.toFloat(); val ch = constraints.maxHeight.toFloat()
            val scale = minOf(cw / b.width, ch / b.height)
            val dw = b.width * scale; val dh = b.height * scale
            val ox = (cw - dw) / 2; val oy = (ch - dh) / 2
            Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier.fillMaxSize().testTag("crop_area").pointerInput(b) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // Pinching out (zoom > 1) enlarges the picture, i.e. shrinks the frame.
                        frame = frame.transformed(b.width, b.height, aspect, pan.x / dw, pan.y / dh, 1f / zoom)
                    }
                }
            ) {
                val l = ox + frame.left * dw; val t = oy + frame.top * dh
                val w = frame.width * dw; val h = frame.height * dh
                clipRect(l, t, l + w, t + h, clipOp = ClipOp.Difference) {
                    drawRect(Color.Black.copy(alpha = 0.6f), Offset(ox, oy), Size(dw, dh))
                }
                drawRect(Color.White, Offset(l, t), Size(w, h), style = Stroke(width = 2.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { frame = CropFrame.of(b.width, b.height, aspect) }) { Text("Reset") }
            Button(onClick = { onUse(frame) }, modifier = Modifier.testTag("crop_use")) { Text("Use this part") }
        }
    }
}
