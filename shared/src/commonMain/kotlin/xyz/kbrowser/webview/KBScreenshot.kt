package xyz.kbrowser.webview

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 网页截图。截图像素与 CSS 坐标 1:1 对齐（已处理 DPR downscale）。
 *
 * @property imageData PNG 编码的图片字节数组
 * @property width CSS 像素宽
 * @property height CSS 像素高
 */
@OptIn(ExperimentalEncodingApi::class)
data class KBScreenshot(
    val imageData: ByteArray,
    val width: Int,
    val height: Int
) {
    val base64: String by lazy { Base64.encode(imageData) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KBScreenshot) return false
        return width == other.width && height == other.height && imageData.contentEquals(other.imageData)
    }

    override fun hashCode(): Int {
        var result = imageData.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

/**
 * 锁定/解锁用户交互（自动化操作期间）。
 * locked=true 时在浏览器组件上覆盖 AWT 拦截层，阻止用户鼠标/键盘输入，自动化（CDP）不受影响。
 * 仅 JVM 平台有效，Android/iOS 为空操作。
 */
internal expect fun setInteractionLockedNative(webView: KBWebView, locked: Boolean)

/**
 * 更新鼠标轨迹位置（在锁定状态下显示自动化操作的光标动画）。
 * 坐标为视口坐标（CSS 像素）。仅 JVM 平台有效。
 * JVM 端坐标自动化方法内部会自动调用此函数。
 */
internal expect fun updateMouseTrailNative(webView: KBWebView, viewportX: Int, viewportY: Int)
