// Fork addition (2026): verify persistence and ordering of per-image raster transforms.
package com.zeaze.tianyinwallpaper.model

import com.alibaba.fastjson.JSON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图集光栅的逐图变换参数与图片列表的对齐逻辑。
 * 参数一旦与图片下标错位，就会出现"给 A 图调的角度跑到 B 图上"这类问题。
 */
class RasterGroupModelTransformTest {

    private fun transform(scale: Float, rotation: Float = 0f) =
        RasterImageTransform(scale = scale, rotation = rotation)

    private fun group(vararg uris: String, transforms: List<RasterImageTransform> = emptyList()) =
        RasterGroupModel(id = "g", imageUris = uris.toList(), imageTransforms = transforms)

    @Test
    fun transformAt_fallsBackToDefaultWhenMissing() {
        val g = group("a", "b")
        // 旧数据没有参数列表
        assertTrue(g.transformAt(0).isIdentity())
        assertTrue(g.transformAt(1).isIdentity())
        // 越界与负下标同样安全
        assertTrue(g.transformAt(5).isIdentity())
        assertTrue(g.transformAt(-1).isIdentity())
    }

    @Test
    fun transformAt_returnsStoredValue() {
        val g = group("a", "b", transforms = listOf(transform(2f), transform(0.5f, 30f)))
        assertEquals(2f, g.transformAt(0).scale, 0f)
        assertEquals(0.5f, g.transformAt(1).scale, 0f)
        assertEquals(30f, g.transformAt(1).rotation, 0f)
    }

    @Test
    fun syncTransformCount_padsAndTruncates() {
        // 比图片少 -> 补齐
        val short = group("a", "b", "c", transforms = listOf(transform(1.5f)))
        short.syncTransformCount()
        assertEquals(3, short.imageTransforms.size)
        assertEquals(1.5f, short.transformAt(0).scale, 0f)
        assertTrue(short.transformAt(1).isIdentity())

        // 比图片多 -> 裁剪
        val long = group("a", transforms = listOf(transform(1f), transform(2f), transform(3f)))
        long.syncTransformCount()
        assertEquals(1, long.imageTransforms.size)
    }

    @Test
    fun withImageUris_preservesOrderAndTruncates() {
        val g = group("a", "b", "c", transforms = listOf(transform(1f), transform(2f), transform(3f)))

        // 替换为同样长度：按位置保留
        val same = g.withImageUris(listOf("x", "y", "z"))
        assertEquals(2f, same.transformAt(1).scale, 0f)

        // 变短：尾部参数丢弃
        val shorter = g.withImageUris(listOf("x", "y"))
        assertEquals(2, shorter.imageTransforms.size)
        assertEquals(2f, shorter.transformAt(1).scale, 0f)

        // 变长：新增位置为默认值
        val longer = g.withImageUris(listOf("x", "y", "z", "w"))
        assertEquals(4, longer.imageTransforms.size)
        assertTrue(longer.transformAt(3).isIdentity())
    }

    @Test
    fun withTransformAt_updatesOnlyTargetIndex() {
        val g = group("a", "b", "c")
        val updated = g.withTransformAt(1, transform(3f, 45f))

        assertEquals(3, updated.imageTransforms.size)
        assertTrue(updated.transformAt(0).isIdentity())
        assertEquals(3f, updated.transformAt(1).scale, 0f)
        assertEquals(45f, updated.transformAt(1).rotation, 0f)
        assertTrue(updated.transformAt(2).isIdentity())

        // 越界下标不应改变模型
        assertEquals(g, g.withTransformAt(9, transform(2f)))
    }

    @Test
    fun withImageMoved_carriesTransformWithImage() {
        val g = group(
            "a", "b", "c",
            transforms = listOf(transform(1f), transform(2f), transform(3f))
        )

        // 把第 0 张移到最后：它的参数应跟着走
        val moved = g.withImageMoved(0, 2)
        assertEquals(listOf("b", "c", "a"), moved.imageUris)
        assertEquals(2f, moved.transformAt(0).scale, 0f)
        assertEquals(3f, moved.transformAt(1).scale, 0f)
        assertEquals(1f, moved.transformAt(2).scale, 0f)

        // 非法下标不应改变模型
        assertEquals(g, g.withImageMoved(0, 9))
        assertEquals(g, g.withImageMoved(0, 0))
    }

    @Test
    fun withImageRemoved_dropsMatchingTransform() {
        val g = group(
            "a", "b", "c",
            transforms = listOf(transform(1f), transform(2f), transform(3f))
        )

        val removed = g.withImageRemoved(1)
        assertEquals(listOf("a", "c"), removed.imageUris)
        assertEquals(2, removed.imageTransforms.size)
        assertEquals(1f, removed.transformAt(0).scale, 0f)
        assertEquals(3f, removed.transformAt(1).scale, 0f)

        assertEquals(g, g.withImageRemoved(7))
    }

