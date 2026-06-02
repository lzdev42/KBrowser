package xyz.kbrowser.webview

/**
 * 将 AxTreeData 转换为 KBrowser YAML Snapshot 格式。
 *
 * 树构建策略：
 *   - 有 childIds（CDP 路径）→ 严格按 childIds 构建，不可见节点透明穿透，孤儿直接挂根
 *   - 无 childIds（JS 注入路径）→ 坐标包含关系构建
 * 两套逻辑完全独立，不混用。
 *
 * @param clean 是否清洗噪音节点（默认 false）
 *   清洗规则：
 *   1. 无文本 + 无交互属性 + role 无语义 → 删，子节点上提
 *   2. 纯装饰节点（image、i、空 span/wrapper）且父节点已包含其信息 → 删
 *   3. 视口外节点（center 超出 viewport）→ 删
 */
fun AxTreeData.toYamlSnapshot(clean: Boolean = false): String {
    if (nodes.isEmpty()) return ""

    val noiseTags = setOf("script", "style", "link", "meta", "noscript", "head", "::before", "::after")

    val visibleNodes = nodes.filter { node ->
        val tag = node.tagName.lowercase()
        if (tag in noiseTags) return@filter false
        if (node.id == "__kb_overlay__") return@filter false
        val style = node.attributes["style"] ?: ""
        if (style.contains("outline:") && style.contains("rgba") &&
            style.contains("position: absolute") && style.contains("box-sizing: border-box")
        ) return@filter false
        true
    }

    if (visibleNodes.isEmpty()) return ""

    val visibleIndex = visibleNodes.associateBy { it.refid }
    val parentMap = mutableMapOf<String, String>()
    val childrenMap = mutableMapOf<String, MutableList<AxNode>>()

    val hasCdpHierarchy = visibleNodes.any { it.childIds.isNotEmpty() }
    if (hasCdpHierarchy) {
        buildCdpTree(nodes, visibleIndex, parentMap, childrenMap)
    } else {
        buildCoordTree(visibleNodes, parentMap, childrenMap)
    }

    // clean 模式：对节点树做一次清洗，直接修改 childrenMap
    if (clean) {
        cleanTree(visibleNodes, visibleIndex, innerWidth, innerHeight, parentMap, childrenMap)
    }

    val roots = visibleNodes
        .filter { it.refid !in parentMap }
        .let { list ->
            if (clean) list.filter { !shouldCleanNode(it, innerWidth, innerHeight, childrenMap) }
            else list
        }
        .sortedWith(compareBy({ it.y }, { it.x }))

    val sb = StringBuilder()
    sb.append("# KBrowser Snapshot\n")
    sb.append("# url: ").append(url).append("\n")
    sb.append("# viewport: ").append(innerWidth).append("x").append(innerHeight).append("\n")
    sb.append("#\n")
    sb.append("# Format: - role \"text\" @refid [center:x,y] [selector:...] [occludedBy:@refid]\n")
    sb.append("# @refid  → use with page.click(refid) or page.locator(selector)\n")
    sb.append("# occludedBy → coordinate click blocked; dismiss that element first\n")
    sb.append("#\n")

    for (root in roots) {
        serializeNode(root, 0, sb, childrenMap, clean)
    }

    return sb.toString().trim()
}

// ────────────────────────────────────────────────
// 清洗
// ────────────────────────────────────────────────

/**
 * 判断节点是否应该被清洗掉。
 *
 * 规则1：视口外（center 超出 viewport）
 * 规则2：纯装饰 tag（image、i）且父节点文本已包含其信息（无独立文本/交互）
 * 规则3：无语义 role + 无文本 + 无任何交互属性
 */
private fun shouldCleanNode(
    node: AxNode,
    viewportWidth: Int,
    viewportHeight: Int,
    childrenMap: Map<String, MutableList<AxNode>>,
): Boolean {
    val tag = node.tagName.lowercase()
    val role = node.role.lowercase()

    // 规则1：视口外
    if (node.centerX < 0 || node.centerY < 0) return true
    if (viewportWidth > 0 && node.centerX > viewportWidth) return true
    if (viewportHeight > 0 && node.centerY > viewportHeight) return true

    // 规则2：纯装饰 tag，无文本无交互
    if (tag in setOf("image", "img", "i", "svg") && !hasInteraction(node)) {
        return true
    }

    // 规则3：role 无语义 + 无文本 + 无交互
    val hasSemanticRole = role.isNotBlank() && role !in NON_SEMANTIC_ROLES
    val hasTagSemantics = tag in setOf("button", "a", "input", "textarea", "select",
        "details", "summary", "label", "form")
    if (!hasSemanticRole && !hasTagSemantics && !hasInteraction(node) && resolveTextForClean(node, childrenMap).isBlank()) {
        return true
    }

    return false
}

