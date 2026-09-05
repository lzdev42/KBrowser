package xyz.kbrowser.webview

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A screenshot of the page. Screenshot pixels align 1:1 with CSS coordinates (DPR downscaling
 * already applied).
 *
 * @property imageData PNG-encoded image bytes
 * @property width width in CSS pixels
 * @property height height in CSS pixels
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
 * Locks/unlocks user interaction (during automation).
 * When locked=true, an AWT interception layer covers the browser component and blocks user
 * mouse/keyboard input; automation (CDP) is unaffected.
 * JVM only; no-op on Android/iOS.
 */
internal expect fun setInteractionLockedNative(webView: KBWebView, locked: Boolean)

/**
 * Updates the mouse trail position (shows the automation cursor animation while interaction is
 * locked).
 * Coordinates are viewport coordinates (CSS pixels). JVM only.
 * JVM coordinate-based automation methods call this automatically.
 */
internal expect fun updateMouseTrailNative(webView: KBWebView, viewportX: Int, viewportY: Int)
