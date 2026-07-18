package xyz.kbrowser.jcef

import org.cef.browser.CefBrowser
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.VolatileImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.RepaintManager

import org.cef.handler.CefNativeRenderHandler

/**
 * Ported from IntelliJ IDEA's JBCefNativeOsrHandler.
 * Utilizes reflection to access JBR and SharedMemory features to prevent compile-time classpath issues.
 */
class KBCefNativeOsrHandler(
    component: JComponent,
    screenBoundsProvider: (JComponent) -> Rectangle
) : KBCefOsrHandler(component, screenBoundsProvider), CefNativeRenderHandler {

    companion object {
        @Volatile private var isReflectInitialized = false
        var lockMethod: java.lang.reflect.Method? = null
        var unlockMethod: java.lang.reflect.Method? = null
        var getPtrMethod: java.lang.reflect.Method? = null
        var getWidthMethod: java.lang.reflect.Method? = null
        var getHeightMethod: java.lang.reflect.Method? = null
        var getRectsOffsetMethod: java.lang.reflect.Method? = null
        var getDirtyRectsCountMethod: java.lang.reflect.Method? = null

        var wrapRasterMethod: java.lang.reflect.Method? = null
        var wrapRectsMethod: java.lang.reflect.Method? = null
        var setWidthMethod: java.lang.reflect.Method? = null
        var setHeightMethod: java.lang.reflect.Method? = null
        var setDirtyRectsCountMethod: java.lang.reflect.Method? = null

        // JBR 反射方法缓存（避免每帧 Class.forName + getMethod）
        @Volatile private var jbrInitialized = false
        private var getNativeRasterLoaderMethod: java.lang.reflect.Method? = null
        private var loadNativeRasterMethod: java.lang.reflect.Method? = null
        private var isNativeRasterLoaderSupportedMethod: java.lang.reflect.Method? = null
        private var cachedRasterLoader: Any? = null
        @Volatile private var cachedNativeRasterSupported: Boolean? = null

        fun initReflection(frameClass: Class<*>) {
            // Double-checked locking：volatile 读无锁快速返回，避免每帧获取锁
            if (isReflectInitialized) return
            synchronized(Companion::class.java) {
                if (isReflectInitialized) return
                try {
                    lockMethod = frameClass.getMethod("lock").apply { isAccessible = true }
                    unlockMethod = frameClass.getMethod("unlock").apply { isAccessible = true }
                    getPtrMethod = frameClass.getMethod("getPtr").apply { isAccessible = true }
                    getWidthMethod = frameClass.getMethod("getWidth").apply { isAccessible = true }
                    getHeightMethod = frameClass.getMethod("getHeight").apply { isAccessible = true }
                    getRectsOffsetMethod = frameClass.getMethod("getRectsOffset").apply { isAccessible = true }
                    getDirtyRectsCountMethod = frameClass.getMethod("getDirtyRectsCount").apply { isAccessible = true }

                    wrapRasterMethod = frameClass.getMethod("wrapRaster").apply { isAccessible = true }
                    wrapRectsMethod = frameClass.getMethod("wrapRects").apply { isAccessible = true }
                    setWidthMethod = frameClass.getMethod("setWidth", Int::class.java).apply { isAccessible = true }
                    setHeightMethod = frameClass.getMethod("setHeight", Int::class.java).apply { isAccessible = true }
                    setDirtyRectsCountMethod = frameClass.getMethod("setDirtyRectsCount", Int::class.java).apply { isAccessible = true }

                    isReflectInitialized = true
                } catch (e: Exception) {
                    println("[KBCefNativeOsrHandler] Reflection init failed: \${e.message}")
                }
            }
        }

        private fun initJbrReflection() {
            // Double-checked locking：volatile 读无锁快速返回，避免每帧获取锁
            if (jbrInitialized) return
            synchronized(Companion::class.java) {
                if (jbrInitialized) return
                try {
                    val jbrClass = Class.forName("com.jetbrains.JBR")
                    isNativeRasterLoaderSupportedMethod = jbrClass.getMethod("isNativeRasterLoaderSupported").apply { isAccessible = true }
                    getNativeRasterLoaderMethod = jbrClass.getMethod("getNativeRasterLoader").apply { isAccessible = true }

                    // 预加载 rasterLoader 实例和 loadNativeRaster 方法
                    if (isNativeRasterLoaderSupportedMethod?.invoke(null) as Boolean) {
                        cachedRasterLoader = getNativeRasterLoaderMethod?.invoke(null)
                        val loaderClass = cachedRasterLoader!!.javaClass
                        loadNativeRasterMethod = loaderClass.getMethod(
                            "loadNativeRaster",
                            VolatileImage::class.java, Long::class.java, Int::class.java, Int::class.java, Long::class.java, Int::class.java
                        ).apply { isAccessible = true }
                    }
                } catch (e: Exception) {
                    println("[KBCefNativeOsrHandler] JBR reflection init failed: ${e.message}")
                }
                jbrInitialized = true
            }
        }
    }

    // 对齐 IDEA：默认强制软件渲染，除非显式设置 jcef.remote.enable_hardware_rendering=true
    // IDEA 注释：temporary enabled until fixed IJPL-161293, IJPL-182455
    private val forceUseSoftwareRendering: Boolean = !System.getProperty("jcef.remote.enable_hardware_rendering", "false").toBoolean()
    private var myCurrentFrame: Any? = null // Holds SharedMemory.WithRaster instance
    @Volatile private var myFrameWidth: Int = 0
    @Volatile private var myFrameHeight: Int = 0

    private val mySharedMemCache: Any?
    private val sharedMemCacheGetMethod: java.lang.reflect.Method?

    init {
        var cache: Any? = null
        var getMethod: java.lang.reflect.Method? = null
        try {
            val cacheClass = Class.forName("com.jetbrains.cef.SharedMemoryCache")
            cache = cacheClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            getMethod = cacheClass.getMethod("get", String::class.java, Long::class.java).apply { isAccessible = true }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mySharedMemCache = cache
        sharedMemCacheGetMethod = getMethod
    }

    override fun disposeNativeResources() {}

    override fun onPaintWithSharedMem(
        browser: CefBrowser,
        popup: Boolean,
        dirtyRectsCount: Int,
        sharedMemName: String,
        sharedMemHandle: Long,
        width: Int,
        height: Int
    ) {
        try {
            val cache = mySharedMemCache ?: return
            val getMethod = sharedMemCacheGetMethod ?: return
            val mem = getMethod.invoke(cache, sharedMemName, sharedMemHandle) ?: return

            // 确保反射方法已初始化（onPaintWithSharedMem 先于 drawVolatileImage 被调用）
            initReflection(mem.javaClass)

            // 用缓存的 Method 调用，避免每帧 getMethod 反射查找
            setWidthMethod?.invoke(mem, width)
            setHeightMethod?.invoke(mem, height)
            setDirtyRectsCountMethod?.invoke(mem, dirtyRectsCount)

            if (popup) {
                var image = myPopupImage
                if (image == null || image.width != width || image.height != height) {
                    image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
                }
                synchronized(myPopupMutex) {
                    loadBuffered(image, mem)
                    myPopupImage = image
                }
            } else {
                // 对齐 IDEA：只存 mem 指针，不解析/累积 dirtyRects
                myCurrentFrame = mem
                myFrameWidth = width
                myFrameHeight = height
            }

            myContentOutdated = true
            SwingUtilities.invokeLater {
                val comp = browser.uiComponent
                if (!comp.isShowing) return@invokeLater
                val root = SwingUtilities.getRootPane(comp)
                if (root != null) {
                    val rm = RepaintManager.currentManager(root)
                    val dirtySrc = Rectangle(0, 0, comp.width, comp.height)
                    val dirtyDst = SwingUtilities.convertRectangle(comp, dirtySrc, root)
                    val dx = 1
                    rm.addDirtyRegion(root, dirtyDst.x - dx, dirtyDst.y - dx, dirtyDst.width + dx * 2, dirtyDst.height + dx * 2)
                } else {
                    comp.repaint()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getCurrentFrameSize(): Dimension? {
        val w = myFrameWidth
        val h = myFrameHeight
        if (w > 0 && h > 0) return Dimension(w, h)
        return super.getCurrentFrameSize()
    }

    /**
     * 对齐 IDEA JBCefNativeOsrHandler.drawVolatileImage：
     * - nativeRasterLoader 路径：直接传 frame 内部的 rects 指针（零拷贝）
     * - CPU 回退路径：loadBuffered 到 BufferedImage，再 super.drawVolatileImage
     */
    override fun drawVolatileImage(vi: VolatileImage) {
        val frame = myCurrentFrame ?: return

        synchronized(frame) {
            initReflection(frame.javaClass)
            initJbrReflection()

            try {
                lockMethod?.invoke(frame)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                if (useNativeRasterLoader()) {
                    // 零拷贝路径：直接传 frame 内部的 raster 指针和 rects 指针
                    val ptr = getPtrMethod?.invoke(frame) as Long
                    val width = getWidthMethod?.invoke(frame) as Int
                    val height = getHeightMethod?.invoke(frame) as Int
                    val rectsOffset = getRectsOffsetMethod?.invoke(frame) as Int
                    val dirtyCount = getDirtyRectsCountMethod?.invoke(frame) as Int

                    loadNativeRasterMethod?.invoke(cachedRasterLoader, vi, ptr, width, height, ptr + rectsOffset, dirtyCount)
                } else {
                    // CPU 回退路径：从共享内存拷贝到 BufferedImage
                    val width = getWidthMethod?.invoke(frame) as Int
                    val height = getHeightMethod?.invoke(frame) as Int

                    var image = myImage
                    if (image == null || image.width != width || image.height != height) {
                        image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
                    }
                    loadBuffered(image, frame)
                    myImage = image

                    super.drawVolatileImage(vi)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    unlockMethod?.invoke(frame)
                } catch (_: Exception) {}
            }
        }
    }

    private fun loadBuffered(bufImage: BufferedImage, mem: Any) {
        try {
            val srcW = getWidthMethod?.invoke(mem) as Int
            val srcH = getHeightMethod?.invoke(mem) as Int

            val srcBuffer = wrapRasterMethod?.invoke(mem) as ByteBuffer
            val src = srcBuffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()

            val dstW = bufImage.raster.width
            val dstH = bufImage.raster.height
            val dst = (bufImage.raster.dataBuffer as DataBufferInt).data

            val rectsCount = getDirtyRectsCountMethod?.invoke(mem) as Int
            var dirtyRects = arrayOf(Rectangle(0, 0, srcW, srcH))
            if (rectsCount > 0) {
                dirtyRects = Array(rectsCount) { Rectangle() }
                val rectsMem = wrapRectsMethod?.invoke(mem) as ByteBuffer
                val rects = rectsMem.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                for (c in 0 until rectsCount) {
                    var pos = c * 4
                    val r = Rectangle()
                    r.x = rects.get(pos++)
                    r.y = rects.get(pos++)
                    r.width = rects.get(pos++)
                    r.height = rects.get(pos)
                    dirtyRects[c] = r
                }
            }

            for (rect in dirtyRects) {
                if (rect.width < srcW || dstW != srcW) {
                    for (line in rect.y until (rect.y + rect.height)) {
                        copyLine(src, srcW, srcH, dst, dstW, dstH, rect.x, line, rect.x + rect.width)
                    }
                } else {
                    val offset = rect.y * srcW
                    if (rect.y + rect.height <= dstH) {
                        src.position(offset).get(dst, offset, srcW * rect.height)
                    } else {
                        src.position(offset).get(dst, offset, srcW * (dstH - rect.y))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun copyLine(src: java.nio.IntBuffer, sw: Int, sh: Int, dst: IntArray, dw: Int, dh: Int, x0: Int, y0: Int, x1: Int) {
        if (x0 < 0 || x0 >= sw || x0 >= dw || x1 <= x0) return
        if (y0 < 0 || y0 >= sh || y0 >= dh) return

        val offsetSrc = y0 * sw + x0
        val offsetDst = y0 * dw + x0
        if (x1 > dw) {
            src.position(offsetSrc).get(dst, offsetDst, dw - x0)
        } else {
            src.position(offsetSrc).get(dst, offsetDst, x1 - x0)
        }
    }

    private fun useNativeRasterLoader(): Boolean {
        if (forceUseSoftwareRendering) return false
        cachedNativeRasterSupported?.let { return it }
        if (!jbrInitialized) initJbrReflection()
        val result = try {
            isNativeRasterLoaderSupportedMethod?.invoke(null) as Boolean
        } catch (e: Exception) {
            false
        }
        cachedNativeRasterSupported = result
        return result
    }

    /**
     * Returns the current frame as a [BufferedImage] at physical pixel resolution.
     *
     * For the NativeRasterLoader path the frame lives in SharedMemory and is never
     * copied into [myImage], so we materialise it here on demand.  For the CPU-
     * fallback path [myImage] is already populated by [drawVolatileImage], so we
     * delegate to the parent.
     */
    override fun getRenderedImage(): BufferedImage? {
        val frame = myCurrentFrame
        if (frame == null || !useNativeRasterLoader()) {
            return super.getRenderedImage()
        }

        return try {
            initReflection(frame.javaClass)
            lockMethod?.invoke(frame)

            val width = getWidthMethod?.invoke(frame) as Int
            val height = getHeightMethod?.invoke(frame) as Int
            if (width <= 0 || height <= 0) return super.getRenderedImage()

            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
            loadBuffered(image, frame)
            image
        } catch (e: Exception) {
            e.printStackTrace()
            super.getRenderedImage()
        } finally {
            try {
                unlockMethod?.invoke(frame)
            } catch (_: Exception) {}
        }
    }

    override fun getColorAt(x: Int, y: Int): Color? {
        val frame = myCurrentFrame
        if (frame == null || !useNativeRasterLoader()) {
            return super.getColorAt(x, y)
        }

        return try {
            initReflection(frame.javaClass)
            lockMethod?.invoke(frame)

            val byteBuffer = wrapRasterMethod?.invoke(frame) as ByteBuffer
            val width = getWidthMethod?.invoke(frame) as Int
            val height = getHeightMethod?.invoke(frame) as Int

            if (x < 0 || x >= width || y < 0 || y >= height) return null

            val colorVal = byteBuffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(y * width + x)
            Color(colorVal, true)
        } catch (e: Exception) {
            null
        } finally {
            try {
                unlockMethod?.invoke(frame)
            } catch (_: Exception) {}
        }
    }
}
