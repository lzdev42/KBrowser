package xyz.kbrowser

import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun makeImageBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap {
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    return bitmap.asImageBitmap()
}

actual fun getDefaultStorageDir(): String? = null