    @Test
    fun isIdentity_detectsNonDefaultValues() {
        assertTrue(RasterImageTransform().isIdentity())
        assertFalse(RasterImageTransform(scale = 1.01f).isIdentity())
        assertFalse(RasterImageTransform(rotation = 1f).isIdentity())
        assertFalse(RasterImageTransform(offsetX = 1f).isIdentity())
        assertFalse(RasterImageTransform(offsetY = -1f).isIdentity())
    }

    @Test
    fun fastjson_roundTrip_keepsTransformsAndOmitsComputedProperty() {
        val original = group("a", "b", transforms = listOf(transform(1.7f, 15f), transform(0.8f)))

        val json = JSON.toJSONString(listOf(original))

        // isIdentity 是计算属性，曾被 fastjson 当成字段一起写进配置
        assertFalse(
            "序列化结果不应包含计算属性 identity，实际为: $json",
            json.contains("\"identity\"")
        )

        // 往返后参数必须原样保留
        val parsed = JSON.parseArray(json, RasterGroupModel::class.java)
        assertEquals(1, parsed.size)
        val restored = parsed[0]
        assertEquals(listOf("a", "b"), restored.imageUris)
        assertEquals(2, restored.imageTransforms.size)
        assertEquals(1.7f, restored.transformAt(0).scale, 0.0001f)
        assertEquals(15f, restored.transformAt(0).rotation, 0.0001f)
        assertEquals(0.8f, restored.transformAt(1).scale, 0.0001f)
    }

    // ── 约束规则：保证图片始终铺满画布，绝不露出空白 ──

    private val phoneAspect = 1080f / 2400f      // 竖屏手机画布
    private val imageAspect = 1600f / 2000f      // 4:5 照片

    @Test
    fun constrained_neverAllowsScaleBelowOne() {
        // 缩放到 0.3 会被拉回 1.0，避免缩出空白
        val t = RasterImageTransform(scale = 0.3f)
        assertEquals(1f, t.constrained(phoneAspect, imageAspect).scale, 0.0001f)
    }

    @Test
    fun constrained_clampsOffsetToKeepImageCoveringCanvas() {
        // 4:5 的图放进 0.45 宽高比的画布：高度铺满，显示宽 = 画布高 * 0.8
        // 画布宽高比 0.45 → 显示宽/画布宽 = 0.8 / 0.45 = 1.777...
        // offset 使用半画布作为单位，允许的偏移上限 = 1.777 - 1 = 0.777...
        val expected = 0.8f / phoneAspect - 1f

        val tooFarRight = RasterImageTransform(offsetX = 5f)
        assertEquals(expected, tooFarRight.constrained(phoneAspect, imageAspect).offsetX, 0.001f)

        val tooFarLeft = RasterImageTransform(offsetX = -5f)
        assertEquals(-expected, tooFarLeft.constrained(phoneAspect, imageAspect).offsetX, 0.001f)

        // 画布比图片更宽时（宽度铺满、高度裁切），水平方向完全没有可移动余量
        val wideCanvas = 2400f / 1080f
        assertEquals(0f, RasterImageTransform(offsetX = 0.3f).constrained(wideCanvas, imageAspect).offsetX, 0.0001f)
    }

    @Test
    fun constrained_keepsOffsetsInsideRange() {
        val t = RasterImageTransform(scale = 2f, offsetX = 0.05f, offsetY = -0.05f)
        val c = t.constrained(phoneAspect, imageAspect)
        // 放大后余量更充足，原值应原样保留
        assertEquals(0.05f, c.offsetX, 0.0001f)
        assertEquals(-0.05f, c.offsetY, 0.0001f)
        assertEquals(2f, c.scale, 0.0001f)
    }

    @Test
    fun constrained_fallsBackWhenAspectUnknown() {
        val t = RasterImageTransform(offsetX = 0.4f, offsetY = 0.4f)
        val c = t.constrained(0f, imageAspect)
        assertEquals(0f, c.offsetX, 0.0001f)
        assertEquals(0f, c.offsetY, 0.0001f)
        assertEquals(1f, c.scale, 0.0001f)
    }

    @Test
    fun constrained_isAlsoAppliedWhenScaleIsMax() {
        val t = RasterImageTransform(scale = 99f)
        assertEquals(RasterImageTransform.MAX_SCALE, t.constrained(phoneAspect, imageAspect).scale, 0.0001f)
    }

    // ── 共享几何：编辑器与渲染器必须给出同一构图 ──