/** 纯结构/格式角色，无文本无交互时视为噪音 */
private val NON_SEMANTIC_ROLES = setOf(
    "generic", "none", "presentation",
    "div", "span", "section", "article", "aside", "main", "nav", "header", "footer",
    "list", "listitem", "paragraph", "descriptionlist", "definition", "term",
    "emphasis", "strong", "group"
)

/**
 * 节点是否有交互属性：href、placeholder、checked、aria-checked、role 明确可交互
 */
private fun hasInteraction(node: AxNode): Boolean {
    if (node.attributes["href"]?.let { it.isNotBlank() && !it.startsWith("javascript") } == true) return true
    if (node.attributes["placeholder"] != null) return true
    if (node.attributes["checked"] != null) return true
    if (node.attributes["aria-checked"] != null) return true
    if (node.attributes["aria-selected"] != null) return true
    if (node.attributes["aria-expanded"] != null) return true
    val interactiveRoles = setOf("button", "link", "checkbox", "radio", "textbox", "combobox",
        "listbox", "menuitem", "menuitemcheckbox", "menuitemradio", "option",
        "switch", "tab", "treeitem", "spinbutton", "slider", "searchbox")
    if (node.role.lowercase() in interactiveRoles) return true
    return false
}

private fun resolveTextForClean(node: AxNode, childrenMap: Map<String, MutableList<AxNode>>): String {
    if (node.text.isNotBlank()) return node.text
    return childrenMap[node.refid]
        ?.filter { it.tagName == "#text" && it.text.isNotBlank() }
        ?.joinToString(" ") { it.text.trim() }
        ?.trim()
        ?: ""
}

/**
 * 遍历整棵树，把需要清洗的节点从 childrenMap 中移除，
 * 其子节点上提到父节点（透明穿透）。
 */
private fun cleanTree(
    allVisible: List<AxNode>,
    visibleIndex: Map<String, AxNode>,
    viewportWidth: Int,
    viewportHeight: Int,
    parentMap: MutableMap<String, String>,
    childrenMap: MutableMap<String, MutableList<AxNode>>,
) {
    // 收集所有需要删除的节点 refid
    val toRemove = allVisible
        .filter { shouldCleanNode(it, viewportWidth, viewportHeight, childrenMap) }
        .map { it.refid }
        .toSet()

    if (toRemove.isEmpty()) return

    // Pass 1: 对每个保留的节点，递归向上找到最近的非删除祖先，重建 parent-child 映射
    val newParentMap = mutableMapOf<String, String>()
    val newChildrenMap = mutableMapOf<String, MutableList<AxNode>>()

    for (node in allVisible) {
        if (node.refid in toRemove) continue

        // 沿 parentMap 递归向上，跳过所有 toRemove 节点，找到最近的保留祖先
        var ancestorRefid = parentMap[node.refid]
        while (ancestorRefid != null && ancestorRefid in toRemove) {
            ancestorRefid = parentMap[ancestorRefid]
        }

        if (ancestorRefid != null) {
            newParentMap[node.refid] = ancestorRefid
            newChildrenMap.getOrPut(ancestorRefid) { mutableListOf() }.add(node)
        }
        // ancestorRefid == null → 该节点成为 root（不加入 parentMap）
    }

    // Pass 2: 移除冗余文本子节点
    // 条件：非交互叶子节点 + role 为非语义 + 文本是父节点文本的子串
    val redundant = mutableSetOf<String>()
    for ((pRefid, children) in newChildrenMap) {
        val parentNode = visibleIndex[pRefid] ?: continue
        val parentText = resolveTextForClean(parentNode, newChildrenMap)
        if (parentText.isBlank()) continue
        for (child in children) {
            val childRole = child.role.lowercase()
            if (childRole !in NON_SEMANTIC_ROLES) continue
            if (hasInteraction(child)) continue
            if (!newChildrenMap[child.refid].isNullOrEmpty()) continue  // 有子节点，不是叶子
            val childText = resolveTextForClean(child, newChildrenMap)
            if (childText.isBlank()) continue
            if (parentText.contains(childText)) {
                redundant.add(child.refid)
            }
        }
    }
    if (redundant.isNotEmpty()) {
        for ((pRefid, children) in newChildrenMap.toMap()) {
            val filtered = children.filterNot { it.refid in redundant }
            if (filtered.isEmpty()) newChildrenMap.remove(pRefid)
            else newChildrenMap[pRefid] = filtered.toMutableList()
        }
        for (refid in redundant) {
            newParentMap.remove(refid)
        }
    }

    parentMap.clear()
    parentMap.putAll(newParentMap)
    childrenMap.clear()
    childrenMap.putAll(newChildrenMap)
}

// ────────────────────────────────────────────────
// CDP 树构建
// ────────────────────────────────────────────────

private fun buildCdpTree(
    allNodes: List<AxNode>,
    visibleIndex: Map<String, AxNode>,
    parentMap: MutableMap<String, String>,
    childrenMap: MutableMap<String, MutableList<AxNode>>,
) {
    val allIndex = allNodes.associateBy { it.refid }

    fun attachChildren(visibleParentRefid: String, childIds: List<String>) {
        for (childId in childIds) {
            val child = allIndex[childId] ?: continue
            if (visibleIndex.containsKey(childId)) {
                if (childId !in parentMap) {
                    parentMap[childId] = visibleParentRefid
                    childrenMap.getOrPut(visibleParentRefid) { mutableListOf() }.add(visibleIndex[childId]!!)
                }
            } else {
                attachChildren(visibleParentRefid, child.childIds)
            }
        }
    }

    for (node in visibleIndex.values) {
        if (node.childIds.isNotEmpty()) {
            attachChildren(node.refid, node.childIds)
        }
    }
}

// ────────────────────────────────────────────────
// 坐标包含树构建（JS 注入路径专用）
// ────────────────────────────────────────────────

private fun buildCoordTree(
    visibleNodes: List<AxNode>,
    parentMap: MutableMap<String, String>,
    childrenMap: MutableMap<String, MutableList<AxNode>>,
) {
    val nodeArea = { n: AxNode -> n.width.toLong() * n.height.toLong() }

    for (node in visibleNodes) {
        var bestParent: AxNode? = null
        var bestArea = Long.MAX_VALUE

        for (candidate in visibleNodes) {
            if (candidate.refid == node.refid) continue
            val area = nodeArea(candidate)
            if (area <= nodeArea(node)) continue
            val contains = node.centerX >= candidate.x &&
                    node.centerX <= candidate.x + candidate.width &&
                    node.centerY >= candidate.y &&
                    node.centerY <= candidate.y + candidate.height
            if (contains && area < bestArea) {
                bestArea = area
                bestParent = candidate
            }
        }

        if (bestParent != null) {
            parentMap[node.refid] = bestParent.refid
            childrenMap.getOrPut(bestParent.refid) { mutableListOf() }.add(node)
        }
    }
}

// ────────────────────────────────────────────────
// 序列化
// ────────────────────────────────────────────────

private fun serializeNode(
    node: AxNode,
    depth: Int,
    sb: StringBuilder,
    childrenMap: Map<String, MutableList<AxNode>>,
    clean: Boolean,
) {
    if (node.tagName == "#text") return

    val indent = "  ".repeat(depth)

    val role = when {
        node.role.isNotBlank() &&
                node.role.lowercase() != "generic" &&
                node.role.lowercase() != "none" -> node.role.lowercase()
        node.tagName.isNotBlank() && node.tagName != "#document" -> node.tagName.lowercase()
        else -> "generic"
    }

    val text = resolveTextForClean(node, childrenMap)
    val placeholder = node.attributes["placeholder"]
    val href = node.attributes["href"]
    val checked = when (node.role.lowercase()) {
        "checkbox", "radio" -> node.attributes["checked"] ?: node.attributes["aria-checked"]
        else -> null
    }
    val isActive = node.attributes["aria-selected"] == "true" ||
            node.className.contains("active") ||
            node.attributes["aria-current"] != null

    sb.append(indent).append("- ").append(role)

    when {
        text.isNotBlank() -> {
            val display = if (text.length > 80) text.take(80) + "…" else text
            sb.append(" \"").append(display).append("\"")
        }
        placeholder != null -> sb.append(" [placeholder:").append(placeholder).append("]")
    }

    if (node.refid.isNotBlank()) sb.append(" @").append(node.refid)

    if (node.centerX != 0 || node.centerY != 0) {
        sb.append(" [center:").append(node.centerX).append(",").append(node.centerY).append("]")
    }

    if (node.selector.isNotBlank()) {
        val s = node.selector
        val display = if (s.length > 60) s.take(60) + "…" else s
        sb.append(" [selector:").append(display).append("]")
    }

    if (node.occludedBy != null) {
        sb.append(" [occludedBy:@").append(node.occludedBy).append("]")
    }

    if (isActive) sb.append(" [active]")
    when (checked) {
        "true", "mixed" -> sb.append(" [checked]")
        "false" -> sb.append(" [unchecked]")
    }

    if (href != null && !href.startsWith("javascript")) {
        val display = if (href.length > 60) href.take(60) + "…" else href
        sb.append(" [href:").append(display).append("]")
    }

    if (node.className.isNotBlank() && text.isBlank() && placeholder == null) {
        sb.append(" [class:").append(node.className.take(40)).append("]")
    }

    sb.append("\n")

    val children = childrenMap[node.refid]
        ?.filter { it.tagName != "#text" }
        ?.sortedWith(compareBy({ it.y }, { it.x }))
        ?: return

    for (child in children) {
        serializeNode(child, depth + 1, sb, childrenMap, clean)
    }
}