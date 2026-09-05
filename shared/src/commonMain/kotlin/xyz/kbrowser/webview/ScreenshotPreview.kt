package xyz.kbrowser.webview

/**
 * Debug utility: shows screenshot bytes in a separate window.
 * The window is sized to the image resolution; moving the mouse shows the CSS coordinates next
 * to the cursor, so they can be read off and entered into the coordinate-click test box to
 * verify alignment precision.
 *
 * Has a real implementation on JVM only; no-op on other platforms.
 */
expect fun showScreenshotPreview(bytes: ByteArray)
