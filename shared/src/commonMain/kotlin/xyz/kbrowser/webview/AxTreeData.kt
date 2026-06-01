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
    val occludedBy: String? = null,
    /**
     * CDP AX 节点 ID（来自 Accessibility.getFullAXTree 的 nodeId 字段）。
     * 用于通过 childIds 构建真实的 DOM 层级关系，替代坐标包含关系重建。
     * JS 注入路径下为 refid 本身。
     */
    val nodeId: String = "",
    /**
     * CDP AX 子节点 ID 列表（来自 Accessibility.getFullAXTree 的 childIds 字段）。
     * 引用其他 AX 节点的 nodeId，用于构建真实的 DOM 层级关系。
     * 绝对定位元素（下拉菜单、弹窗等）的视觉坐标不在 DOM 父节点内，
     * 坐标包含关系会错误分配父节点，childIds 提供了正确的层级信息。
     */
    val childIds: List<String> = emptyList()
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
