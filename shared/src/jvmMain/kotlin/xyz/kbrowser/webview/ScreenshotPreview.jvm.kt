package xyz.kbrowser.webview

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

actual fun showScreenshotPreview(bytes: ByteArray) {
    val image: BufferedImage = ImageIO.read(ByteArrayInputStream(bytes)) ?: return
    SwingUtilities.invokeLater {
        ScreenshotPreviewWindow(image).isVisible = true
    }
}

/**
 * Screenshot preview window.
 *
 * - Sized 1:1 to the image resolution (CSS pixels), not resizable
 * - Draws a live "(x, y)" label next to the cursor while hovering
 * - Coordinates match the KBPage.clickByCoordinates / screenshot coordinate system
 */
private class ScreenshotPreviewWindow(private val image: BufferedImage) : JFrame() {

    init {
        title = "截图预览  ${image.width} × ${image.height} px  |  移动鼠标查看坐标"
        isResizable = false
        defaultCloseOperation = DISPOSE_ON_CLOSE

        val panel = ImagePanel(image)
        contentPane.add(panel)

        pack()
        setLocationRelativeTo(null)
    }
}

private class ImagePanel(private val image: BufferedImage) : JPanel() {

    @Volatile private var mouseX: Int = -1
    @Volatile private var mouseY: Int = -1

    private val labelFont = Font(Font.MONOSPACED, Font.BOLD, 12)
    private val labelPadH = 6
    private val labelPadV = 3
    private val labelOffsetX = 14
    private val labelOffsetY = 14

    init {
        preferredSize = Dimension(image.width, image.height)
        background = Color.BLACK
        isFocusable = true

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                mouseX = e.x
                mouseY = e.y
                repaint()
            }
            override fun mouseDragged(e: MouseEvent) {
                mouseX = e.x
                mouseY = e.y
                repaint()
            }
        })

        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                mouseX = -1
                mouseY = -1
                repaint()
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)

        // Draw the image 1:1 with no scaling so cursor coords match image pixels
        g2.drawImage(image, 0, 0, null)

        if (mouseX >= 0 && mouseY >= 0) {
            val label = "(${mouseX}, ${mouseY})"
            g2.font = labelFont
            val fm = g2.fontMetrics
            val textW = fm.stringWidth(label)
            val textH = fm.ascent

            val boxW = textW + labelPadH * 2
            val boxH = textH + labelPadV * 2
            var bx = mouseX + labelOffsetX
            var by = mouseY + labelOffsetY
            if (bx + boxW > width)  bx = mouseX - labelOffsetX - boxW
            if (by + boxH > height) by = mouseY - labelOffsetY - boxH

            g2.color = Color(0, 0, 0, 180)
            g2.fillRoundRect(bx, by, boxW, boxH, 6, 6)

            g2.color = Color(100, 200, 255, 200)
            g2.drawRoundRect(bx, by, boxW, boxH, 6, 6)

            g2.color = Color(220, 240, 255)
            g2.drawString(label, bx + labelPadH, by + labelPadV + textH - fm.descent)

            g2.color = Color(100, 200, 255, 120)
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)
            g2.drawLine(mouseX, 0, mouseX, height)
            g2.drawLine(0, mouseY, width, mouseY)
        }
    }
}
