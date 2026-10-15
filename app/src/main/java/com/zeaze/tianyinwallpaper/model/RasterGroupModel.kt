// Fork modification (2026): persist per-image transforms and keep them aligned with image ordering.
package com.zeaze.tianyinwallpaper.model

/**
 * 光栅组合数据模型
 * 支持静态图集光栅和动态视频光栅
 */
data class RasterGroupModel(
    var id: String = "",
    var type: Int = TYPE_STATIC,
    var imageUris: List<String> = emptyList(),
    /**
     * 每张图片各自的平移/缩放/旋转参数，下标与 [imageUris] 一一对应。
     * 允许比 [imageUris] 短或为空（旧数据即为此情况），缺失项按默认值处理。
     */
    var imageTransforms: List<RasterImageTransform> = emptyList(),
    var videoUri: String? = null,
    var createdAt: Long = 0L,
    
    // ── 传感器参数 ──
    var sensorWidth: Float = 1.5f,           // 传感器灵敏度 (1.0 ~ 9.0)
    
    // ── 过渡效果参数 ──
    var transitionBand: Float = 0.55f,       // 过渡区域宽度 (0.1 ~ 1.0)
    var edgeSoftness: Float = 0.25f,         // 边缘柔化程度 (0.01 ~ 0.5)
    
    // ── 效果类型 ──
    var effectType: Int = EFFECT_STANDARD,   // 扫描线效果类型

    // ── 玻璃效果参数 ──
    var stripedWavelength: Float = 32f,      // 条纹波长 (dp, 8 ~ 80)
    var stripedAmplitude: Float = 16f,       // 条纹振幅 (dp, 2 ~ 40)
    var narrowWavelength: Float = 12f,       // 窄波波长 (dp, 仅棱镜模式, 4 ~ 40)
    var narrowAmplitude: Float = 6f,         // 窄波振幅 (dp, 仅棱镜模式, 1 ~ 20)
    var glassAnimEnabled: Boolean = true,    // 玻璃动画开关（扫描线移动时条纹滚动）
    var glassBandWidth: Float = 0.3f,        // 玻璃折射区域宽度 (0.05 ~ 1.0)
    var deadZoneEnabled: Boolean = true,     // 死区开关（关闭后倾斜始终响应，无淡出效果）
    var clockColorMode: Int = 0
) {
    /** 取第 [index] 张图的变换参数，缺失或越界时返回默认值。 */
    fun transformAt(index: Int): RasterImageTransform =
        imageTransforms.getOrNull(index) ?: RasterImageTransform.DEFAULT

    /**
     * 把 [imageTransforms] 补齐/裁剪到与 [imageUris] 等长。
     * 图片列表变化后调用，避免下标错位。
     */
    fun syncTransformCount(): RasterGroupModel {
        if (imageTransforms.size == imageUris.size) return this
        val synced = ArrayList<RasterImageTransform>(imageUris.size)
        for (i in imageUris.indices) {
            synced.add(imageTransforms.getOrNull(i) ?: RasterImageTransform())
        }
        imageTransforms = synced
        return this
    }

    /**
     * 替换图片列表，并按顺序保留仍然有效的变换参数。
     * 主要用于"替换全部"这类整体换图的操作。
     */
    fun withImageUris(newUris: List<String>): RasterGroupModel {
        val newTransforms = ArrayList<RasterImageTransform>(newUris.size)
        for (i in newUris.indices) {
            newTransforms.add(imageTransforms.getOrNull(i) ?: RasterImageTransform())
        }
        return copy(imageUris = newUris, imageTransforms = newTransforms)
    }

    /** 单独更新第 [index] 张图的变换参数。 */
    fun withTransformAt(index: Int, transform: RasterImageTransform): RasterGroupModel {
        if (index !in imageUris.indices) return this
        val newTransforms = ArrayList<RasterImageTransform>(imageUris.size)
        for (i in imageUris.indices) {
            newTransforms.add(if (i == index) transform else transformAt(i))
        }
        return copy(imageTransforms = newTransforms)
    }

    /**
     * 把第 [fromIndex] 张图移动到 [toIndex]，图片与它自己的变换参数一起搬运。
     * 图片顺序变了但参数不跟着走的话，会张冠李戴。
     */
    fun withImageMoved(fromIndex: Int, toIndex: Int): RasterGroupModel {
        if (fromIndex !in imageUris.indices || toIndex !in imageUris.indices) return this
        if (fromIndex == toIndex) return this

        val uris = imageUris.toMutableList()
        val transforms = ArrayList<RasterImageTransform>(imageUris.size)
        for (i in imageUris.indices) transforms.add(transformAt(i))

        val movedUri = uris.removeAt(fromIndex)
        uris.add(toIndex, movedUri)
        val movedTransform = transforms.removeAt(fromIndex)
        transforms.add(toIndex, movedTransform)

        return copy(imageUris = uris, imageTransforms = transforms)
    }

    /** 删除第 [index] 张图及其变换参数。 */
    fun withImageRemoved(index: Int): RasterGroupModel {
        if (index !in imageUris.indices) return this
        val uris = imageUris.toMutableList().also { it.removeAt(index) }
        // 按原列表剔除被删项，保持其余参数的顺序与下标对应
        val transforms = ArrayList<RasterImageTransform>(uris.size)
        for (i in imageUris.indices) {
            if (i != index) transforms.add(transformAt(i))
        }
        return copy(imageUris = uris, imageTransforms = transforms)
    }

    companion object {
        const val TYPE_STATIC = 0
        const val TYPE_DYNAMIC = 1
        
        // 效果类型常量
        const val EFFECT_STANDARD = 0       // 标准扫描线
        const val EFFECT_CORRUGATED_GLASS = 3  // 波纹玻璃
        const val EFFECT_REEDED_GLASS = 4   // 长虹玻璃
        const val EFFECT_PRISM_GLASS = 5    // 棱镜玻璃
    }
}

