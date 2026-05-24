package xyz.kbrowser.webview

fun AxTreeData.getCleanedAxTree(): AxTreeData {
    val cleanedNodes = nodes.filter { node ->
        if (!node.isVisible) return@filter false

        val tag = node.tagName.lowercase()

        if (tag in setOf("script", "style", "link", "meta", "noscript", "head", "html", "iframe")) return@filter false

        if (tag in setOf("input", "button", "select", "textarea", "a", "option")) return@filter true

        if (node.text.isNotBlank()) return@filter true

        if (node.attributes.containsKey("role") || 
            node.attributes.containsKey("aria-label") || 
            node.attributes.containsKey("name") || 
            node.attributes.containsKey("placeholder")) {
            return@filter true
        }

        false
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
