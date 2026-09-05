package xyz.kbrowser

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/**
 * Cross-platform timestamp in milliseconds, replacing java.time / kotlin.system, which are
 * unavailable in commonMain.
 */
expect fun currentTimeMillis(): Long

expect fun getDefaultStorageDir(): String?

expect fun makeImageBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap