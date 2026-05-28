package xyz.kbrowser.webview

/**
 * 清洗 AXTree 节点。
 *
 * 策略（针对 CDP Accessibility.getFullAXTree 的输出）：
 * - 删除：不可见节点（isVisible=false）
 * - 删除：纯噪音标签（script/style/link/meta/noscript/head）
 * - 删除：role=generic 且 text 为空 且 无有意义 attributes 的纯容器节点
 * - 保留：所有有 text 的节点
 * - 保留：所有有语义 role 的节点（非 generic/none/RootWebArea 的）
 * - 保留：交互元素（input/button/select/textarea/a/option/label）
 * - 保留：有 aria-label / placeholder / name / role attribute 的节点
 * - 保留：有 id 或有意义 className 的节点（可能是交互目标）
 */
fun AxTreeData.getCleanedAxTree(): AxTreeData {
    // 纯噪音标签，无论如何都删
    val noiseTags = setOf("script", "style", "link", "meta", "noscript", "head")

    // 有语义的 role（保留）

    // 有语义的 role（保留）
    val semanticRoles = setOf(
        "button", "link", "textbox", "combobox", "listbox", "option",
        "checkbox", "radio", "menuitem", "menuitemcheckbox", "menuitemradio",
        "tab", "tabpanel", "dialog", "alertdialog", "alert",
        "heading", "img", "figure", "table", "row", "cell", "columnheader", "rowheader",
        "list", "listitem", "term", "definition",
        "navigation", "main", "banner", "contentinfo", "complementary", "region", "search",
        "form", "group", "radiogroup",
        "progressbar", "slider", "spinbutton", "scrollbar",
        "tooltip", "status", "log", "marquee", "timer",
        "treeitem", "tree", "grid", "gridcell", "treegrid",
        "separator", "toolbar", "menubar", "menu",
        "article", "section", "aside",
        "paragraph", "blockquote", "code", "strong", "emphasis",
        "statictext", "labeltext", "descriptionlist",
        "iframe", "rootwebarea"
    )

    // 交互标签（无论 role 是什么都保留）
    val interactiveTags = setOf("input", "button", "select", "textarea", "a", "option", "label", "summary")

    val cleanedNodes = nodes.filter { node ->
        // 1. 不可见的删掉
        if (!node.isVisible) return@filter false

        val tag = node.tagName.lowercase()

        // 2. 纯噪音标签删掉
        if (tag in noiseTags) return@filter false

        // 3. StaticText (#text) 删掉 — 是父节点（link/button等）的文本子节点，信息冗余
        //    父节点的 text 字段已经包含了这个文本内容
        if (tag == "#text") return@filter false

        // 3. 交互标签无条件保留
        if (tag in interactiveTags) return@filter true

        // 4. 有 text 的保留
        if (node.text.isNotBlank()) return@filter true

        // 5. 有语义 role 的保留（role 转小写比较）
        val roleLower = node.role.lowercase()
        if (roleLower in semanticRoles && roleLower != "generic" && roleLower != "none") return@filter true

        // 6. generic/none role 但有有意义 attributes 的保留
        if (node.attributes.containsKey("aria-label") ||
            node.attributes.containsKey("aria-labelledby") ||
            node.attributes.containsKey("placeholder") ||
            node.attributes.containsKey("name") ||
            node.attributes.containsKey("data-testid") ||
            node.attributes.containsKey("role")) {
            return@filter true
        }

        // 7. 其余 generic/none 空节点删掉（纯布局容器）
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
