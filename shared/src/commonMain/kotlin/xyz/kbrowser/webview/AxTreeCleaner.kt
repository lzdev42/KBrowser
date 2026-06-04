package xyz.kbrowser.webview

/**
 * 清洗 AXTree：只保留视口内的节点。
 *
 * 视口范围：centerX ∈ [scrollX, scrollX + innerWidth], centerY ∈ [scrollY, scrollY + innerHeight]
 * 和 toYamlSnapshot(clean = true) 使用完全相同的逻辑。
 */
fun AxTreeData.getCleanedAxTree(): AxTreeData {
    val left = scrollX
    val right = scrollX + innerWidth
    val top = scrollY
    val bottom = scrollY + innerHeight

    val filtered = nodes.filter { node ->
        node.centerX in left..right && node.centerY in top..bottom
    }

    return this.copy(
        nodes = filtered,
        totalElements = filtered.size,
        visibleElements = filtered.count { it.isVisible },
        hiddenElements = filtered.count { !it.isVisible }
    )
}
