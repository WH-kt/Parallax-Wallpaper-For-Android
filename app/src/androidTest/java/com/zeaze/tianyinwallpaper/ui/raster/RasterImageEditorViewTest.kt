// Fork addition (2026): exercise editor drawing, touch handling, and slider-driven transforms on Android.
package com.zeaze.tianyinwallpaper.ui.raster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zeaze.tianyinwallpaper.model.RasterImageTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RasterImageEditorViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun editor(): RasterImageEditorView {
        val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888).apply {
            density = 640 // 故意与设备密度不同，验证 Drawable 尺寸换算。
            eraseColor(Color.WHITE)
        }
        return RasterImageEditorView(instrumentation.targetContext).apply {
            setBitmap(bitmap)
            layout(0, 0, 1080, 2400)
        }
    }

    private fun send(
        view: RasterImageEditorView,
        down: Long,
        time: Long,
        action: Int,
        vararg pointers: Triple<Int, Float, Float>
    ) {
        val properties = pointers.map { (id, _, _) ->
            MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }.toTypedArray()
        val coordinates = pointers.map { (_, x, y) ->
            MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        }.toTypedArray()
        val event = MotionEvent.obtain(down, time, action, pointers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            assertTrue(view.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    @Test
    fun actualImageViewDrawing_coversCanvasAtEveryCorner() = instrumentation.runOnMainSync {
        val view = editor()
        val output = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        for (transform in listOf(
            RasterImageTransform(),
            RasterImageTransform(0.2f, 99f, -99f),
            RasterImageTransform(2f, -99f, 99f, 35f),
            RasterImageTransform(1f, 99f, 99f, 90f)
        )) {
            output.eraseColor(Color.MAGENTA)
            view.setTransform(transform)
            view.draw(Canvas(output))
            for ((x, y) in listOf(2 to 2, 1077 to 2, 2 to 2397, 1077 to 2397, 540 to 1200)) {
                assertEquals("图片未覆盖 $x,$y: $transform", Color.WHITE, output.getPixel(x, y))
            }
        }
        output.recycle()
    }

    @Test
    fun drag_followsFingerAndCanMoveBackFromTheBoundary() = instrumentation.runOnMainSync {
        val view = editor()
        view.setTransform(RasterImageTransform(scale = 2f))
        val down = SystemClock.uptimeMillis()
        send(view, down, down, MotionEvent.ACTION_DOWN, Triple(7, 500f, 900f))
        send(view, down, down + 50, MotionEvent.ACTION_MOVE, Triple(7, 600f, 1000f))
        assertEquals(200f / 1080f, view.currentTransform().offsetX, 0.0001f)
        assertEquals(-200f / 2400f, view.currentTransform().offsetY, 0.0001f)
        send(view, down, down + 100, MotionEvent.ACTION_MOVE, Triple(7, 10000f, 1000f))
        val atEdge = view.currentTransform().offsetX
        send(view, down, down + 150, MotionEvent.ACTION_MOVE, Triple(7, 9990f, 1000f))
        assertEquals(atEdge - 20f / 1080f, view.currentTransform().offsetX, 0.0001f)
        send(view, down, down + 200, MotionEvent.ACTION_UP, Triple(7, 9990f, 1000f))
    }

    @Test
    fun multipleFingers_doNotZoomOrRotateAndResumeWithoutJumping() = instrumentation.runOnMainSync {
        val view = editor()
        view.setTransform(RasterImageTransform(scale = 2f))
        val down = SystemClock.uptimeMillis()
        send(view, down, down, MotionEvent.ACTION_DOWN, Triple(7, 350f, 900f))
        send(view, down, down + 30, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            Triple(7, 350f, 900f), Triple(12, 550f, 900f))
        send(view, down, down + 60, MotionEvent.ACTION_MOVE,
            Triple(7, 300f, 1000f), Triple(12, 700f, 1100f))
        assertEquals(2f, view.currentTransform().scale, 0.0001f)
        assertEquals(0f, view.currentTransform().rotation, 0f)
        assertEquals(0f, view.currentTransform().offsetX, 0f)
        assertEquals(0f, view.currentTransform().offsetY, 0f)
        val beforeLift = view.currentTransform()
        send(view, down, down + 90, MotionEvent.ACTION_POINTER_UP,
            Triple(7, 300f, 1000f), Triple(12, 700f, 1100f))
        send(view, down, down + 120, MotionEvent.ACTION_MOVE, Triple(12, 710f, 1110f))
        assertEquals(beforeLift.offsetX + 20f / 1080f, view.currentTransform().offsetX, 0.0001f)
        assertEquals(beforeLift.offsetY - 20f / 2400f, view.currentTransform().offsetY, 0.0001f)
        assertEquals(beforeLift.scale, view.currentTransform().scale, 0f)
        send(view, down, down + 150, MotionEvent.ACTION_UP, Triple(12, 710f, 1110f))
    }

    @Test
    fun doubleTap_resetsZoomPanAndRotationAndReportsSavedValue() = instrumentation.runOnMainSync {
        val view = editor()
        view.setTransform(RasterImageTransform(3f, 0.4f, -0.5f, 30f))
        var reported: RasterImageTransform? = null
        view.onTransformChanged = { reported = it }
        val down = SystemClock.uptimeMillis()
        send(view, down, down, MotionEvent.ACTION_DOWN, Triple(0, 500f, 900f))
        send(view, down, down + 30, MotionEvent.ACTION_UP, Triple(0, 500f, 900f))
        send(view, down + 120, down + 120, MotionEvent.ACTION_DOWN, Triple(0, 500f, 900f))
        send(view, down + 120, down + 150, MotionEvent.ACTION_UP, Triple(0, 500f, 900f))
        assertTrue(view.currentTransform().isIdentity())
        assertEquals(view.currentTransform(), reported)
    }

    @Test
    fun dragFollowedByTap_isNotMistakenForDoubleTap() = instrumentation.runOnMainSync {
        val view = editor()
        view.setTransform(RasterImageTransform(scale = 2f))
        val down = SystemClock.uptimeMillis()
        send(view, down, down, MotionEvent.ACTION_DOWN, Triple(0, 500f, 900f))
        send(view, down, down + 30, MotionEvent.ACTION_MOVE, Triple(0, 700f, 1000f))
        send(view, down, down + 60, MotionEvent.ACTION_UP, Triple(0, 700f, 1000f))
        send(view, down + 120, down + 120, MotionEvent.ACTION_DOWN, Triple(0, 700f, 1000f))
        send(view, down + 120, down + 150, MotionEvent.ACTION_UP, Triple(0, 700f, 1000f))
        assertEquals(2f, view.currentTransform().scale, 0f)
    }
    @Test
    fun scaleSlider_zoomsAroundScreenCenterWithoutChangingRotation() = instrumentation.runOnMainSync {
        val view = editor()
        view.setTransform(RasterImageTransform(2f, 0.2f, -0.1f, 15f))
        fun imagePointAtCenter(): FloatArray {
            val inverse = android.graphics.Matrix()
            assertTrue(view.imageMatrix.invert(inverse))
            return floatArrayOf(540f, 1200f).also { inverse.mapPoints(it) }
        }
        val before = imagePointAtCenter()
        view.setImageScale(2.5f)
        val after = imagePointAtCenter()
        assertEquals(2.5f, view.currentTransform().scale, 0.0001f)
        assertEquals(15f, view.currentTransform().rotation, 0.0001f)
        assertEquals(before[0], after[0], 0.01f)
        assertEquals(before[1], after[1], 0.01f)
    }

    @Test
    fun separateSliders_reportActualCoverScaleAndNeverLeaveCornersEmpty() = instrumentation.runOnMainSync {
        val view = editor()
        var reported: RasterImageTransform? = null
        view.onTransformChanged = { reported = it }
        val output = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        for (angle in listOf(-180f, -90f, -35f, 0f, 25f, 90f, 180f)) {
            view.setImageRotation(angle)
            view.setImageScale(0.1f)
            val actual = view.currentTransform()
            assertEquals(angle, actual.rotation, 0.0001f)
            assertEquals(actual, reported)
            assertTrue(actual.scale >= 1f)
            output.eraseColor(Color.MAGENTA)
            view.draw(Canvas(output))
            for ((x, y) in listOf(2 to 2, 1077 to 2, 2 to 2397, 1077 to 2397)) {
                assertEquals("滑块调整后露白 angle=$angle", Color.WHITE, output.getPixel(x, y))
            }
        }
        view.fitToScreen()
        assertTrue(view.currentTransform().isIdentity())
        assertEquals(view.currentTransform(), reported)
        output.recycle()
    }

}