    @Test
    fun geometry_fillsCanvasAndClampsOffsetToItsLimit() {
        // 4:5 图放进 0.45 宽高比的画布：高度贴合、宽度大幅裁切（宽高比 = 画布宽高比）
        val g = RasterImageGeometry.of(
            canvasWidth = 1080f, canvasHeight = 2400f,
            imageWidth = 900f, imageHeight = 1200f,
            transform = RasterImageTransform(scale = 1f, offsetX = 4f)
        )
        // 覆盖式铺满：高度正好贴合画布，宽度按图片比例放大到 1800（比画布宽，被裁切）
        assertEquals(2400f, g.height, 1f)
        assertEquals(1800f, g.width, 1f)
        // 图片完整覆盖画布
        assertTrue(g.centerX - g.width / 2f <= 0.5f)
        assertTrue(g.centerX + g.width / 2f >= 1080f - 0.5f)

        // 拖到最右时图片左边缘刚好贴住画布，中心为图片宽度的一半。
        val expected = 900f
        assertEquals(expected, g.centerX, 1f)
    }

    @Test
    fun geometry_unknownAspectFallsBackToCentered() {
        val g = RasterImageGeometry.of(0f, 0f, 900f, 1200f, RasterImageTransform(offsetX = 0.5f))
        assertEquals(0f, g.rotation, 0f)
        assertTrue(g.width >= 0f)
    }

    @Test
    fun geometry_isSymmetricAroundCenterWhenOffsetIsZero() {
        val g = RasterImageGeometry.of(1080f, 2400f, 900f, 1200f, RasterImageTransform())
        assertEquals(540f, g.centerX, 0.5f)
        assertEquals(1200f, g.centerY, 0.5f)
        // 居中时左右两侧外扩量相等
        assertEquals(g.centerX - g.width / 2f, 1080f - (g.centerX + g.width / 2f), 1f)
    }

    @Test
    fun geometry_neverLeavesCanvasUncovered() {
        // 任意参数下，图片都必须完整覆盖画布（左边缘 <= 0，右边缘 >= 画布宽）
        val cases = listOf(
            RasterImageTransform(scale = 1f, offsetX = 9f),
            RasterImageTransform(scale = 1f, offsetX = -9f, offsetY = 9f),
            RasterImageTransform(scale = 3f, offsetX = 9f, offsetY = -9f),
        )
        for (t in cases) {
            val g = RasterImageGeometry.of(1080f, 2400f, 900f, 1200f, t)
            val left = g.centerX - g.width / 2f
            val right = g.centerX + g.width / 2f
            val top = g.centerY - g.height / 2f
            val bottom = g.centerY + g.height / 2f
            assertTrue("左边缘露白: $t -> $left", left <= 0.5f)
            assertTrue("右边缘露白: $t -> $right", right >= 1080f - 0.5f)
            assertTrue("上边缘露白: $t -> $top", top <= 0.5f)
            assertTrue("下边缘露白: $t -> $bottom", bottom >= 2400f - 0.5f)
        }
    }

    // ── 画布矩阵：图片中心必须落在画布中心 + 位移处 ──

    @Test
    fun toCanvasMatrix_mapsImageCenterToCanvasCenterWithOffset() {
        val g = RasterImageGeometry.of(
            canvasWidth = 1080f, canvasHeight = 2400f,
            imageWidth = 900f, imageHeight = 1200f,
            transform = RasterImageTransform(offsetX = 0.3333333f)
        )
        val m = g.toCanvasMatrix()
        // 矩阵工作在"几何空间"：局部原点在图片左上角，尺寸为 g.width x g.height
        val x = m[0] * (g.width / 2f) + m[1] * (g.height / 2f) + m[2]
        val y = m[3] * (g.width / 2f) + m[4] * (g.height / 2f) + m[5]
        assertEquals("图片中心 x", g.centerX, x, 0.5f)
        assertEquals("图片中心 y", g.centerY, y, 0.5f)
    }

    @Test
    fun toCanvasMatrix_mapsCornersToExpectedCanvasBounds() {
        val g = RasterImageGeometry.of(
            canvasWidth = 1080f, canvasHeight = 2400f,
            imageWidth = 900f, imageHeight = 1200f,
            transform = RasterImageTransform()
        )
        val m = g.toCanvasMatrix()
        // 居中且铺满：左边缘应为负、右边缘应超过画布宽（局部宽度用 g.width）
        val left = m[0] * 0f + m[1] * 0f + m[2]
        val right = m[0] * g.width + m[1] * 0f + m[2]
        assertEquals(g.centerX - g.width / 2f, left, 0.5f)
        assertEquals(g.centerX + g.width / 2f, right, 0.5f)
        assertTrue("应比画布宽", right - left >= 1080f)
    }
}
