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
 * 截图预览窗口。
 *
 * - 窗口尺寸固定为图片分辨率（1:1 CSS 像素），不可缩放
 * - 鼠标在图片上移动时，在光标右下方实时绘制坐标标签 "(x, y)"
 * - 坐标直接对应 KBPage.clickByCoordinates / screenshot 的坐标系
 */
private class ScreenshotPreviewWindow(private val image: BufferedImage) : JFrame() {

    init {
        title = "截图预览  ${image.width} × ${image.height} px  |  移动鼠标查看坐标"
        isResizable = false
        defaultCloseOperation = DISPOSE_ON_CLOSE

        val panel = ImagePanel(image)
        contentPane.add(panel)

        // 固定窗口为图片尺寸（pack() 会根据 preferredSize 自动计算含标题栏的总尺寸）
        pack()

        // 居中显示
        setLocationRelativeTo(null)
    }
}

private class ImagePanel(private val image: BufferedImage) : JPanel() {

    @Volatile private var mouseX: Int = -1
    @Volatile private var mouseY: Int = -1

    // 坐标标签的样式常量
    private val labelFont = Font(Font.MONOSPACED, Font.BOLD, 12)
    private val labelPadH = 6
    private val labelPadV = 3
    private val labelOffsetX = 14  // 标签相对光标的偏移
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

        // 1:1 绘制图片，不做任何缩放
        g2.drawImage(image, 0, 0, null)

        // 绘制坐标标签
        if (mouseX >= 0 && mouseY >= 0) {
            val label = "(${mouseX}, ${mouseY})"
            g2.font = labelFont
            val fm = g2.fontMetrics
            val textW = fm.stringWidth(label)
            val textH = fm.ascent

            // 标签框位置：默认在光标右下方，靠近右/下边缘时自动翻转
            val boxW = textW + labelPadH * 2
            val boxH = textH + labelPadV * 2
            var bx = mouseX + labelOffsetX
            var by = mouseY + labelOffsetY
            if (bx + boxW > width)  bx = mouseX - labelOffsetX - boxW
            if (by + boxH > height) by = mouseY - labelOffsetY - boxH

            // 半透明深色背景
            g2.color = Color(0, 0, 0, 180)
            g2.fillRoundRect(bx, by, boxW, boxH, 6, 6)

            // 亮色边框
            g2.color = Color(100, 200, 255, 200)
            g2.drawRoundRect(bx, by, boxW, boxH, 6, 6)

            // 坐标文字
            g2.color = Color(220, 240, 255)
            g2.drawString(label, bx + labelPadH, by + labelPadV + textH - fm.descent)

            // 十字准星（细线，半透明）
            g2.color = Color(100, 200, 255, 120)
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)
            g2.drawLine(mouseX, 0, mouseX, height)
            g2.drawLine(0, mouseY, width, mouseY)
        }
    }
}
