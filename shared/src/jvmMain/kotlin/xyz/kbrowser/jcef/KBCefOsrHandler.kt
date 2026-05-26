package xyz.kbrowser.jcef

import org.cef.OS
import org.cef.browser.CefBrowser
import org.cef.callback.CefDragData
import org.cef.handler.CefRenderHandler
import org.cef.handler.CefScreenInfo
import org.cef.misc.CefRange
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.image.VolatileImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.ceil

open class KBCefOsrHandler(
    private val component: JComponent,
    private val screenBoundsProvider: (JComponent) -> Rectangle
) : CefRenderHandler {

    @Volatile
    protected var myImage: BufferedImage? = null

    @Volatile
    protected var myPopupImage: BufferedImage? = null

    @Volatile
    private var myPopupShown = false

    @Volatile
    private var myPopupBounds = Rectangle()
    protected val myPopupMutex = Any()

    @Volatile
    private var myVolatileImage: VolatileImage? = null

    @Volatile
    protected var myContentOutdated = false

    @Volatile
    var pixelDensity: Double = 1.0
        protected set

    @Volatile
    var scaleFactor: Double = 1.0
        protected set

    private val myLocationOnScreenRef = AtomicReference(Point())
    private var myResizePusherAlarm: Timer? = null
    private var resizePushStarted: Long = 0
    private val RESIZE_PUSHER_TIMEOUT_MS: Long = 2000

    override fun getViewRect(browser: CefBrowser): Rectangle {
        val comp = browser.uiComponent
        val scale = scaleFactor
        val width = comp.width / scale
        val height = comp.height / scale
        return Rectangle(0, 0, ceil(width).toInt(), ceil(height).toInt())
    }

    override fun getScreenInfo(browser: CefBrowser, screenInfo: CefScreenInfo): Boolean {
        val rect = screenBoundsProvider(component)
        val scale = scaleFactor * pixelDensity
        screenInfo.Set(scale, 32, 4, false, rect, rect)
        return true
    }

    override fun getScreenPoint(browser: CefBrowser, viewPoint: Point): Point {
        val pt = viewPoint.location
        val loc = myLocationOnScreenRef.get()
        if (OS.isMacintosh()) {
            val rect = screenBoundsProvider(component)
            pt.setLocation(loc.x + pt.x, rect.height - loc.y - pt.y)
        } else {
            pt.translate(loc.x, loc.y)
        }
        return if (OS.isMacintosh()) pt else toRealCoordinates(pt)
    }

    override fun getDeviceScaleFactor(browser: CefBrowser): Double {
        return scaleFactor * pixelDensity
    }

    override fun onPopupShow(browser: CefBrowser, show: Boolean) {
        synchronized(myPopupMutex) {
            myPopupShown = show
        }
    }

    override fun onPopupSize(browser: CefBrowser, size: Rectangle) {
        synchronized(myPopupMutex) {
            myPopupBounds = size
        }
    }

    override fun onPaint(
        browser: CefBrowser,
        popup: Boolean,
        dirtyRects: Array<Rectangle>,
        buffer: ByteBuffer,
        width: Int,
        height: Int
    ) {
        println("[OSR-DBG] onPaint called: popup=$popup, ${width}x${height}")
        val rect = getViewRect(browser)
        val w = rect.width * pixelDensity
        val h = rect.height * pixelDensity
        if (w != width.toDouble() || h != height.toDouble()) {
            startResizePusher(browser, false)
        } else {
            stopResizePusher()
        }

        var image = if (popup) myPopupImage else myImage
        if (image == null || image.width != width || image.height != height) {
            image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
        }

        if (popup) {
            synchronized(myPopupMutex) {
                drawByteBuffer(image, buffer, dirtyRects)
                myPopupImage = image
            }
        } else {
            drawByteBuffer(image, buffer, dirtyRects)
            myImage = image
        }

        myContentOutdated = true
        SwingUtilities.invokeLater {
            if (!browser.uiComponent.isShowing) return@invokeLater
            browser.uiComponent.repaint()
        }
    }

    override fun onCursorChange(browser: CefBrowser, cursorType: Int): Boolean {
        SwingUtilities.invokeLater { browser.uiComponent.cursor = Cursor(cursorType) }
        return true
    }

    override fun startDragging(browser: CefBrowser, dragData: CefDragData, mask: Int, x: Int, y: Int): Boolean {
        return false
    }

    override fun updateDragCursor(browser: CefBrowser, operation: Int) {}

    override fun OnImeCompositionRangeChanged(
        browser: CefBrowser,
        selectionRange: CefRange,
        characterBounds: Array<Rectangle>
    ) {
    }

    override fun OnTextSelectionChanged(browser: CefBrowser, selectedText: String, selectionRange: CefRange) {
    }

    fun setLocationOnScreen(location: Point) {
        myLocationOnScreenRef.set(location)
    }

    fun setScreenInfo(density: Double, scale: Double) {
        pixelDensity = density
        scaleFactor = scale
    }

    private fun toRealCoordinates(pt: Point): Point {
        val scale = pixelDensity
        return Point(Math.round(pt.x * scale).toInt(), Math.round(pt.y * scale).toInt())
    }

    protected open fun getCurrentFrameSize(): Dimension? {
        val image = myImage ?: return null
        return Dimension(image.width, image.height)
    }

    fun paint(g: Graphics2D) {
        val frameSize = getCurrentFrameSize()
        println("[OSR-DBG] paint called, frameSize=$frameSize")
        frameSize ?: return
        var vi = myVolatileImage

        val scale = if (pixelDensity > 0.0) pixelDensity else 1.0
        val logicalW = (frameSize.width / scale).toInt()
        val logicalH = (frameSize.height / scale).toInt()

        do {
            val contentOutdated = myContentOutdated
            myContentOutdated = false
            if (vi == null || vi.width != logicalW || vi.height != logicalH) {
                vi = createVolatileImage(g, logicalW, logicalH)
            } else if (contentOutdated) {
                drawVolatileImage(vi)
            }

            when (vi.validate(g.deviceConfiguration)) {
                VolatileImage.IMAGE_RESTORED -> drawVolatileImage(vi)
                VolatileImage.IMAGE_INCOMPATIBLE -> vi = createVolatileImage(g, logicalW, logicalH)
            }

            g.drawImage(vi, 0, 0, component.width, component.height, null)
        } while (vi.contentsLost())

        myVolatileImage = vi

        if (myPopupShown) {
            synchronized(myPopupMutex) {
                val popupImage = myPopupImage
                if (myPopupShown && popupImage != null) {
                    g.drawImage(popupImage, myPopupBounds.x, myPopupBounds.y, myPopupBounds.width, myPopupBounds.height, null)
                }
            }
        }
    }

    protected open fun drawVolatileImage(vi: VolatileImage) {
        val image = myImage
        val g = vi.graphics as Graphics2D
        try {
            g.background = Color(0, 0, 0, 0)
            g.composite = AlphaComposite.Src
            g.clearRect(0, 0, vi.width, vi.height)
            if (image != null) {
                g.drawImage(image, 0, 0, vi.width, vi.height, null)
            }
        } finally {
            g.dispose()
        }
    }

    private fun createVolatileImage(g: Graphics2D, width: Int, height: Int): VolatileImage {
        val image = g.deviceConfiguration.createCompatibleVolatileImage(width, height, Transparency.TRANSLUCENT)
        val gimg = image.graphics as Graphics2D
        gimg.background = Color(0, 0, 0, 0)
        gimg.composite = AlphaComposite.Src
        gimg.clearRect(0, 0, image.width, image.height)
        gimg.dispose()
        drawVolatileImage(image)
        return image
    }

    open fun getColorAt(x: Int, y: Int): Color? {
        val image = myImage ?: return null
        if (x >= image.width || y >= image.height || x < 0 || y < 0) return null
        return Color(image.getRGB(x, y), true)
    }

    fun startResizePusher(browser: CefBrowser, resetTimeout: Boolean) {
        SwingUtilities.invokeLater {
            if (component.isShowing) {
                startResizePusherImpl(browser, resetTimeout)
            }
        }
    }

    private fun startResizePusherImpl(browser: CefBrowser, resetTimeout: Boolean) {
        if (myResizePusherAlarm != null) {
            if (resetTimeout) {
                resizePushStarted = System.currentTimeMillis()
            }
            return
        }

        myResizePusherAlarm = Timer(20, ActionListener {
            if (System.currentTimeMillis() - resizePushStarted > RESIZE_PUSHER_TIMEOUT_MS) {
                stopResizePusher()
                return@ActionListener
            }
            browser.invalidate()
        })
        myResizePusherAlarm?.isRepeats = true
        myResizePusherAlarm?.start()
        resizePushStarted = System.currentTimeMillis()
    }

    fun stopResizePusher() {
        SwingUtilities.invokeLater {
            myResizePusherAlarm?.stop()
            myResizePusherAlarm = null
            resizePushStarted = 0
        }
    }

    companion object {
        private fun drawByteBuffer(dst: BufferedImage, src: ByteBuffer, rectangles: Array<Rectangle>) {
            val dstData = (dst.raster.dataBuffer as DataBufferInt).data
            val srcData = src.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            val imgWidth = dst.width
            val capacity = src.capacity()

            for (rect in rectangles) {
                if (rect.width < imgWidth) {
                    for (line in rect.y until (rect.y + rect.height)) {
                        val offset = line * imgWidth + rect.x
                        val length = Math.min(rect.width, capacity - offset)
                        if (length > 0) {
                            srcData.position(offset).get(dstData, offset, length)
                        }
                    }
                } else {
                    val offset = rect.y * imgWidth
                    val length = Math.min(rect.height * imgWidth, capacity - offset)
                    if (length > 0) {
                        srcData.position(offset).get(dstData, offset, length)
                    }
                }
            }
        }
    }
}
