// Fork modification (2026): render each image with the shared editor transform and coverage geometry.
package com.zeaze.tianyinwallpaper.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.net.Uri
import android.opengl.EGL14
import android.view.Surface
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.model.RasterImageGeometry
import com.zeaze.tianyinwallpaper.model.RasterImageTransform
import com.zeaze.tianyinwallpaper.model.toTextureMatrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sign

/**
 * 光栅壁纸共享渲染器
 * 支持：
 * - 标准扫描线效果
 * - 实时参数更新
 * - 优化的资源管理
 * - 无限制图片数量
 * - 集成传感器数据处理
 * - Bitmap 缓存机制
 */
class RasterGLRenderer {

    // ── 屏幕旋转 ──
    // 实时获取屏幕旋转角度，用于根据屏幕方向选择正确的陀螺仪轴
    // 返回 Surface.ROTATION_0/90/180/270
    var displayRotationProvider: () -> Int = { Surface.ROTATION_0 }

    // ── 配置参数 ──
    var transitionBand: Float = 0.55f
    var edgeSoftness: Float = 0.25f
    var effectType: ScanlineEffectType = ScanlineEffectType.STANDARD
        set(value) {
            if (field != value) {
                field = value
                effectTypeDirty = true
                requestRender()
            }
        }
    

    // ── 传感器处理参数 ──
    // 灵敏度系数 (0.5 ~ 9.0)，值越小越灵敏
    var sensitivity: Float = 1.5f
        set(value) {
            val newValue = value.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
            if (field != newValue) {
                field = newValue
                // 灵敏度变化时，根据当前角度重新计算倾斜值
                recalculateTiltFromCurrentAngle()
            }
        }
    
    // 死区角度（弧度），小于此角度的偏移被忽略
    var deadZoneAngle: Float = DEAD_ZONE_RAD
    
    // 最大累积角度（弧度）
    var maxAccumulatedAngle: Float = MAX_ANGLE_RAD
    
    // 低通滤波系数 (0.0 ~ 1.0)，值越小越平滑
    var filterAlpha: Float = FILTER_ALPHA
    
    // 速度阈值，低于此速度不累积角度
    var velocityThreshold: Float = VELOCITY_THRESHOLD
    
    // ── 传感器处理状态 ──
    private var lastTimestamp: Long = 0L
    private var rawAccumulatedAngle: Float = 0f
    private var filteredAngle: Float = 0f
    private var smoothAngle: Float = 0f
    private val angleHistory = FloatArray(HISTORY_SIZE)
    private var historyIndex: Int = 0
    private var historyCount: Int = 0
    
    // 输出状态
    var tiltNormalized: Float = 0f
        private set
    @Volatile var tiltDirection: Int = 0
        private set
    
    // 传感器回调
    var onTiltChanged: ((tilt: Float, direction: Int) -> Unit)? = null

    // 扫描线位置（供 Compose 层读取）
    fun getScanProgress(): Float = scanProgress
    fun getScanDirection(): Int = scanDirection

    // ── 状态 ──
    @Volatile private var imageCount: Int = 0
    @Volatile private var currentFloatIndex: Float = 0f
    @Volatile private var displayedIntIndex: Int = -1
    @Volatile private var scanFromIndex: Int = -1
    @Volatile private var scanToIndex: Int = -1
    @Volatile private var scanProgress: Float = 0f
    @Volatile private var scanDirection: Int = 0
    @Volatile private var effectTypeDirty: Boolean = false

    // 扫描线淡出：当过渡消失时渐变淡出而不是突然消失
    @Volatile private var scanFadeAlpha: Float = 0f
    // 保留上一次有效的过渡状态，用于淡出渲染
    @Volatile private var lastScanFromIndex: Int = -1
    @Volatile private var lastScanToIndex: Int = -1
    @Volatile private var lastScanProgress: Float = 0f
    @Volatile private var lastScanDirection: Int = 0

    // 淡入/淡出渲染使用的过渡参数（由 updateScanFadeAlpha 实时计算）
    @Volatile private var fadeFromIndex: Int = -1
    @Volatile private var fadeToIndex: Int = -1
    @Volatile private var fadeProgress: Float = 0f
    @Volatile private var fadeDirection: Int = 0

    // ── Bitmap 数据 ──
    private val bitmaps = mutableListOf<Bitmap?>()
    private val bitmapsLock = Any()  // 同步锁
    @Volatile private var surfaceWidth: Int = 1
    @Volatile private var surfaceHeight: Int = 1

    /**
     * 光栅在过渡时最多只会同时使用两张图。把整组原图常驻内存会在大量高分辨率
     * 图片时造成瞬间内存峰值，因此模型加载改为一个有界的滑动窗口。
     */
    private data class BitmapSource(
        val context: Context,
        val uris: List<String>,
        val targetWidth: Int,
        val targetHeight: Int,
        val generation: Int
    )

