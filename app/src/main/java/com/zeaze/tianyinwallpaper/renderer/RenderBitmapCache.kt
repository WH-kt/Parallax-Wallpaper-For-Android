package com.zeaze.tianyinwallpaper.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * 渲染级 Bitmap 缓存
 * 解决以下问题：
 * 1. 避免 HARDWARE 位图与 OpenGL 不兼容问题
 * 2. 防止重复解码同一图片
 * 3. 支持预加载机制
 * 4. 内存管理优化
 */
object RenderBitmapCache {

    private const val TAG = "RenderBitmapCache"
    
    // 内存缓存不能挤占渲染器、系统壁纸和 Compose 本身的堆空间。
    private const val MEMORY_DIVISOR = 16L
    private const val MAX_CACHE_BYTES = 48L * 1024L * 1024L
    
    // 缓存状态
    private val isInitialized = AtomicBoolean(false)
    
    // 内存缓存
    private lateinit var memoryCache: LruCache<String, Bitmap>
    
    // 正在加载的任务
    private val pendingLoads = ConcurrentHashMap<String, Future<*>>()
    
    // 线程池
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    
    // 回调管理
    private val callbacks = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()
    
    /**
     * 初始化缓存
     */
    fun init() {
        if (isInitialized.getAndSet(true)) return
        
        val maxMemory = Runtime.getRuntime().maxMemory()
        val cacheSize = min(
            maxMemory / MEMORY_DIVISOR / 1024L,
            MAX_CACHE_BYTES / 1024L
        ).toInt()
        
        memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return value.byteCount / 1024
            }
            
            override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
                // 注意：不在这里 recycle，因为 Bitmap 可能还在被 OpenGL 使用
                // Bitmap 的回收由调用者负责
            }
        }
    }
    
    /**
     * 生成缓存键
     */
    fun generateKey(uri: Uri, targetWidth: Int = 0, targetHeight: Int = 0): String {
        return "${uri}|${targetWidth}x${targetHeight}"
    }
    
    /**
     * 从缓存获取 Bitmap
     */
    fun get(key: String): Bitmap? {
        if (!isInitialized.get()) init()
        return memoryCache.get(key)?.takeIf { !it.isRecycled } ?: run {
            memoryCache.remove(key)
            null
        }
    }
    
    /**
     * 将 Bitmap 放入缓存
     */
    fun put(key: String, bitmap: Bitmap) {
        if (!isInitialized.get()) init()
        if (!bitmap.isRecycled) memoryCache.put(key, bitmap)
    }
    
    /**
     * 同步加载图片（带缓存）
     * 强制使用 SOFTWARE 配置避免 HARDWARE 位图问题
     */
    fun loadSync(
        context: Context,
        uri: Uri,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? {
        if (!isInitialized.get()) init()
        
        val key = generateKey(uri, targetWidth, targetHeight)
        
        // 检查缓存
        get(key)?.let { return it }
        
        // 解码图片。只有需要采样时才进行 bounds pass；旧实现即使不采样也会无用地打开一次流。
        return try {
            val options = BitmapFactory.Options().apply {
                // 强制使用 ARGB_8888 软件位图，避免 HARDWARE 配置
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            if (targetWidth > 0 && targetHeight > 0) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                options.inSampleSize = calculateInSampleSize(
                    bounds.outWidth, bounds.outHeight, targetWidth, targetHeight
                )
            }

            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)?.let { bitmap ->
                    // 确保不是 HARDWARE 位图
                    ensureSoftwareBitmap(bitmap)?.let { softwareBitmap ->
                        memoryCache.put(key, softwareBitmap)
                        softwareBitmap
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load bitmap: $uri", e)
            null
        } catch (e: OutOfMemoryError) {
            // 让调用方显示占位/上一帧，而不是让单张异常大图终止壁纸服务进程。
            Log.w(TAG, "Not enough memory to load bitmap: $uri", e)
            memoryCache.evictAll()
            null
        }
    }
    
    /**
     * 异步加载图片
     */
    fun loadAsync(
        context: Context,
        uri: Uri,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        callback: (Bitmap?) -> Unit
    ) {
        if (!isInitialized.get()) init()
        
        val key = generateKey(uri, targetWidth, targetHeight)
        
        // 检查缓存
        get(key)?.let {
            callback(it)
            return
        }
        
        // 添加回调
        callbacks.getOrPut(key) { mutableListOf() }.add(callback)
        
        // 如果已有相同任务在执行，不重复创建
        if (pendingLoads.containsKey(key)) return
        
        // 提交加载任务
        val future = executor.submit {
            val bitmap = loadSync(context, uri, targetWidth, targetHeight)
            
            // 通知所有回调
            callbacks.remove(key)?.forEach { it(bitmap) }
            pendingLoads.remove(key)
        }
        
        pendingLoads[key] = future
    }
    
    /**
     * 批量预加载图片
     */
    fun preload(
        context: Context,
        uris: List<Uri>,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ) {
        if (!isInitialized.get()) init()
        
        executor.submit {
            uris.forEachIndexed { index, uri ->
                loadSync(context, uri, targetWidth, targetHeight)
                onProgress?.invoke(index + 1, uris.size)
            }
            onComplete?.invoke()
        }
    }
    
    /**
     * 取消加载任务
     */
    fun cancelLoad(key: String) {
        pendingLoads[key]?.cancel(true)
        pendingLoads.remove(key)
        callbacks.remove(key)
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        memoryCache.evictAll()
        callbacks.clear()
        pendingLoads.values.forEach { it.cancel(true) }
        pendingLoads.clear()
    }
    
    /**
     * 从缓存移除指定项
     */
    fun remove(key: String) {
        memoryCache.remove(key)
    }
    
    /**
     * 获取缓存统计
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            hitCount = memoryCache.hitCount(),
            missCount = memoryCache.missCount(),
            putCount = memoryCache.putCount(),
            evictCount = memoryCache.evictionCount(),
            size = memoryCache.size()
        )
    }
    
    /**
     * 计算采样率
     */
    private fun calculateInSampleSize(
        width: Int, height: Int,
        reqWidth: Int, reqHeight: Int
    ): Int {
        var inSampleSize = 1
        
        if (height <= reqHeight || width <= reqWidth) return inSampleSize

        // 支持非 2 的幂采样，避免大图在目标尺寸附近仍被解码成数倍于屏幕的 Bitmap。
        val widthRatio = width / reqWidth.coerceAtLeast(1)
        val heightRatio = height / reqHeight.coerceAtLeast(1)
        return max(1, min(widthRatio, heightRatio))
    }
    
    /**
     * 确保是 SOFTWARE 位图
     */
    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap? {
        return try {
            // Android O (API 26)+ 检查配置
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                bitmap.config == Bitmap.Config.HARDWARE) {
                // 转换为软件位图
                bitmap.copy(Bitmap.Config.ARGB_8888, false).also {
                    bitmap.recycle()
                }
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure software bitmap", e)
            null
        }
    }
    
    /**
     * 缓存统计信息
     */
    data class CacheStats(
        val hitCount: Int,
        val missCount: Int,
        val putCount: Int,
        val evictCount: Int,
        val size: Int
    )
}
