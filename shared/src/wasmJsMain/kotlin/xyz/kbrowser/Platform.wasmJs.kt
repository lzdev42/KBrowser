package xyz.kbrowser

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

class WasmJsPlatform : Platform {
    override val name: String = "WasmJS Browser"
}

actual fun getPlatform(): Platform = WasmJsPlatform()

private fun jsNow(): Double = js("Date.now()")

actual fun currentTimeMillis(): Long = jsNow().toLong()

actual fun makeImageBitmap(bytes: ByteArray): ImageBitmap {
    return Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

actual fun getDefaultStorageDir(): String? = null