    @Volatile private var bitmapSource: BitmapSource? = null
    /**
     * 每张图片各自的平移/缩放/旋转参数，下标与图片顺序一致。
     * 只在主线程整体替换（不做原地修改），GL 线程读取，故用 @Volatile 保证可见性。
     */
    @Volatile private var bitmapTransforms: List<RasterImageTransform> = emptyList()
    private val bitmapLoadGeneration = AtomicInteger(0)
    private val pendingBitmapLoads = ConcurrentHashMap.newKeySet<String>()
    private val bitmapLoadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RasterBitmapLoader").apply { isDaemon = true }
    }

    // ── EGL ──
    private var eglThread: EglRenderThread? = null
    private val isRunning = AtomicBoolean(false)
    private val isSurfaceValid = AtomicBoolean(false)
    @Volatile private var renderingEnabled = true
    private val renderControl = Object()

    // ── 消息队列 ──
    private val messageQueue = ConcurrentLinkedQueue<RenderMessage>()

    private sealed class RenderMessage {
        data class LoadBitmaps(val bitmaps: List<Bitmap>) : RenderMessage()
        data class UpdateTilt(val tilt: Float, val direction: Int) : RenderMessage()
        data class Resize(val width: Int, val height: Int) : RenderMessage()
        data class UpdateParams(
            val transitionBand: Float,
            val edgeSoftness: Float,
            val effectType: ScanlineEffectType,
            val stripedWavelength: Float = 32f,
            val stripedAmplitude: Float = 16f,
            val narrowWavelength: Float = 12f,
            val narrowAmplitude: Float = 6f,
            val glassAnimEnabled: Boolean = true,
            val glassBandWidth: Float = 0.3f,
            val deadZoneEnabled: Boolean = true
        ) : RenderMessage()
        data class ExecuteTask(val task: () -> Unit) : RenderMessage()
        object Render : RenderMessage()
    }

    // ── 公共接口 ──

    /**
     * 启动渲染器
     */
    fun start(surface: Surface) {
        if (isRunning.get()) {
            stopAndWait(300)
        }
        isSurfaceValid.set(true)
        isRunning.set(true)
        eglThread = EglRenderThread(surface)
        eglThread?.start()
    }

    /**
     * 停止渲染器（同步等待）
     */
    fun stop() {
        isSurfaceValid.set(false)
        clearBitmapSource()
        if (!isRunning.getAndSet(false)) {
            releaseBitmaps()
            return
        }
        messageQueue.clear()
        synchronized(renderControl) {
            renderControl.notifyAll()
        }
        eglThread?.finish()
        eglThread = null
        releaseBitmaps()
    }

    /**
     * 停止渲染器并等待完成
     */
    fun stopAndWait(timeoutMs: Long = 500) {
        isSurfaceValid.set(false)
        clearBitmapSource()
        if (!isRunning.getAndSet(false)) {
            releaseBitmaps()
            return
        }
        messageQueue.clear()
        synchronized(renderControl) {
            renderControl.notifyAll()
        }
        eglThread?.finishAndWait(timeoutMs)
        eglThread = null
        releaseBitmaps()
    }

    /**
     * 更新 Surface 尺寸
     */
    fun resize(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        messageQueue.offer(RenderMessage.Resize(width, height))
    }

    /**
     * 加载图片
     */
    fun loadBitmaps(newBitmaps: List<Bitmap>) {
        // 置空图片源会关闭按需加载，但位图已由调用方给出，这里无需按 URIs 重新解码。
        bitmapSource = null
        pendingBitmapLoads.clear()
        synchronized(bitmapsLock) {
            // 只回收不在新列表中的旧 bitmap（避免回收缓存中的共享 bitmap）
            val newBitmapSet = newBitmaps.toSet()
            val toRelease = bitmaps.filterNotNull().filter { it !in newBitmapSet }
            toRelease.forEach { 
                if (!it.isRecycled) it.recycle() 
            }
            
            bitmaps.clear()
            bitmaps.addAll(newBitmaps)
            imageCount = bitmaps.size
        }
        // 初始化显示第一张图片
        displayedIntIndex = if (newBitmaps.isNotEmpty()) 0 else -1
        scanFromIndex = -1
        scanToIndex = -1
        scanProgress = 0f
        currentFloatIndex = 0f
        messageQueue.offer(RenderMessage.LoadBitmaps(newBitmaps.toList()))
        requestRender()
        // 放在最后：让上一组图片尚未完成的按需解码失效，避免它把旧图写回新数组。
        bitmapLoadGeneration.incrementAndGet()
    }
    
    /**
     * 从 RasterGroupModel 加载所有参数和图片
     * @param context Android Context
     * @param group 光栅组模型
     * @param useCache 是否使用缓存，默认 true
     */
    fun loadFromModel(context: Context, group: RasterGroupModel, useCache: Boolean = true) {
        // 更新渲染参数
        updateParams(
            transitionBand = group.transitionBand,
            edgeSoftness = group.edgeSoftness,
            effectType = ScanlineEffectType.fromId(group.effectType),
            stripedWavelength = group.stripedWavelength,
            stripedAmplitude = group.stripedAmplitude,
            narrowWavelength = group.narrowWavelength,
            narrowAmplitude = group.narrowAmplitude,
            glassBandWidth = group.glassBandWidth
        )
        
        // 更新传感器灵敏度
        sensitivity = group.sensorWidth

        bitmapTransforms = group.imageTransforms.toList()

        setBitmapSource(context, group.imageUris, useCache)
    }
    
    /**
     * 只更新参数（不重新加载图片）
     * 用于参数变化时实时更新
     */
    fun updateParamsFromModel(group: RasterGroupModel) {
        // 更新渲染参数
        updateParams(
            transitionBand = group.transitionBand,
            edgeSoftness = group.edgeSoftness,
            effectType = ScanlineEffectType.fromId(group.effectType),
            stripedWavelength = group.stripedWavelength,
            stripedAmplitude = group.stripedAmplitude,
            narrowWavelength = group.narrowWavelength,
            narrowAmplitude = group.narrowAmplitude,
            glassAnimEnabled = group.glassAnimEnabled,
            glassBandWidth = group.glassBandWidth,
            deadZoneEnabled = group.deadZoneEnabled
        )
        
        // 更新传感器灵敏度
        sensitivity = group.sensorWidth

        // 每张图的平移/缩放/旋转，不重新解码图片，仅同步参数
        bitmapTransforms = group.imageTransforms.toList()

        requestRender()
    }

    /**
     * 只更新每张图片的平移/缩放/旋转参数（不重新加载图片）。
     * 用于编辑器里拖动/缩放时的实时预览。
     */
    fun updateTransforms(transforms: List<RasterImageTransform>) {
        bitmapTransforms = transforms.toList()
        requestRender()
    }

    /**
     * 更新倾斜状态
     */
    fun updateTilt(tiltNormalized: Float, direction: Int) {
        if (imageCount == 0) return

        // ✅ 修复：允许 currentFloatIndex 略微超过 imageCount - 1
        // 这样当 tiltNormalized = 1.0 时，fraction 可以达到 1.0，确保最后一张图能完全显示
        // 乘以 1.02 是为了提供 2% 的余量，让用户可以"扫到底"
        val maxIndex = (imageCount - 1).toFloat()
        currentFloatIndex = (tiltNormalized * maxIndex * 1.02f).coerceIn(0f, maxIndex * 1.02f)

        if (imageCount == 1) {
            requestBitmapWindow(0)
            if (displayedIntIndex != 0) {
                displayedIntIndex = 0
                uploadStaticFrame(0)
            }
            scanProgress = 0f
            scanFromIndex = -1
            scanToIndex = -1
            requestRender()
            return
        }

        // ✅ 修复：允许 intIndex 达到 imageCount - 1，确保能显示最后一张图
        // 当 currentFloatIndex > imageCount - 1 时，intIndex 应该保持在 imageCount - 2
        // 这样 toIdx = imageCount - 1（最后一张图的索引）
        val intIndex = if (currentFloatIndex >= imageCount - 1) {
            imageCount - 2  // 锁定在倒数第二段过渡
        } else {
            currentFloatIndex.toInt().coerceIn(0, imageCount - 2)
        }
        val fraction = currentFloatIndex - intIndex

        val fromIdx = intIndex
        val toIdx = intIndex + 1
        requestBitmapWindow(intIndex)

        val bandStart = (1f - transitionBand) / 2f
        val bandEnd = (1f + transitionBand) / 2f

        if (fraction <= bandStart) {
            scanFromIndex = -1
            scanToIndex = -1
            scanProgress = 0f
            if (displayedIntIndex != fromIdx) {
                displayedIntIndex = fromIdx
                uploadStaticFrame(fromIdx)
            }
        } else if (fraction >= bandEnd) {
            scanFromIndex = -1
            scanToIndex = -1
            scanProgress = 0f
            if (displayedIntIndex != toIdx) {
                displayedIntIndex = toIdx
                uploadStaticFrame(toIdx)
            }
        } else {
            val mappedProgress = ((fraction - bandStart) / transitionBand).coerceIn(0f, 1f)
            if (scanFromIndex != fromIdx || scanToIndex != toIdx) {
                scanFromIndex = fromIdx
                scanToIndex = toIdx
                uploadTransitionTextures(fromIdx, toIdx)
            }
            scanDirection = if (direction >= 0) 1 else -1
            scanProgress = mappedProgress
            // 保存有效过渡状态（供淡出使用）
            lastScanFromIndex = fromIdx
            lastScanToIndex = toIdx
            lastScanProgress = mappedProgress
            lastScanDirection = scanDirection
        }
        requestRender()
    }

    // ── 玻璃效果参数 ──
    var stripedWavelength: Float = 32f
    var stripedAmplitude: Float = 16f
    var narrowWavelength: Float = 12f
    var narrowAmplitude: Float = 6f

    var glassAnimEnabled: Boolean = true

    /** 死区开关（关闭后倾斜始终响应，无淡出效果） */
    var deadZoneEnabled: Boolean = true

    /** 禁用透明遮罩（1.0=全屏玻璃，0.0=仅扫描线附近），用于调试测试 */
    var glassFullWidth: Float = 0f

    /** 玻璃折射区域宽度 (0.05 ~ 1.0) */
    var glassBandWidth: Float = 0.3f

    /**
     * 更新所有渲染参数
     */
    fun updateParams(
        transitionBand: Float = this.transitionBand,
        edgeSoftness: Float = this.edgeSoftness,
        effectType: ScanlineEffectType = this.effectType,
        stripedWavelength: Float = this.stripedWavelength,
        stripedAmplitude: Float = this.stripedAmplitude,
        narrowWavelength: Float = this.narrowWavelength,
        narrowAmplitude: Float = this.narrowAmplitude,
        glassAnimEnabled: Boolean = this.glassAnimEnabled,
        glassBandWidth: Float = this.glassBandWidth,
        deadZoneEnabled: Boolean = this.deadZoneEnabled
    ) {
        this.transitionBand = transitionBand
        this.edgeSoftness = edgeSoftness
        this.effectType = effectType
        this.stripedWavelength = stripedWavelength
        this.stripedAmplitude = stripedAmplitude
        this.narrowWavelength = narrowWavelength
        this.narrowAmplitude = narrowAmplitude
        this.glassAnimEnabled = glassAnimEnabled
        this.glassBandWidth = glassBandWidth
        this.deadZoneEnabled = deadZoneEnabled
        
        messageQueue.offer(RenderMessage.UpdateParams(
            transitionBand, edgeSoftness, effectType,
            stripedWavelength, stripedAmplitude,
            narrowWavelength, narrowAmplitude,
            glassAnimEnabled,
            glassBandWidth,
            deadZoneEnabled
        ))
        requestRender()
    }

    /**
     * 请求渲染
     */
    fun requestRender() {
        messageQueue.offer(RenderMessage.Render)
        synchronized(renderControl) {
            renderControl.notifyAll()
        }
    }

    fun setRenderingEnabled(enabled: Boolean) {
        renderingEnabled = enabled
        synchronized(renderControl) {
            renderControl.notifyAll()
        }
        if (enabled) {
            requestRender()
        }
    }
    
    // ── 传感器处理接口 ──
    
    /**
     * 根据当前角度重新计算倾斜值（灵敏度变化时调用）
     */
    private fun recalculateTiltFromCurrentAngle() {
        // 死区处理
        val effectiveDeadZone = if (deadZoneEnabled) deadZoneAngle else 0f
        val angleWithDeadZone = if (abs(smoothAngle) < effectiveDeadZone) {
            0f
        } else {
            smoothAngle - effectiveDeadZone * sign(smoothAngle)
        }

        // 计算归一化倾斜值（与 onSensorEvent 保持一致）
        // 灵敏度范围 0.5~9.0 对应角度阈值 20°~40°（约 0.349~0.698 弧度）
        val normalizedSensitivity = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        val angleThreshold = (0.3285f + 0.041f * normalizedSensitivity).coerceIn(0.349f, 0.698f)
        val newTiltNormalized = (abs(angleWithDeadZone) / angleThreshold).coerceIn(0f, 1f)

        // 检查是否有变化
        if (abs(newTiltNormalized - tiltNormalized) > MIN_CHANGE_THRESHOLD) {
            tiltNormalized = newTiltNormalized
            updateTilt(tiltNormalized, tiltDirection)
            onTiltChanged?.invoke(tiltNormalized, tiltDirection)
        }
    }

    /**
     * 重置传感器状态
     */
    fun resetSensor() {
        lastTimestamp = 0L
        rawAccumulatedAngle = 0f
        filteredAngle = 0f
        smoothAngle = 0f
        tiltNormalized = 0f
        tiltDirection = 0
        historyIndex = 0
        historyCount = 0
        angleHistory.fill(0f)
    }
    
    /**
     * 处理传感器事件
     * 可直接用于 SensorEventListener.onSensorChanged
     */
    fun onSensorEvent(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return

        // 初始化时间戳
        if (lastTimestamp == 0L) {
            lastTimestamp = e.timestamp
            return
        }

        // 计算时间差
        val dt = (e.timestamp - lastTimestamp) / 1_000_000_000f
        lastTimestamp = e.timestamp

        // 防止时间跳跃导致的大角度变化
        if (dt <= 0f || dt > 0.5f) return

        // 获取角速度，根据屏幕旋转选择正确的轴
        // ROTATION_0 (竖屏): Y轴
        // ROTATION_90 (左横屏): X轴
        // ROTATION_180 (倒竖屏): -Y轴
        // ROTATION_270 (右横屏): -X轴
        val rotation = displayRotationProvider()
        val angularVelocity = when (rotation) {
            Surface.ROTATION_90 -> e.values[0]    // 左横屏
            Surface.ROTATION_180 -> -e.values[1]  // 倒竖屏
            Surface.ROTATION_270 -> -e.values[0]  // 右横屏
            else -> e.values[1]                   // 竖屏
        }
        val absVelocity = abs(angularVelocity)

        // 速度阈值过滤
        if (absVelocity >= velocityThreshold) {
            // 累积原始角度
            rawAccumulatedAngle += angularVelocity * dt
            
            // 限制最大角度
            rawAccumulatedAngle = rawAccumulatedAngle.coerceIn(
                -maxAccumulatedAngle, maxAccumulatedAngle
            )
        }

        // 低通滤波
        filteredAngle = filterAlpha * rawAccumulatedAngle + (1 - filterAlpha) * filteredAngle

        // 自适应平滑
        smoothAngle = adaptiveSmooth(filteredAngle)

        // 死区处理
        val effectiveDeadZone = if (deadZoneEnabled) deadZoneAngle else 0f
        val angleWithDeadZone = if (abs(smoothAngle) < effectiveDeadZone) {
            0f
        } else {
            // 死区外保持原值，但减少死区边缘的跳变
            smoothAngle - effectiveDeadZone * sign(smoothAngle)
        }

        // 计算归一化倾斜值
        // 灵敏度语义：值越大，需要的倾斜角度越大（越不灵敏）
        // sensorWidth 范围 0.5~9.0，对应倾斜角度约 20°~40°
        // angleThreshold = sensitivity 对应的角度，达到此角度时 tiltNormalized = 1.0
        val normalizedSensitivity = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        // 将灵敏度转换为角度阈值（弧度），范围 20°~40°（约 0.349~0.698 弧度）
        val angleThreshold = (0.3285f + 0.041f * normalizedSensitivity).coerceIn(0.349f, 0.698f)
        val newTiltNormalized = (abs(angleWithDeadZone) / angleThreshold).coerceIn(0f, 1f)

        // 计算方向（移除阈值限制，实现即时方向更新）
        // 之前的问题：当 direction = 0 时进入过渡状态，scanDirection 会被错误设为 1
        val newDirection = when {
            smoothAngle < 0 -> 1   // 右倾（角速度负值累积导致 smoothAngle 为负）
            smoothAngle > 0 -> -1  // 左倾
            else -> tiltDirection  // 平稳状态保持原方向
        }

        // 检查是否有变化
        if (abs(newTiltNormalized - tiltNormalized) > MIN_CHANGE_THRESHOLD ||
            newDirection != tiltDirection) {
            tiltNormalized = newTiltNormalized
            tiltDirection = newDirection
            updateTilt(tiltNormalized, tiltDirection)
            onTiltChanged?.invoke(tiltNormalized, tiltDirection)
        }
    }

    // ── 内部方法 ──

    /**
     * 自适应平滑算法
     * 根据角度变化速度动态调整平滑程度
     */
    private fun adaptiveSmooth(angle: Float): Float {
        // 添加到历史记录
        angleHistory[historyIndex] = angle
        historyIndex = (historyIndex + 1) % HISTORY_SIZE
        historyCount = (historyCount + 1).coerceAtMost(HISTORY_SIZE)

        if (historyCount < 2) return angle

        // 计算变化速度
        val prevIndex = (historyIndex - 2 + HISTORY_SIZE) % HISTORY_SIZE
        val velocity = abs(angle - angleHistory[prevIndex])

        // 根据速度调整平滑程度
        // 速度快时减少平滑，速度慢时增加平滑
        val adaptiveAlpha = when {
            velocity > FAST_VELOCITY_THRESHOLD -> 0.8f   // 快速移动：减少平滑
            velocity > MEDIUM_VELOCITY_THRESHOLD -> 0.5f // 中速移动：中等平滑
            else -> 0.2f                                  // 慢速移动：强平滑
        }

        // 计算加权平均
        var sum = 0f
        var weightSum = 0f
        for (i in 0..<historyCount) {
            val idx = (historyIndex - 1 - i + HISTORY_SIZE) % HISTORY_SIZE
            val weight = if (i == 0) adaptiveAlpha else (1 - adaptiveAlpha) / (historyCount - 1)
            sum += angleHistory[idx] * weight
            weightSum += weight
        }

        return sum / weightSum
    }

    private fun uploadStaticFrame(index: Int) {
        eglThread?.post {
            val bitmap = synchronized(bitmapsLock) { bitmaps.getOrNull(index) } ?: return@post
            eglThread?.uploadBitmapSync(bitmap)
        }
    }

    private fun uploadTransitionTextures(fromIdx: Int, toIdx: Int) {
        eglThread?.post {
            val (bmpA, bmpB) = synchronized(bitmapsLock) {
                bitmaps.getOrNull(fromIdx) to bitmaps.getOrNull(toIdx)
            }
            if (bmpA != null) eglThread?.uploadToTexA(bmpA)
            if (bmpB != null) eglThread?.uploadToTexB(bmpB)
        }
    }

    private fun releaseBitmaps() {
        synchronized(bitmapsLock) {
            bitmaps.forEach { 
                if (it != null && !it.isRecycled) it.recycle()
            }
            bitmaps.clear()
            imageCount = 0
        }
    }
    
    // ── Bitmap 加载方法 ──
    
    /**
     * 配置按需图片源。只预取当前过渡附近的图片，避免“图片数量 × 单图尺寸”成为
     * 常驻内存；解码也始终在专用后台线程完成。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun setBitmapSource(context: Context, uris: List<String>, useCache: Boolean) {
        val generation = bitmapLoadGeneration.incrementAndGet()
        pendingBitmapLoads.clear()
        val targetWidth = if (surfaceWidth > 1) (surfaceWidth * DECODE_SCALE).toInt() else 0
        val targetHeight = if (surfaceHeight > 1) (surfaceHeight * DECODE_SCALE).toInt() else 0
        val source = BitmapSource(
            context.applicationContext,
            uris.toList(),
            targetWidth,
            targetHeight,
            generation
        )
        bitmapSource = source

        synchronized(bitmapsLock) {
            bitmaps.forEach { bitmap ->
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            }
            bitmaps.clear()
            repeat(source.uris.size) { bitmaps.add(null) }
            imageCount = source.uris.size
        }

        displayedIntIndex = if (source.uris.isNotEmpty()) 0 else -1
        scanFromIndex = -1
        scanToIndex = -1
        scanProgress = 0f
        currentFloatIndex = 0f
        // 让 GL 线程丢弃上一组图片对应的纹理引用；实际像素仍由按需加载完成后上传。
        messageQueue.offer(RenderMessage.LoadBitmaps(emptyList()))
        requestBitmapWindow(0)
        requestRender()
    }

    private fun clearBitmapSource() {
        bitmapLoadGeneration.incrementAndGet()
        bitmapSource = null
        pendingBitmapLoads.clear()
    }

    /** 保留当前过渡的前后图及少量相邻图，总数始终有上限。 */
    private fun requestBitmapWindow(centerIndex: Int) {
        val source = bitmapSource ?: return
        if (source.uris.isEmpty()) return

        val start = (centerIndex - RESIDENT_BEFORE).coerceAtLeast(0)
        val end = (centerIndex + RESIDENT_AFTER).coerceAtMost(source.uris.lastIndex)
        for (index in start..end) {
            val alreadyLoaded = synchronized(bitmapsLock) { bitmaps.getOrNull(index) != null }
            val loadKey = "${source.generation}:$index"
            if (!alreadyLoaded && pendingBitmapLoads.add(loadKey)) {
                bitmapLoadExecutor.execute {
                    val bitmap = decodeBitmap(source, index)
                    try {
                        if (bitmap == null || bitmapSource !== source ||
                            bitmapLoadGeneration.get() != source.generation) {
                            bitmap?.takeIf { !it.isRecycled }?.recycle()
                            return@execute
                        }

                        synchronized(bitmapsLock) {
                            if (bitmapSource !== source || bitmapLoadGeneration.get() != source.generation) {
                                if (!bitmap.isRecycled) bitmap.recycle()
                                return@synchronized
                            }
                            bitmaps.getOrNull(index)?.let { oldBitmap ->
                                if (!oldBitmap.isRecycled) oldBitmap.recycle()
                            }
                            if (index in bitmaps.indices) {
                                bitmaps[index] = bitmap
                                trimResidentBitmapsLocked(currentFloatIndex.toInt())
                            } else if (!bitmap.isRecycled) {
                                bitmap.recycle()
                            }
                        }
                        requestRender()
                    } finally {
                        pendingBitmapLoads.remove(loadKey)
                    }
                }
            }
        }
    }

    private fun trimResidentBitmapsLocked(centerIndex: Int) {
        val keepStart = (centerIndex - RESIDENT_BEFORE).coerceAtLeast(0)
        val keepEnd = (centerIndex + RESIDENT_AFTER).coerceAtMost(bitmaps.lastIndex)
        bitmaps.forEachIndexed { index, bitmap ->
            if (index !in keepStart..keepEnd && bitmap != null) {
                if (!bitmap.isRecycled) bitmap.recycle()
                bitmaps[index] = null
            }
        }
    }

    private fun decodeBitmap(source: BitmapSource, index: Int): Bitmap? {
        val uri = source.uris.getOrNull(index)?.let(Uri::parse) ?: return null
        return try {
            // 渲染器会在不再需要时 recycle 自己的副本，缓存本体不能直接交给 GL 层。
            RenderBitmapCache.loadSync(
                source.context,
                uri,
                source.targetWidth,
                source.targetHeight
            )?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load image: $uri", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory to load image: $uri", e)
            null
        }
    }

    // ── EGL 渲染线程 ──

    private inner class EglRenderThread(private val surface: Surface) : Thread("RasterGLRenderer") {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var iTexId: Int = 0
        private var texAId: Int = 0
        private var texBId: Int = 0
        // 纹理内容仅在图片切换时变化；避免在每个传感器驱动的 draw 中重复 texImage2D。
        private var uploadedSingleBitmap: Bitmap? = null
        private var uploadedTexABitmap: Bitmap? = null
        private var uploadedTexBBitmap: Bitmap? = null
        
        // 着色器程序
        private var singleProg: Int = 0
        private var standardProg: Int = 0
        private var corrugatedGlassProg: Int = 0
        private var reededGlassProg: Int = 0
        private var prismGlassProg: Int = 0
        
        // 当前活动的效果类型
        private var activeEffectType: ScanlineEffectType = ScanlineEffectType.STANDARD
        
        // 参数缓存
        private var paramTransitionBand: Float = 0.55f
        private var paramEdgeSoftness: Float = 0.25f
        private var paramStripedWavelength: Float = 32f
        private var paramStripedAmplitude: Float = 16f
        private var paramNarrowWavelength: Float = 12f
        private var paramNarrowAmplitude: Float = 6f
        private var paramGlassAnimEnabled: Boolean = true
        private var paramGlassBandWidth: Float = 0.3f
        private var paramDeadZoneEnabled: Boolean = true

        // 玻璃动画相位追踪
        private var glassPhase: Float = 0f
        private var lastProgress: Float = 0f
        private var lastFrameTimeNanos: Long = 0L

        private var vBuf: FloatBuffer
        private var tBuf: FloatBuffer

        private var sW: Int = 1
        private var sH: Int = 1

        init {
            val vData = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            vBuf = ByteBuffer.allocateDirect(vData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vData)
            vBuf.position(0)
            val tData = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            tBuf = ByteBuffer.allocateDirect(tData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(tData)
            tBuf.position(0)
        }

        fun post(r: () -> Unit) {
            messageQueue.offer(RenderMessage.ExecuteTask(r))
            synchronized(renderControl) {
                renderControl.notifyAll()
            }
        }

        fun uploadBitmapSync(b: Bitmap) {
            if (b.isRecycled) return
            // 去重只在同一张仍然存活的位图上成立：滑动窗口回收过的位图即使实例相同，
            // 也说明纹理里是更早的内容，必须重新上传。
            if (uploadedSingleBitmap === b && !b.isRecycled) return
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
            uploadedSingleBitmap = b
        }

        fun uploadToTexA(b: Bitmap) {
            if (b.isRecycled) return
            if (uploadedTexABitmap === b && !b.isRecycled) return
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texAId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
            uploadedTexABitmap = b
        }

        fun uploadToTexB(b: Bitmap) {
            if (b.isRecycled) return
            if (uploadedTexBBitmap === b && !b.isRecycled) return
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
            uploadedTexBBitmap = b
        }

        private fun transformAt(index: Int): RasterImageTransform =
            bitmapTransforms.getOrNull(index) ?: RasterImageTransform.DEFAULT

        /** 编辑器绘制几何的逆变换；单图、扫描线和玻璃效果使用同一套构图。 */
        private fun buildTextureMatrix(
            bitmap: Bitmap,
            transform: RasterImageTransform,
            scratch: FloatArray
        ): FloatArray = RasterImageGeometry.of(
            sW.toFloat(), sH.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat(), transform
        ).toTextureMatrix(sW.toFloat(), sH.toFloat(), scratch)

        private val stScratchSingle = FloatArray(16)
        private val stScratchA = FloatArray(16)
        private val stScratchB = FloatArray(16)

        override fun run() {
            if (!initEGL()) {
                Log.e(TAG, "Failed to init EGL")
                return
            }
            initGL()

            while (isRunning.get() && isSurfaceValid.get()) {
                if (!renderingEnabled) {
                    try {
                        synchronized(renderControl) {
                            while (isRunning.get() && isSurfaceValid.get() && !renderingEnabled) {
                                renderControl.wait()
                            }
                        }
                    } catch (_: InterruptedException) {
                        if (!isRunning.get() || !isSurfaceValid.get()) break
                    }
                    if (!isRunning.get() || !isSurfaceValid.get()) break
                }

                try {
                    synchronized(renderControl) {
                        while (isRunning.get() && isSurfaceValid.get() && renderingEnabled && messageQueue.isEmpty()) {
                            renderControl.wait()
                        }
                    }
                } catch (_: InterruptedException) {
                    if (!isRunning.get() || !isSurfaceValid.get()) break
                }

                var needsDraw = false
                while (isSurfaceValid.get()) {
                    val msg = messageQueue.poll() ?: break
                    if (!isSurfaceValid.get()) break
                    when (msg) {
                        is RenderMessage.Resize -> {
                            sW = msg.width
                            sH = msg.height
                        }
                        is RenderMessage.LoadBitmaps -> {
                            // 标记需要更新纹理
                            displayedIntIndex = -1
                            scanFromIndex = -1
                            scanToIndex = -1
                            scanProgress = 0f
                            scanFadeAlpha = 0f
                            lastScanFromIndex = -1
                            lastScanToIndex = -1
                            lastScanProgress = 0f
                            lastScanDirection = 0
                            fadeFromIndex = -1
                            fadeToIndex = -1
                            fadeProgress = 0f
                            fadeDirection = 0
                            uploadedSingleBitmap = null
                            uploadedTexABitmap = null
                            uploadedTexBBitmap = null
                        }
                        is RenderMessage.UpdateTilt -> { }
                        is RenderMessage.UpdateParams -> {
                            paramTransitionBand = msg.transitionBand
                            paramEdgeSoftness = msg.edgeSoftness
                            activeEffectType = msg.effectType
                            paramStripedWavelength = msg.stripedWavelength
                            paramStripedAmplitude = msg.stripedAmplitude
                            paramNarrowWavelength = msg.narrowWavelength
                            paramNarrowAmplitude = msg.narrowAmplitude
                            paramGlassAnimEnabled = msg.glassAnimEnabled
                            paramGlassBandWidth = msg.glassBandWidth
                            paramDeadZoneEnabled = msg.deadZoneEnabled
                        }
                        is RenderMessage.ExecuteTask -> {
                            if (display != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE && isSurfaceValid.get()) {
                                EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
                            }
                            if (isSurfaceValid.get()) {
                                msg.task()
                            }
                        }
                        is RenderMessage.Render -> needsDraw = true
                    }
                }

                if (!isSurfaceValid.get()) break
                if (needsDraw) {
                    draw()
                }
            }

            destroyEGL()
        }

        private fun initEGL(): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)

            val attr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, attr, 0, configs, 0, 1, numConfigs, 0)

            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )

            eglSurface = EGL14.eglCreateWindowSurface(
                display, configs[0], surface,
                intArrayOf(EGL14.EGL_NONE), 0
            )

            return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
        }

        private fun initGL() {
            // 单图着色器
            singleProg = createProg(RasterShaders.SINGLE_VERTEX, RasterShaders.SINGLE_FRAGMENT)

            // 标准扫描线着色器
            standardProg = createProg(RasterShaders.TRANSITION_VERTEX, RasterShaders.STANDARD_FRAGMENT)

            // 波纹玻璃着色器
            corrugatedGlassProg = createProg(RasterShaders.TRANSITION_VERTEX, RasterShaders.CORRUGATED_GLASS_FRAGMENT)

            // 长虹玻璃着色器
            reededGlassProg = createProg(RasterShaders.TRANSITION_VERTEX, RasterShaders.REEDED_GLASS_FRAGMENT)

            // 棱镜玻璃着色器
            prismGlassProg = createProg(RasterShaders.TRANSITION_VERTEX, RasterShaders.PRISM_GLASS_FRAGMENT)

            // 创建纹理
            val tex = IntArray(3)
            GLES20.glGenTextures(3, tex, 0)
            iTexId = tex[0]
            texAId = tex[1]
            texBId = tex[2]

            for (texId in intArrayOf(iTexId, texAId, texBId)) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
        }

        private fun draw() {
            if (!isSurfaceValid.get()) return
            if (display == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
            if (imageCount == 0) return

            // 更新玻璃动画相位
            updateGlassPhase()

            // 更新扫描线淡出 alpha
            updateScanFadeAlpha()

            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) return

            GLES20.glViewport(0, 0, sW, sH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // 当有 2+ 张图片时，检查是否需要显示过渡效果
            if (imageCount >= 2) {
                // 检查是否有活动的过渡状态
                // 使用 >= 0.001f 避免浮点精度问题
                val hasActiveTransition = scanFromIndex >= 0 && scanToIndex >= 0 && scanProgress >= 0.001f
                if (hasActiveTransition) {
                        // 有活动的过渡状态，显示过渡效果
                    drawTransitionAt(scanFromIndex, scanToIndex, scanProgress, scanDirection)
                } else if (scanFadeAlpha > 0.01f && fadeFromIndex >= 0 && fadeToIndex >= 0) {
                    // 过渡消失但正在淡出：先画底层单图，再叠加淡出的过渡效果
                    val targetIdx = displayedIntIndex.coerceIn(0, maxOf(0, imageCount - 1))
                    drawSingleAt(targetIdx)
                    drawFadingTransitionAt(fadeFromIndex, fadeToIndex, fadeProgress, fadeDirection, scanFadeAlpha)
                } else {
                    // ✅ 静止状态：显示单张图片，限制索引范围
                    val targetIdx = displayedIntIndex.coerceIn(0, maxOf(0, imageCount - 1))
                    drawSingleAt(targetIdx)
                }
            } else {
                drawSingle()
            }

            if (isSurfaceValid.get()) {
                EGL14.eglSwapBuffers(display, eglSurface)
            }
        }

        /**
         * 根据扫描线移动速度和方向更新玻璃动画相位
         * 扫描线移动时条纹滚动，静止时相位不变
         */
        private fun updateGlassPhase() {
            val isGlassEffect = activeEffectType == ScanlineEffectType.CORRUGATED_GLASS ||
                    activeEffectType == ScanlineEffectType.REEDED_GLASS ||
                    activeEffectType == ScanlineEffectType.PRISM_GLASS
            if (!isGlassEffect) return

            // 动画开关关闭时不更新相位
            if (!paramGlassAnimEnabled) return

            val now = System.nanoTime()
            if (lastFrameTimeNanos == 0L) {
                lastFrameTimeNanos = now
                lastProgress = scanProgress
                return
            }

            val deltaNanos = now - lastFrameTimeNanos
            if (deltaNanos <= 0) return
            lastFrameTimeNanos = now

            // 计算扫描线进度变化量
            val progressDelta = scanProgress - lastProgress
            lastProgress = scanProgress

            // 只在有活动过渡时更新相位
            val hasActiveTransition = scanFromIndex >= 0 && scanToIndex >= 0 && scanProgress >= 0.001f
            if (!hasActiveTransition) return

            // progressDelta 的符号和方向决定条纹滚动方向
            // 方向 + 速度映射到相位变化：速度越快条纹滚动越快
            val speed = abs(progressDelta)
            // 将速度映射为相位步进，乘以缩放因子让动画更明显
            val phaseScale = 15.0f * TWO_PI
            val phaseStep = progressDelta * phaseScale

            glassPhase = (glassPhase + phaseStep) % TWO_PI
            if (glassPhase < 0) glassPhase += TWO_PI
        }

        /**
         * 更新扫描线淡出 alpha 及淡出所需的过渡参数
         * 有活动过渡时立即设为1，无过渡时跟随倾斜角度淡出
         * 角度驱动：fraction 距离 band 边缘越远，alpha 越低
         * 死区关闭时跳过淡出，直接消失
         *
         * 关键：当没有活跃过渡时，根据 currentFloatIndex 实时计算
         * fadeFrom/fadeTo/fadeProgress/fadeDirection，确保淡入淡出
         * 总是使用当前角度对应的正确图片对和方向。
         */
        private fun updateScanFadeAlpha() {
            val hasActiveTransition = scanFromIndex >= 0 && scanToIndex >= 0 && scanProgress >= 0.001f
            if (hasActiveTransition) {
                scanFadeAlpha = 1f
                fadeFromIndex = scanFromIndex
                fadeToIndex = scanToIndex
                fadeProgress = scanProgress
                fadeDirection = scanDirection
            } else {
                if (!paramDeadZoneEnabled) {
                    scanFadeAlpha = 0f
                    return
                }
                // 根据当前 currentFloatIndex 计算 fraction 与 band 边缘的距离
                val maxIdx = (imageCount - 1).toFloat()
                if (maxIdx <= 0f) {
                    scanFadeAlpha = 0f
                    return
                }
                val curIdx = currentFloatIndex.coerceIn(0f, maxIdx * 1.02f)
                val intIdx = if (curIdx >= imageCount - 1)
                    (imageCount - 2).coerceAtLeast(0)
                else
                    curIdx.toInt().coerceIn(0, (imageCount - 2).coerceAtLeast(0))
                val fraction = curIdx - intIdx
                val bandStart = (1f - paramTransitionBand) / 2f
                val bandEnd = (1f + paramTransitionBand) / 2f

                // 计算 fraction 与 band 边缘的距离
                val distFromBand = when {
                    fraction <= bandStart -> bandStart - fraction
                    fraction >= bandEnd -> fraction - bandEnd
                    else -> 0f  // 在 band 内（不应到达，防御性处理）
                }

                // 淡出范围：距离 band 边缘 0.25 时完全透明
                val fadeRange = 0.25f
                scanFadeAlpha = (1f - distFromBand / fadeRange).coerceIn(0f, 1f)

                // 根据当前角度实时计算淡入/淡出使用的过渡参数
                // 确保 from/to/progress/direction 与当前倾斜方向一致
                if (scanFadeAlpha > 0.01f) {
                    fadeFromIndex = intIdx
                    fadeToIndex = intIdx + 1
                    // 根据距 band 边缘的距离映射 progress
                    if (fraction <= bandStart) {
                        fadeProgress = 0f
                    } else {
                        fadeProgress = ((fraction - bandStart) / paramTransitionBand).coerceIn(0f, 1f)
                    }
                    fadeDirection = tiltDirection
                }
            }
        }

        private val TWO_PI = (Math.PI * 2.0).toFloat()
        
        /**
         * 在指定索引和进度绘制过渡效果
         */
        private fun drawTransitionAt(fromIdx: Int, toIdx: Int, progress: Float, direction: Int) {
            val bmpA: Bitmap?
            val bmpB: Bitmap?
            synchronized(bitmapsLock) {
                bmpA = bitmaps.getOrNull(fromIdx)
                bmpB = bitmaps.getOrNull(toIdx)
            }
            
            // 检查 bitmap 有效性
            if (bmpA == null || bmpA.isRecycled || bmpB == null || bmpB.isRecycled) {
                drawSingleAt(if (bmpA != null && !bmpA.isRecycled) fromIdx else toIdx)
                return
            }
            
            // 选择当前效果类型的着色器程序
            val prog = when (activeEffectType) {
                ScanlineEffectType.CORRUGATED_GLASS -> corrugatedGlassProg
                ScanlineEffectType.REEDED_GLASS -> reededGlassProg
                ScanlineEffectType.PRISM_GLASS -> prismGlassProg
                else -> standardProg
            }
            
            GLES20.glUseProgram(prog)

            val sAsp = if (sH > 0) sW.toFloat() / sH else 9f / 16f
            
            // 上传纹理
            uploadToTexA(bmpA)
            uploadToTexB(bmpB)

            // texA / texB 纹理坐标变换（含各自图片的平移/缩放/旋转）
            val stMatA = buildTextureMatrix(bmpA, transformAt(fromIdx), stScratchA)
            val stMatB = buildTextureMatrix(bmpB, transformAt(toIdx), stScratchB)

            val mvp = FloatArray(16)
            Matrix.setIdentityM(mvp, 0)

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uSTA"), 1, false, stMatA, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uSTB"), 1, false, stMatB, 0)

            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            val aTex = GLES20.glGetAttribLocation(prog, "aTex")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vBuf)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tBuf)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texAId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "sTexA"), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "sTexB"), 1)

            // 通用参数
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uProgress"), progress)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uDirection"), direction.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uEdgeSoftness"), paramEdgeSoftness)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uScreenWidth"), sW.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFadeAlpha"), 1.0f)

            // 玻璃效果专属参数
            if (activeEffectType == ScanlineEffectType.CORRUGATED_GLASS ||
                activeEffectType == ScanlineEffectType.REEDED_GLASS ||
                activeEffectType == ScanlineEffectType.PRISM_GLASS) {
                val wavelengthNorm = paramStripedWavelength / sW.toFloat()
                val amplitudeNorm = paramStripedAmplitude / sW.toFloat()
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uScreenHeight"), sH.toFloat())
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAmplitude"), amplitudeNorm)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uWavelength"), wavelengthNorm.coerceAtLeast(0.001f))
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHighlightStrength"), 0.0f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uShadowStrength"), 0.0f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uColorSaturation"), 1.2f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uColorContrast"), 1.1f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uGlassFullWidth"), glassFullWidth)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uGlassBandWidth"), paramGlassBandWidth)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uPhase"), glassPhase)

                // 棱镜模式额外参数
                if (activeEffectType == ScanlineEffectType.PRISM_GLASS) {
                    val narrowWavelengthNorm = paramNarrowWavelength / sW.toFloat()
                    val narrowAmplitudeNorm = paramNarrowAmplitude / sW.toFloat()
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uNarrowAmplitude"), narrowAmplitudeNorm)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uNarrowWavelength"), narrowWavelengthNorm.coerceAtLeast(0.001f))
                }
            }

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // 关闭 blending
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        /**
         * 绘制淡出中的过渡效果（叠加在单图之上）
         * RGB 正常 alpha 混合实现淡出，但 alpha 通道保持 1.0 防止透出底色
         */
        private fun drawFadingTransitionAt(fromIdx: Int, toIdx: Int, progress: Float, direction: Int, fadeAlpha: Float) {
            val bmpA: Bitmap?
            val bmpB: Bitmap?
            synchronized(bitmapsLock) {
                bmpA = bitmaps.getOrNull(fromIdx)
                bmpB = bitmaps.getOrNull(toIdx)
            }

            if (bmpA == null || bmpA.isRecycled || bmpB == null || bmpB.isRecycled) return

            // 确保纹理已上传
            uploadToTexA(bmpA)
            uploadToTexB(bmpB)

            // 开启 blending：RGB 正常混合淡出，alpha 通道始终保持 1.0（不透出底色）
            // Alpha 方程：resultA = srcA * 1 + dstA * (1 - srcA) = fadeAlpha + 1.0 * (1 - fadeAlpha) = 1.0
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFuncSeparate(
                GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,  // RGB: 标准 alpha 混合（淡出效果）
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA          // Alpha: 始终合并为 1.0
            )

            // 选择着色器
            val prog = when (activeEffectType) {
                ScanlineEffectType.CORRUGATED_GLASS -> corrugatedGlassProg
                ScanlineEffectType.REEDED_GLASS -> reededGlassProg
                ScanlineEffectType.PRISM_GLASS -> prismGlassProg
                else -> standardProg
            }
            GLES20.glUseProgram(prog)

            val sAsp = if (sH > 0) sW.toFloat() / sH else 9f / 16f

            // texA / texB 纹理坐标变换（含各自图片的平移/缩放/旋转）
            val stMatA = buildTextureMatrix(bmpA, transformAt(fromIdx), stScratchA)
            val stMatB = buildTextureMatrix(bmpB, transformAt(toIdx), stScratchB)

            val mvp = FloatArray(16)
            Matrix.setIdentityM(mvp, 0)

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uSTA"), 1, false, stMatA, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uSTB"), 1, false, stMatB, 0)

            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            val aTex = GLES20.glGetAttribLocation(prog, "aTex")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vBuf)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tBuf)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texAId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "sTexA"), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "sTexB"), 1)

            // 通用参数 + fade alpha
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uProgress"), progress)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uDirection"), direction.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uEdgeSoftness"), paramEdgeSoftness)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uScreenWidth"), sW.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uFadeAlpha"), fadeAlpha)

            // 玻璃效果专属参数
            if (activeEffectType == ScanlineEffectType.CORRUGATED_GLASS ||
                activeEffectType == ScanlineEffectType.REEDED_GLASS ||
                activeEffectType == ScanlineEffectType.PRISM_GLASS) {
                val wavelengthNorm = paramStripedWavelength / sW.toFloat()
                val amplitudeNorm = paramStripedAmplitude / sW.toFloat()
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uScreenHeight"), sH.toFloat())
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAmplitude"), amplitudeNorm)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uWavelength"), wavelengthNorm.coerceAtLeast(0.001f))
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHighlightStrength"), 0.0f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uShadowStrength"), 0.0f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uColorSaturation"), 1.2f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uColorContrast"), 1.1f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uGlassFullWidth"), glassFullWidth)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uGlassBandWidth"), paramGlassBandWidth)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uPhase"), glassPhase)

                if (activeEffectType == ScanlineEffectType.PRISM_GLASS) {
                    val narrowWavelengthNorm = paramNarrowWavelength / sW.toFloat()
                    val narrowAmplitudeNorm = paramNarrowAmplitude / sW.toFloat()
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uNarrowAmplitude"), narrowAmplitudeNorm)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uNarrowWavelength"), narrowWavelengthNorm.coerceAtLeast(0.001f))
                }
            }

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // 关闭 blending
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        private fun drawSingle() {
            // ✅ 限制索引范围，确保不越界
            drawSingleAt(displayedIntIndex.coerceIn(0, maxOf(0, imageCount - 1)))
        }
        
        /**
         * 绘制指定索引的单张图片
         */
        private fun drawSingleAt(targetIdx: Int) {
            val (bitmapIndex, bitmap) = synchronized(bitmapsLock) {
                val index = if (bitmaps.getOrNull(targetIdx)?.isRecycled == false) targetIdx else bitmaps
                    .withIndex()
                    .asSequence()
                    .filter { (_, candidate) -> candidate?.isRecycled == false }
                    .minByOrNull { (index, _) -> kotlin.math.abs(index - targetIdx) }
                    ?.index ?: -1
                index to bitmaps.getOrNull(index)
            }
            
            if (bitmap == null || bitmap.isRecycled) return
            
            uploadBitmapSync(bitmap)

            GLES20.glUseProgram(singleProg)

            val stMat = buildTextureMatrix(bitmap, transformAt(bitmapIndex), stScratchSingle)
            val mvp = FloatArray(16)
            Matrix.setIdentityM(mvp, 0)

            val aPos = GLES20.glGetAttribLocation(singleProg, "aPos")
            val aTex = GLES20.glGetAttribLocation(singleProg, "aTex")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vBuf)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tBuf)

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(singleProg, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(singleProg, "uST"), 1, false, stMat, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iTexId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // 关闭 blending
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        private fun drawTransition() {
            drawTransitionAt(scanFromIndex, scanToIndex, scanProgress, scanDirection)
        }

        private fun createProg(v: String, f: String): Int {
            val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
            GLES20.glShaderSource(vs, v)
            GLES20.glCompileShader(vs)
            
            // 检查顶点着色器编译状态
            val vStatus = IntArray(1)
            GLES20.glGetShaderiv(vs, GLES20.GL_COMPILE_STATUS, vStatus, 0)
            if (vStatus[0] == GLES20.GL_FALSE) {
                Log.e(TAG, "Vertex shader compile error: ${GLES20.glGetShaderInfoLog(vs)}")
            }
            
            val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
            GLES20.glShaderSource(fs, f)
            GLES20.glCompileShader(fs)
            
            // 检查片段着色器编译状态
            val fStatus = IntArray(1)
            GLES20.glGetShaderiv(fs, GLES20.GL_COMPILE_STATUS, fStatus, 0)
            if (fStatus[0] == GLES20.GL_FALSE) {
                Log.e(TAG, "Fragment shader compile error: ${GLES20.glGetShaderInfoLog(fs)}")
            }
            
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            
            // 检查链接状态
            val lStatus = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, lStatus, 0)
            if (lStatus[0] == GLES20.GL_FALSE) {
                Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(p)}")
            }
            
            return p
        }

        private fun destroyEGL() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)

                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }

                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                    context = EGL14.EGL_NO_CONTEXT
                }

                EGL14.eglTerminate(display)
                display = EGL14.EGL_NO_DISPLAY
            }
        }

        fun finish() {
            isRunning.set(false)
            interrupt()
            try {
                join(1000)
            } catch (e: InterruptedException) { }
        }

        fun finishAndWait(timeoutMs: Long) {
            isRunning.set(false)
            isSurfaceValid.set(false)
            messageQueue.clear()
            interrupt()
            try {
                join(timeoutMs)
            } catch (e: InterruptedException) { }
        }
    }

    companion object {
        private const val TAG = "RasterGLRenderer"
        
        // ── 传感器处理常量 ──
        // 角度常量（弧度）
        const val DEAD_ZONE_RAD = 0.026f       // 约 1.5 度
        const val MAX_ANGLE_RAD = 0.785f       // 约 45 度，灵敏度=1.0 时倾斜此角度显示最后一张图
        const val DIRECTION_THRESHOLD = 0.05f  // 方向判断阈值
        
        // 滤波参数
        const val FILTER_ALPHA = 0.15f
        const val VELOCITY_THRESHOLD = 0.01f
        
        // 自适应平滑参数
        const val FAST_VELOCITY_THRESHOLD = 0.1f
        const val MEDIUM_VELOCITY_THRESHOLD = 0.03f
        const val HISTORY_SIZE = 8
        
        // 灵敏度范围
        const val MIN_SENSITIVITY = 0.5f
        const val MAX_SENSITIVITY = 9.0f
        
        // 最小变化阈值
        const val MIN_CHANGE_THRESHOLD = 0.001f

        // 图片仅为当前扫描线保留有限窗口，防止大图组把 Java 堆与 GPU 上传压力同时拉满。
        private const val RESIDENT_BEFORE = 1
        private const val RESIDENT_AFTER = 2
        private const val DECODE_SCALE = 1.25f
    }
}
