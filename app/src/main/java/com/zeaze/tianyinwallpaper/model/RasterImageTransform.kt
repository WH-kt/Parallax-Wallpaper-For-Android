// Fork modification (2026): shared cover, pan, zoom, rotation, and editor/render geometry constraints.
package com.zeaze.tianyinwallpaper.model

import com.alibaba.fastjson.annotation.JSONField
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 单张图片的显示变换参数（图集光栅）
 *
 * 语义与壁纸功能的变换一致：先把图片按"居中裁切铺满"填满画布，再叠加这里的变换。
 *
 * 坐标约定（编辑器与渲染器共用，见 [constrained]）：
 * 图片始终覆盖整个画布，位移被限制在"图片边缘不越过画布边缘"的范围内，
 * 因此任何合法参数下都不会出现黑边或空白。
 *
 * @param scale    相对铺满尺寸的额外缩放，1.0 = 恰好铺满（下限于此，不允许留白）
 * @param offsetX  图片中心相对画布中心的水平偏移比例：
 *                 0 = 居中，+1 = 图片中心移到画布右边缘，-1 = 移到左边缘
 * @param offsetY  图片中心的垂直偏移，+1 = 移到画布上边缘，-1 = 移到下边缘
 * @param rotation 旋转角度（度，正值为顺时针，与壁纸功能同向）
 */
