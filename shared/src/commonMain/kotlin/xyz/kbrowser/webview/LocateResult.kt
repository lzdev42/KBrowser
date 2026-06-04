package xyz.kbrowser.webview

import kotlinx.serialization.Serializable

@Serializable
data class LocateResult(
    val centerX: Int = 0,
    val centerY: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val tagName: String = "",
    val role: String = "",
    val text: String = "",
    val isVisible: Boolean = true,
    val attributes: Map<String, String> = emptyMap(),
    val selector: String = ""
)

@Serializable
data class Rect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)
