// Fork addition (2026): single-finger image positioning and shared cover-constrained editor rendering.
package com.zeaze.tianyinwallpaper.ui.raster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageView
import com.zeaze.tianyinwallpaper.model.RasterImageGeometry
import com.zeaze.tianyinwallpaper.model.RasterImageTransform
import com.zeaze.tianyinwallpaper.model.toImageMatrix
import kotlin.math.hypot

/** 等比例铺满的图片编辑器；所有变换都经过与壁纸渲染器共用的覆盖约束。 */
class RasterImageEditorView(context: Context) : ImageView(context) {
    var onTransformChanged: ((RasterImageTransform) -> Unit)? = null

    private var sourceBitmap: Bitmap? = null
    private var transform = RasterImageTransform()
    private val drawMatrix = Matrix()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastX = 0f
    private var lastY = 0f
    private var multiplePointers = false
    private var dragging = false
    private var resetting = false
    private var firstPointerId = MotionEvent.INVALID_POINTER_ID

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetting = true
            fitToScreen()
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    fun setBitmap(value: Bitmap?) {
        if (sourceBitmap === value) return
        sourceBitmap = value
        setImageBitmap(value)
        rebuildMatrix(notifyIfConstrained = true)
    }

    fun setTransform(value: RasterImageTransform) {
        if (transform == value) return
        transform = value.copy()
        rebuildMatrix(notifyIfConstrained = true)
    }

    fun currentTransform(): RasterImageTransform = transform.copy()

    /** 双击和底部按钮使用同一个入口：等比例居中铺满。 */
    fun fitToScreen() {
        applyTransform(RasterImageTransform())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildMatrix(notifyIfConstrained = true)
    }

    private fun rebuildMatrix(notifyIfConstrained: Boolean = false) {
        val bmp = sourceBitmap ?: return
        val image = drawable ?: return
        if (width <= 0 || height <= 0) return
        val requested = transform
        transform = transform.constrained(canvasAspect(), bmp.width.toFloat() / bmp.height)
        val geometry = RasterImageGeometry.of(
            width.toFloat(), height.toFloat(), bmp.width.toFloat(), bmp.height.toFloat(), transform
        )
        drawMatrix.setValues(geometry.toImageMatrix(image.intrinsicWidth.toFloat(), image.intrinsicHeight.toFloat()))
        imageMatrix = drawMatrix
        if (notifyIfConstrained && transform != requested) onTransformChanged?.invoke(currentTransform())
    }

    private fun applyTransform(next: RasterImageTransform) {
        transform = next
        rebuildMatrix()
        onTransformChanged?.invoke(currentTransform())
    }

    /** 滑块围绕屏幕中心缩放；实际生效值会回传，始终受铺满约束。 */
    fun setImageScale(value: Float) {
        transformAroundCenter(value, transform.rotation)
    }

    fun setImageRotation(degrees: Float) {
        transformAroundCenter(transform.scale, degrees)
    }

    private fun transformAroundCenter(scale: Float, rotation: Float) {
        val bmp = sourceBitmap ?: return
        if (width <= 0 || height <= 0) return
        applyTransform(transform.gestured(
            canvasAspect(), bmp.width.toFloat() / bmp.height,
            0.5f, 0.5f, 0.5f, 0.5f,
            zoom = scale / transform.scale,
            rotationDelta = rotation - transform.rotation
        ))
    }

    /** 多指期间暂停移动；松开多余手指时重新建立单指基准，避免跳动。 */
    private fun trackPointers(event: MotionEvent, excludedIndex: Int = -1) {
        val remaining = (0 until event.pointerCount).filter { it != excludedIndex }
        val first = remaining.firstOrNull() ?: return
        firstPointerId = event.getPointerId(first)
        lastX = event.getX(first)
        lastY = event.getY(first)
        multiplePointers = remaining.size > 1
        if (multiplePointers) dragging = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = sourceBitmap ?: return false
        if (width <= 0 || height <= 0) return false

        if (event.actionMasked == MotionEvent.ACTION_DOWN) resetting = false
        taps.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = false
                trackPointers(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> trackPointers(event)
            MotionEvent.ACTION_MOVE -> {
                if (resetting || multiplePointers || event.pointerCount != 1) return true
                val first = event.findPointerIndex(firstPointerId)
                if (first < 0) return true
                val x = event.getX(first)
                val y = event.getY(first)
                if (!dragging && hypot(x - lastX, y - lastY) <= touchSlop) return true
                dragging = true
                applyTransform(transform.gestured(
                    canvasAspect = canvasAspect(),
                    imageAspect = bmp.width.toFloat() / bmp.height,
                    fromX = lastX / width,
                    fromY = lastY / height,
                    toX = x / width,
                    toY = y / height
                ))
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> trackPointers(event, event.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP && !dragging) performClick()
                dragging = false
                firstPointerId = MotionEvent.INVALID_POINTER_ID
                multiplePointers = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun canvasAspect(): Float = if (height > 0) width.toFloat() / height else RasterImageTransform.FALLBACK_CANVAS_ASPECT
}
