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
     * refid of the element occluding this node's center point.
     * Non-null means a coordinate click would hit the occluder instead of this node.
     * Handle the occluder first (close the popup/ad), or bypass coordinates with
     * locator(selector).fill().
     */
    val occludedBy: String? = null,
    /**
     * CDP AX node ID (the nodeId field from Accessibility.getFullAXTree).
     * Used to build the real DOM hierarchy via childIds instead of rebuilding it from
     * coordinate containment.
     * On the JS injection path this equals the refid.
     */
    val nodeId: String = "",
    /**
     * CDP AX child node IDs (the childIds field from Accessibility.getFullAXTree).
     * References the nodeId of other AX nodes, used to build the real DOM hierarchy.
     * Absolutely positioned elements (dropdowns, popups, etc.) can lie outside their DOM
     * parent's visual bounds, so coordinate containment assigns the wrong parent; childIds
     * provide the correct hierarchy.
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
