package xyz.kbrowser.webview

import kotlinx.serialization.Serializable

@Serializable
data class AxNode(
    val refid: String = "",
    val tagName: String = "",
    val role: String = "",
    val id: String = "",
    val className: String = "",
    val text: String = "",
    val isVisible: Boolean = true,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val centerX: Int = 0,
    val centerY: Int = 0,
    val childCount: Int = 0,
    val attributes: Map<String, String> = emptyMap(),
    val iframeSrc: String? = null,
    val selector: String = "",
    /**
     * 遮挡该节点中心点的元素的 refid。
     * 非 null 表示坐标点击会打到遮挡物而非该节点本身。
     * AI 应先处理遮挡物（关闭弹窗/广告），或改用 locator(selector).fill() 绕过坐标。
     */
    val occludedBy: String? = null
)

@Serializable
data class AxTreeData(
    val url: String = "",
    val innerWidth: Int = 0,
    val innerHeight: Int = 0,
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val documentWidth: Int = 0,
    val documentHeight: Int = 0,
    val devicePixelRatio: Double = 1.0,
    val totalElements: Int = 0,
    val visibleElements: Int = 0,
    val hiddenElements: Int = 0,
    val iframeCount: Int = 0,
    val nodes: List<AxNode> = emptyList()
)
