package xyz.kbrowser.webview

/**
 * Cleans the AX tree: keeps only nodes inside the viewport.
 *
 * Viewport range: centerX ∈ [scrollX, scrollX + innerWidth], centerY ∈ [scrollY, scrollY + innerHeight].
 * Uses the same viewport filtering as [toYamlSnapshot] with [SnapshotMode.VIEWPORT].
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
