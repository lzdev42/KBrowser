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
import java.util.concurrent.locks.ReentrantLock
import javax.swing.JComponent
import javax.swing.SwingUtilities

import org.cef.handler.CefNativeRenderHandler

/**
 * Ported from IntelliJ IDEA's JBCefNativeOsrHandler.
 * Utilizes reflection to access JBR and SharedMemory features to prevent compile-time classpath issues.
 */
class KBCefNativeOsrHandler(
    component: JComponent,
    screenBoundsProvider: (JComponent) -> Rectangle
) : KBCefOsrHandler(component, screenBoundsProvider), CefNativeRenderHandler {

    private val forceUseSoftwareRendering: Boolean = System.getProperty("jcef.remote.enable_hardware_rendering") == "false"
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
            val mem = getMethod.invoke(cache, sharedMemName, sharedMemHandle)

            val memClass = mem.javaClass
            memClass.getMethod("setWidth", Int::class.java).apply { isAccessible = true }.invoke(mem, width)
            memClass.getMethod("setHeight", Int::class.java).apply { isAccessible = true }.invoke(mem, height)
            memClass.getMethod("setDirtyRectsCount", Int::class.java).apply { isAccessible = true }.invoke(mem, dirtyRectsCount)

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
                myCurrentFrame = mem
                myFrameWidth = width
                myFrameHeight = height
            }

            myContentOutdated = true
            SwingUtilities.invokeLater {
                if (!browser.uiComponent.isShowing) return@invokeLater
                browser.uiComponent.repaint()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getCurrentFrameSize(): Dimension? {
        val w = myFrameWidth
        val h = myFrameHeight
        if (w > 0 && h > 0) return Dimension(w, h)
        val fallback = super.getCurrentFrameSize()
        return fallback
    }

    override fun drawVolatileImage(vi: VolatileImage) {
        val frame = myCurrentFrame
        if (frame == null) {
            super.drawVolatileImage(vi)
            return
        }
        
        var nativeRasterLoaded = false
        val frameClass = frame.javaClass

        try {
            frameClass.getMethod("lock").apply { isAccessible = true }.invoke(frame)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            if (useNativeRasterLoader()) {
                val jbrClass = Class.forName("com.jetbrains.JBR")
                val getNativeRasterLoaderMethod = jbrClass.getMethod("getNativeRasterLoader").apply { isAccessible = true }
                val rasterLoader = getNativeRasterLoaderMethod.invoke(null)
                
                val getPtrMethod = frameClass.getMethod("getPtr").apply { isAccessible = true }
                val ptr = getPtrMethod.invoke(frame) as Long
                
                val getWidthMethod = frameClass.getMethod("getWidth").apply { isAccessible = true }
                val width = getWidthMethod.invoke(frame) as Int
                
                val getHeightMethod = frameClass.getMethod("getHeight").apply { isAccessible = true }
                val height = getHeightMethod.invoke(frame) as Int
                
                val getRectsOffsetMethod = frameClass.getMethod("getRectsOffset").apply { isAccessible = true }
                val rectsOffset = getRectsOffsetMethod.invoke(frame) as Int
                
                val getDirtyRectsCountMethod = frameClass.getMethod("getDirtyRectsCount").apply { isAccessible = true }
                val dirtyRectsCount = getDirtyRectsCountMethod.invoke(frame) as Int
                
                val loadMethod = rasterLoader.javaClass.getMethod(
                    "loadNativeRaster", 
                    VolatileImage::class.java, Long::class.java, Int::class.java, Int::class.java, Long::class.java, Int::class.java
                ).apply { isAccessible = true }
                
                loadMethod.invoke(rasterLoader, vi, ptr, width, height, ptr + rectsOffset, dirtyRectsCount)
                nativeRasterLoaded = true
            }
        } catch (e: Exception) {
            println("[KBCefNativeOsrHandler] NativeRasterLoader failed, falling back to CPU buffering: ${e.message}")
        }

        try {
            if (!nativeRasterLoaded) {
                val getWidthMethod = frameClass.getMethod("getWidth").apply { isAccessible = true }
                val width = getWidthMethod.invoke(frame) as Int
                
                val getHeightMethod = frameClass.getMethod("getHeight").apply { isAccessible = true }
                val height = getHeightMethod.invoke(frame) as Int
                
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
                frameClass.getMethod("unlock").apply { isAccessible = true }.invoke(frame)
            } catch (e: Exception) { }
        }
    }
    
    private fun loadBuffered(bufImage: BufferedImage, mem: Any) {
        try {
            val memClass = mem.javaClass
            val srcW = memClass.getMethod("getWidth").apply { isAccessible = true }.invoke(mem) as Int
            val srcH = memClass.getMethod("getHeight").apply { isAccessible = true }.invoke(mem) as Int
            
            val wrapRasterMethod = memClass.getMethod("wrapRaster").apply { isAccessible = true }
            val srcBuffer = wrapRasterMethod.invoke(mem) as ByteBuffer
            val src = srcBuffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            
            val dstW = bufImage.raster.width
            val dstH = bufImage.raster.height
            val dst = (bufImage.raster.dataBuffer as DataBufferInt).data
            
            val rectsCount = memClass.getMethod("getDirtyRectsCount").apply { isAccessible = true }.invoke(mem) as Int
            var dirtyRects = arrayOf(Rectangle(0, 0, srcW, srcH))
            
            if (rectsCount > 0) {
                dirtyRects = Array(rectsCount) { Rectangle() }
                val wrapRectsMethod = memClass.getMethod("wrapRects").apply { isAccessible = true }
                val rectsMem = wrapRectsMethod.invoke(mem) as ByteBuffer
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
        return try {
            val jbrClass = Class.forName("com.jetbrains.JBR")
            val method = jbrClass.getMethod("isNativeRasterLoaderSupported").apply { isAccessible = true }
            method.invoke(null) as Boolean
        } catch (e: Exception) {
            false
        }
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
            // CPU-fallback path: myImage is kept up-to-date by drawVolatileImage
            return super.getRenderedImage()
        }

        return try {
            val frameClass = frame.javaClass
            frameClass.getMethod("lock").apply { isAccessible = true }.invoke(frame)

            val width  = frameClass.getMethod("getWidth").apply  { isAccessible = true }.invoke(frame) as Int
            val height = frameClass.getMethod("getHeight").apply { isAccessible = true }.invoke(frame) as Int
            if (width <= 0 || height <= 0) return super.getRenderedImage()

            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
            loadBuffered(image, frame)
            image
        } catch (e: Exception) {
            e.printStackTrace()
            super.getRenderedImage()
        } finally {
            try {
                val frameClass = frame.javaClass
                frameClass.getMethod("unlock").apply { isAccessible = true }.invoke(frame)
            } catch (_: Exception) {}
        }
    }

    override fun getColorAt(x: Int, y: Int): Color? {
        val frame = myCurrentFrame
        if (frame == null || !useNativeRasterLoader()) {
            return super.getColorAt(x, y)
        }
        
        return try {
            val frameClass = frame.javaClass
            frameClass.getMethod("lock").apply { isAccessible = true }.invoke(frame)
            
            val wrapRasterMethod = frameClass.getMethod("wrapRaster").apply { isAccessible = true }
            val byteBuffer = wrapRasterMethod.invoke(frame) as ByteBuffer
            val width = frameClass.getMethod("getWidth").apply { isAccessible = true }.invoke(frame) as Int
            val height = frameClass.getMethod("getHeight").apply { isAccessible = true }.invoke(frame) as Int
            
            if (x < 0 || x >= width || y < 0 || y >= height) return null
            
            val colorVal = byteBuffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(y * width + x)
            Color(colorVal, true)
        } catch (e: Exception) {
            null
        } finally {
            try {
                val frameClass = frame.javaClass
                frameClass.getMethod("unlock").apply { isAccessible = true }.invoke(frame)
            } catch (e: Exception) {}
        }
    }
}
