package xyz.kbrowser.webview

/**
 * 清洗 AXTree 节点。
 *
 * 策略：只删技术噪音，保留所有可见的语义节点，包括 #text 节点。
 * 宁可多 token，也不能让 AI 找不到目标元素。
 * #text 节点保留，AI 可以通过坐标关系推断父节点语义（如图标按钮的文字说明）。
 *
 * 删除：
 * - 不可见节点（isVisible=false）
 * - 纯技术标签：script / style / link / meta / noscript / head / ::before / ::after
 * - 调试用 overlay 节点（id=__kb_overlay__ 及其画框子节点）
 *
 * 保留其他所有节点，包括 #text。
 */
fun AxTreeData.getCleanedAxTree(): AxTreeData {
    val noiseTags = setOf("script", "style", "link", "meta", "noscript", "head", "::before", "::after")

    val cleanedNodes = nodes.filter { node ->
        // 1. 不可见的删掉
        if (!node.isVisible) return@filter false

        val tag = node.tagName.lowercase()

        // 2. 纯技术标签删掉
        if (tag in noiseTags) return@filter false

        // 3. 调试 overlay 节点删掉（id=__kb_overlay__ 及其画框子节点）
        if (node.id == "__kb_overlay__") return@filter false
        val style = node.attributes["style"] ?: ""
        if (style.contains("outline:") && style.contains("rgba") &&
            style.contains("position: absolute") && style.contains("box-sizing: border-box")) {
            return@filter false
        }

        true
    }

    return this.copy(
        nodes = cleanedNodes,
        totalElements = cleanedNodes.size,
        visibleElements = cleanedNodes.count { it.isVisible },
        hiddenElements = cleanedNodes.count { !it.isVisible }
    )
}

fun AxTreeData.getViewportAxTree(): AxTreeData {
    val minX = scrollX
    val maxX = scrollX + innerWidth
    val minY = scrollY
    val maxY = scrollY + innerHeight

    val croppedNodes = nodes.filter { node ->
        val overlapX = (node.x < maxX) && (node.x + node.width > minX)
        val overlapY = (node.y < maxY) && (node.y + node.height > minY)
        overlapX && overlapY
    }

    return this.copy(
        nodes = croppedNodes,
        totalElements = croppedNodes.size,
        visibleElements = croppedNodes.count { it.isVisible },
        hiddenElements = croppedNodes.count { !it.isVisible }
    )
}
