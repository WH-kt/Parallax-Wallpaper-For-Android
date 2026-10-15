// Fork addition (2026): verify shared cover geometry, transforms, and scale constraints.
package com.zeaze.tianyinwallpaper.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RasterImageGeometryTest {
    private val canvasWidth = 1080f
    private val canvasHeight = 2400f
    private val canvasAspect = canvasWidth / canvasHeight

    private fun geometry(transform: RasterImageTransform, imageWidth: Float = 900f, imageHeight: Float = 1200f) =
        RasterImageGeometry.of(canvasWidth, canvasHeight, imageWidth, imageHeight, transform)

    private fun textureAt(matrix: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        // 输入画布比例坐标，左上为 0,0；GL 的输入纵坐标相反。
        return (matrix[0] * x + matrix[4] * (1f - y) + matrix[12]) to
            (matrix[1] * x + matrix[5] * (1f - y) + matrix[13])
    }

    @Test
    fun imageViewMatrix_scalesUpRatherThanDownAndPreservesAspect() {
        val g = geometry(RasterImageTransform())
        val matrix = g.toImageMatrix(900f, 1200f)
        assertEquals(2f, matrix[0], 0.0001f)
        assertEquals(2f, matrix[4], 0.0001f)
        assertEquals(-360f, matrix[2], 0.01f)
        assertEquals(0f, matrix[5], 0.01f)

        // Drawable 因设备密度而改变尺寸时，仍应铺到相同的画布边界。
        val denseDrawable = g.toImageMatrix(1800f, 2400f)
        assertEquals(1f, denseDrawable[0], 0.0001f)
        assertEquals(matrix[2], denseDrawable[2], 0.0001f)
        assertEquals(1440f, denseDrawable[0] * 1800f + denseDrawable[2], 0.01f)
    }

    @Test
    fun cover_keepsAspectAndCropsTheLongAxis() {
        for ((w, h) in listOf(4000f to 1000f, 1000f to 4000f, 1080f to 2400f, 500f to 500f)) {
            val g = geometry(RasterImageTransform(), w, h)
            assertEquals(w / h, g.width / g.height, 0.0001f)
            assertTrue(g.width >= canvasWidth - 0.01f)
            assertTrue(g.height >= canvasHeight - 0.01f)
            assertTrue(g.width <= canvasWidth + 0.01f || g.height <= canvasHeight + 0.01f)
            assertEquals(canvasWidth / 2f, g.centerX, 0.0001f)
            assertEquals(canvasHeight / 2f, g.centerY, 0.0001f)
        }
    }

    @Test
    fun drag_reachesBothImageEdgesWithNoDeadSpace() {
        val right = geometry(RasterImageTransform(offsetX = 100f))
        val left = geometry(RasterImageTransform(offsetX = -100f))
        assertEquals(0f, right.centerX - right.width / 2f, 0.01f)
        assertEquals(canvasWidth, left.centerX + left.width / 2f, 0.01f)
        assertEquals(canvasHeight / 2f, right.centerY, 0.01f)

        val tall = geometry(RasterImageTransform(offsetY = 100f), 1000f, 4000f)
        assertEquals(canvasHeight, tall.centerY + tall.height / 2f, 0.01f)
    }

    @Test
    fun cover_containsEveryCanvasCornerEvenWhenRotatedAndDragged() {
        for (aspect in listOf(0.05f, 0.25f, 0.45f, 1f, 4f, 20f)) {
            for (rotation in listOf(-135f, -45f, 0f, 15f, 45f, 90f, 180f, 359f)) {
                for (scale in listOf(0.1f, 1f, 3f, 8f, 99f)) {
                    for ((x, y) in listOf(0f to 0f, -99f to 99f, 99f to -99f)) {
                        val t = RasterImageTransform(scale, x, y, rotation)
                        val g = geometry(t, 1000f * aspect, 1000f)
                        val m = g.toTextureMatrix(canvasWidth, canvasHeight)
                        for ((cx, cy) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
                            val (u, v) = textureAt(m, cx, cy)
                            assertTrue("角落露白: aspect=$aspect $t corner=$cx,$cy -> $u,$v",
                                u >= -0.00002f && u <= 1.00002f && v >= -0.00002f && v <= 1.00002f)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun editorAndRenderer_mapTheSameImagePointToTheSameScreenPoint() {
        for (rotation in listOf(0f, 30f, 90f, -70f)) {
            val g = geometry(RasterImageTransform(3f, 0.3f, -0.4f, rotation))
            val forward = g.toImageMatrix(900f, 1200f)
            val inverse = g.toTextureMatrix(canvasWidth, canvasHeight)
            for ((x, y) in listOf(0f to 0f, 0.2f to 0.7f, 0.5f to 0.5f, 1f to 1f)) {
                val (u, v) = textureAt(inverse, x, y)
                val screenX = forward[0] * u * 900f + forward[1] * v * 1200f + forward[2]
                val screenY = forward[3] * u * 900f + forward[4] * v * 1200f + forward[5]
                assertEquals(x * canvasWidth, screenX, 0.01f)
                assertEquals(y * canvasHeight, screenY, 0.01f)
            }
        }
    }

    @Test
    fun pinch_preservesImagePointUnderMovingFingers() {
        val before = RasterImageTransform(scale = 2f)
        val after = before.gestured(canvasAspect, 0.75f, 0.3f, 0.4f, 0.4f, 0.45f, 1.5f, 12f)
        val pointBefore = textureAt(geometry(before).toTextureMatrix(canvasWidth, canvasHeight), 0.3f, 0.4f)
        val pointAfter = textureAt(geometry(after).toTextureMatrix(canvasWidth, canvasHeight), 0.4f, 0.45f)
        assertEquals(pointBefore.first, pointAfter.first, 0.0001f)
        assertEquals(pointBefore.second, pointAfter.second, 0.0001f)
    }

    @Test
    fun pan_followsFingerByTheSamePixelDistance() {
        val before = RasterImageTransform(scale = 2f)
        val after = before.gestured(canvasAspect, 0.75f, 0.5f, 0.5f, 0.6f, 0.6f)
        assertEquals(108f, geometry(after).centerX - geometry(before).centerX, 0.01f)
        assertEquals(240f, geometry(after).centerY - geometry(before).centerY, 0.01f)
    }

    @Test
    fun pinch_usesActualScaleAtLimitsAndCanReverseImmediately() {
        val atMaximum = RasterImageTransform(scale = 3f)
        val clamped = atMaximum.gestured(canvasAspect, 0.75f, 0.3f, 0.4f, 0.3f, 0.4f, 2f)
        assertEquals(3f, clamped.scale, 0f)
        assertEquals(0f, clamped.offsetX, 0.0001f)
        assertEquals(0f, clamped.offsetY, 0.0001f)
        val reversed = clamped.gestured(canvasAspect, 0.75f, 0.3f, 0.4f, 0.3f, 0.4f, 0.5f)
        assertEquals(1.5f, reversed.scale, 0.0001f)
        val atMinimum = reversed.gestured(canvasAspect, 0.75f, 0.3f, 0.4f, 0.3f, 0.4f, 0.01f)
        assertEquals(1f, atMinimum.scale, 0f)
        assertEquals(0f, atMinimum.offsetY, 0.0001f)
    }
}
