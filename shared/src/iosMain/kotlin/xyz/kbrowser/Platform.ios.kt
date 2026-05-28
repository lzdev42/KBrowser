@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package xyz.kbrowser

import platform.UIKit.UIDevice
import androidx.compose.ui.graphics.toComposeImageBitmap

class IOSPlatform : Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun currentTimeMillis(): Long = platform.posix.time(null) * 1000L

actual fun makeImageBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap {
    return org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}