data class RasterImageTransform(
    var scale: Float = 1f,
    var offsetX: Float = 0f,
    var offsetY: Float = 0f,
    var rotation: Float = 0f
) {
    /**
     * 是否为默认（未调整）状态。
     *
     * 必须标注 [JSONField] 排除：fastjson 会把 `isXxx()` 形式的方法当成布尔属性
     * 一起序列化，在配置里留下无意义的 "identity" 键。
     */
    @JSONField(serialize = false, deserialize = false)
    fun isIdentity(): Boolean =
        scale == 1f && offsetX == 0f && offsetY == 0f && rotation == 0f

    /**
     * 把参数钳制到合法范围：缩放不低于 1，位移不越界。
     *
     * 这是"编辑器所见 = 预览/壁纸所得"的关键：两边都调用同一个函数，
     * 不依赖各自画布的像素尺寸。
     *
     * @param canvasAspect 画布宽高比（宽 / 高）
     * @param imageAspect  图片原始宽高比（宽 / 高）
     */
    fun constrained(canvasAspect: Float, imageAspect: Float): RasterImageTransform {
        val safeRotation = if (rotation.isFinite()) rotation % 360f else 0f
        val requestedScale = if (scale.isFinite()) scale else MIN_SCALE
        if (!canvasAspect.isFinite() || !imageAspect.isFinite() || canvasAspect <= 0f || imageAspect <= 0f) {
            return copy(scale = requestedScale.coerceIn(MIN_SCALE, MAX_SCALE), offsetX = 0f, offsetY = 0f, rotation = safeRotation)
        }

        // 以画布高度为 1，在图片自身的坐标轴上检查画布四角。
        // 只检查旋转后的外接矩形不够：其四个角仍可能露黑。
        val radians = Math.toRadians(safeRotation.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val baseWidth = maxOf(canvasAspect, imageAspect)
        val baseHeight = maxOf(1f, canvasAspect / imageAspect)
        val requiredWidth = abs(c) * canvasAspect + abs(s)
        val requiredHeight = abs(s) * canvasAspect + abs(c)
        val minScale = maxOf(MIN_SCALE, requiredWidth / baseWidth, requiredHeight / baseHeight)
        val safeScale = requestedScale.coerceIn(minScale, maxOf(MAX_SCALE, minScale))

        val dx = (if (offsetX.isFinite()) offsetX else 0f) * canvasAspect / 2f
        val dy = -(if (offsetY.isFinite()) offsetY else 0f) / 2f
        val maxLocalX = ((baseWidth * safeScale - requiredWidth) / 2f).coerceAtLeast(0f)
        val maxLocalY = ((baseHeight * safeScale - requiredHeight) / 2f).coerceAtLeast(0f)
        val localX = (c * dx + s * dy).coerceIn(-maxLocalX, maxLocalX)
        val localY = (-s * dx + c * dy).coerceIn(-maxLocalY, maxLocalY)

        return copy(
            scale = safeScale,
            offsetX = 2f * (c * localX - s * localY) / canvasAspect,
            offsetY = -2f * (s * localX + c * localY),
            rotation = safeRotation
        )
    }

    /** 手势焦点使用画布比例坐标（左上为 0,0）；先绕旧焦点缩放/旋转，再跟随焦点移动。 */
    fun gestured(
        canvasAspect: Float,
        imageAspect: Float,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        zoom: Float = 1f,
        rotationDelta: Float = 0f
    ): RasterImageTransform {
        val current = constrained(canvasAspect, imageAspect)
        val next = current.copy(scale = current.scale * zoom, rotation = current.rotation + rotationDelta)
            .constrained(canvasAspect, imageAspect)
        val ratio = next.scale / current.scale
        val radians = Math.toRadians((next.rotation - current.rotation).toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val dx = (0.5f + current.offsetX / 2f - fromX) * canvasAspect
        val dy = 0.5f - current.offsetY / 2f - fromY
        val centerX = toX + ratio * (c * dx - s * dy) / canvasAspect
        val centerY = toY + ratio * (s * dx + c * dy)
        return next.copy(offsetX = 2f * (centerX - 0.5f), offsetY = -2f * (centerY - 0.5f))
            .constrained(canvasAspect, imageAspect)
    }

    companion object {
        val DEFAULT = RasterImageTransform()

        /** 缩放下限为 1.0：小于 1 必然露出空白，不符合"卡死铺满"的要求 */
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 3f

        /** 画布宽高比未知时的兜底值（9:16 竖屏） */
        const val FALLBACK_CANVAS_ASPECT = 9f / 16f
    }
}

/**
 * 图片在画布上的最终几何（像素）：把"铺满 + 用户变换"的完整计算集中在这里，
 * 编辑器和 GL 渲染器都调用它，从根上消除两边算得不一样的可能。
 */
data class RasterImageGeometry(
    /** 图片在画布坐标下的宽高（已含缩放） */
    val width: Float,
    val height: Float,
    /** 图片中心在画布坐标下的位置 */
    val centerX: Float,
    val centerY: Float,
    /** 生效后的旋转角度 */
    val rotation: Float
) {
    companion object {
        /**
         * @param canvasWidth   画布宽（像素）
         * @param canvasHeight  画布高（像素）
         * @param imageWidth    图片原始宽（像素）
         * @param imageHeight   图片原始高（像素）
         * @param transform     用户变换参数
         */
        fun of(
            canvasWidth: Float,
            canvasHeight: Float,
            imageWidth: Float,
            imageHeight: Float,
            transform: RasterImageTransform
        ): RasterImageGeometry {
            if (canvasWidth <= 0f || canvasHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
                return RasterImageGeometry(canvasWidth, canvasHeight, canvasWidth / 2f, canvasHeight / 2f, 0f)
            }
            val canvasAspect = canvasWidth / canvasHeight
            val imageAspect = imageWidth / imageHeight
            val applied = transform.constrained(canvasAspect, imageAspect)

            // 铺满画布的基础缩放：两个方向都不得小于画布
            val baseScale = maxOf(canvasWidth / imageWidth, canvasHeight / imageHeight)
            val totalScale = baseScale * applied.scale

            return RasterImageGeometry(
                width = imageWidth * totalScale,
                height = imageHeight * totalScale,
                centerX = canvasWidth * (0.5f + applied.offsetX / 2f),
                centerY = canvasHeight * (0.5f - applied.offsetY / 2f),
                rotation = applied.rotation
            )
        }
    }
}

/**
 * 把 [RasterImageGeometry] 转成"图片局部坐标 → 画布坐标"的矩阵。
 *
 * 纯函数、不依赖 Android 绘制框架，便于单元测试；
 * 编辑器（原生 ImageView + Matrix）与渲染器的构图都以此为准。
 */
fun RasterImageGeometry.toCanvasMatrix(): FloatArray {
    // 图片宽高任一为 0 时退化为单位矩阵，避免出现 NaN
    if (width <= 0f || height <= 0f) {
        return floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)
    }

    // 以图片中心为原点：平移到原点 → 旋转 → 平移到图片中心所在位置
    val rad = Math.toRadians(rotation.toDouble())
    val cos = kotlin.math.cos(rad).toFloat()
    val sin = kotlin.math.sin(rad).toFloat()

    val halfWidth = width / 2f
    val halfHeight = height / 2f
    val centerX = centerX
    val centerY = centerY

    // Android Matrix 的 3x3 表示为 [MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, 0, 0, 1]
    // 这里输出同样布局的一个 3x3（9 元素）数组，便于测试直接断言
    val m00 = cos
    val m01 = -sin
    val m10 = sin
    val m11 = cos
    // 平移项：使图片中心（局部 (halfWidth, halfHeight)）映射到 (centerX, centerY)
    //   centerX = m00*halfWidth + m01*halfHeight + tx  →  tx = centerX - (m00*halfWidth + m01*halfHeight)
    return floatArrayOf(
        m00, m01, centerX - (m00 * halfWidth + m01 * halfHeight),
        m10, m11, centerY - (m10 * halfWidth + m11 * halfHeight),
        0f, 0f, 1f
    )
}

/** Drawable 局部像素 → 画布像素。使用 Drawable 尺寸以兼容 Bitmap 的密度缩放。 */
fun RasterImageGeometry.toImageMatrix(imageWidth: Float, imageHeight: Float): FloatArray {
    val matrix = toCanvasMatrix()
    val sx = width / imageWidth
    val sy = height / imageHeight
    matrix[0] *= sx
    matrix[3] *= sx
    matrix[1] *= sy
    matrix[4] *= sy
    return matrix
}

/**
 * GL 画布纹理坐标（左下 0,0）→ 图片纹理坐标（左上 0,0）。
 * 这是编辑器绘制矩阵的逆变换，单图和光栅过渡共用，缩放越大采样范围越小。
 */
fun RasterImageGeometry.toTextureMatrix(
    canvasWidth: Float,
    canvasHeight: Float,
    result: FloatArray = FloatArray(16)
): FloatArray {
    result.fill(0f)
    result[10] = 1f
    result[15] = 1f
    val radians = Math.toRadians(rotation.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    result[0] = c * canvasWidth / width
    result[4] = -s * canvasHeight / width
    result[12] = 0.5f + (-c * centerX + s * (canvasHeight - centerY)) / width
    result[1] = -s * canvasWidth / height
    result[5] = -c * canvasHeight / height
    result[13] = 0.5f + (s * centerX + c * (canvasHeight - centerY)) / height
    return result
}
