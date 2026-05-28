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
    /**
     * CDP backendNodeId，仅 JVM 平台填充，用于 DOM 直接操作（绕过坐标 hit-test）。
     * Android/iOS 为 null，此时回退到坐标点击。
     */
    val backendNodeId: Int? = null
)

@Serializable
data class Rect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)
