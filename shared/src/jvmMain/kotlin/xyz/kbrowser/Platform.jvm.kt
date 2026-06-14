package xyz.kbrowser

import androidx.compose.ui.graphics.toComposeImageBitmap

class JVMPlatform : Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun makeImageBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap {
    return org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

actual fun getDefaultStorageDir(): String? {
    return System.getProperty("user.home") + "/.browserpilot/jcef_cache"
}