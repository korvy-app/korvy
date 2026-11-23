package com.hiddenoutlier.launcher

import com.hiddenoutlier.launcher.core.CropFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class CropFrameTest {
    private val phone = 1080f / 2340f // ~0.46

    @Test fun landscapePictureGetsAFullHeightPortraitFrameInTheMiddle() {
        val f = CropFrame.of(4000, 3000, phone)
        assertEquals(1f, f.height, 1e-4f)
        assertEquals(phone * 3000 / 4000, f.width, 1e-4f)
        assertEquals((1 - f.width) / 2, f.left, 1e-4f)
        // The frame has the screen's shape in pixels.
        assertEquals(phone, f.width * 4000 / (f.height * 3000), 1e-4f)
    }

    @Test fun veryTallPictureGetsAFullWidthFrame() {
        val f = CropFrame.of(1000, 4000, phone)
        assertEquals(1f, f.width, 1e-4f)
        assertEquals(phone, f.width * 1000 / (f.height * 4000), 1e-4f)
    }

    @Test fun draggingStaysInsideThePicture() {
        val f = CropFrame.of(4000, 3000, phone).transformed(4000, 3000, phone, dx = 5f, dy = 0f, zoomBy = 1f)
        assertEquals(1f, f.right, 1e-4f)
        val g = f.transformed(4000, 3000, phone, dx = -5f, dy = 0f, zoomBy = 1f)
        assertEquals(0f, g.left, 1e-4f)
    }

    @Test fun pinchZoomsInButNotPastTheLimitsAndKeepsTheShape() {
        val f = CropFrame.of(4000, 3000, phone).transformed(4000, 3000, phone, 0f, 0f, zoomBy = 0.5f)
        assertEquals(0.5f, f.height, 1e-4f)
        assertEquals(phone, f.width * 4000 / (f.height * 3000), 1e-4f)
        val tiny = f.transformed(4000, 3000, phone, 0f, 0f, zoomBy = 0.01f)
        assertEquals(CropFrame.MIN_ZOOM, tiny.height, 1e-4f)
        val big = f.transformed(4000, 3000, phone, 0f, 0f, zoomBy = 100f)
        assertEquals(1f, big.height, 1e-4f)
    }
}
