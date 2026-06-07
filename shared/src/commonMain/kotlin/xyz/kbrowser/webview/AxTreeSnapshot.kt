package xyz.kbrowser.webview

/**
 * 将 AxTreeData 转换为 YAML 格式。
 *
 * [clean] = false → 纯格式转换，不做任何过滤，所有节点、所有字段原样输出。
 * [clean] = true  → 紧凑输出，供 AI agent 消费：
 *   1. 视口过滤：只保留 centerX/centerY 落在视口范围内的节点
 *   2. 删除 StaticText（#text）节点 — 父节点 text 字段已包含
 *   3. 删除 ::before / ::after 伪元素
 *   4. 删除不可见且不可交互的节点
 *   5. 删除空的 generic 容器（role=generic/none/presentation，无文本，不可交互），children 提升到父级
 *   6. 字段精简：省略空值/null/默认字段，去掉 AI 不需要的内部字段
 *   7. attributes 只保留交互相关属性（href/placeholder/type/aria-* 等）
 */
fun AxTreeData.toYamlSnapshot(clean: Boolean = false): String {
    val nodes = this.nodes
    if (nodes.isEmpty()) return ""

    val nodeIndex = nodes.associateBy { it.refid }
    val parentMap = mutableMapOf<String, String>()
    val childrenMap = mutableMapOf<String, MutableList<AxNode>>()

    val hasCdpHierarchy = nodes.any { it.childIds.isNotEmpty() }
    if (hasCdpHierarchy) {
        buildCdpTree(nodes, nodeIndex, parentMap, childrenMap)
    } else {
        buildCoordTree(nodes, parentMap, childrenMap)
    }

    val roots = nodes
        .filter { it.refid !in parentMap }
        .sortedWith(compareBy({ it.y }, { it.x }))

    val sb = StringBuilder()

    // 元数据
    sb.append("url: ").append(yamlStr(url)).append('\n')
    sb.append("innerWidth: ").append(innerWidth).append('\n')
    sb.append("innerHeight: ").append(innerHeight).append('\n')
    sb.append("scrollX: ").append(scrollX).append('\n')
    sb.append("scrollY: ").append(scrollY).append('\n')
    if (!clean) {
        sb.append("documentWidth: ").append(documentWidth).append('\n')
        sb.append("documentHeight: ").append(documentHeight).append('\n')
        sb.append("devicePixelRatio: ").append(devicePixelRatio).append('\n')
        sb.append("totalElements: ").append(totalElements).append('\n')
        sb.append("visibleElements: ").append(visibleElements).append('\n')
        sb.append("hiddenElements: ").append(hiddenElements).append('\n')
        sb.append("iframeCount: ").append(iframeCount).append('\n')
    }
    sb.append("nodes:\n")

    val left = scrollX
    val right = scrollX + innerWidth
    val top = scrollY
    val bottom = scrollY + innerHeight

    val compactCache = mutableMapOf<String, Int>()

    for (root in roots) {
        if (clean) {
            serializeNodeCompact(root, 1, sb, childrenMap, left, right, top, bottom, compactCache)
        } else {
            serializeNode(root, 1, sb, childrenMap)
        }
    }

    return sb.toString().trimEnd()
}

// ────────────────────────────────────────────────
// YAML 辅助
// ────────────────────────────────────────────────

private fun yamlStr(s: String): String {
    if (s.isEmpty()) return "\"\""
    val escaped = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "\"$escaped\""
}

private fun yamlStrOrNull(s: String?): String {
    return if (s == null) "null" else yamlStr(s)
}

// ────────────────────────────────────────────────
// CDP 树构建
// ────────────────────────────────────────────────

private fun buildCdpTree(
    allNodes: List<AxNode>,
    nodeIndex: Map<String, AxNode>,
    parentMap: MutableMap<String, String>,
    childrenMap: MutableMap<String, MutableList<AxNode>>,
) {
    for (node in allNodes) {
        for (childId in node.childIds) {
            if (nodeIndex.containsKey(childId) && childId !in parentMap) {
                parentMap[childId] = node.refid
                childrenMap.getOrPut(node.refid) { mutableListOf() }.add(nodeIndex[childId]!!)
            }
        }
    }
}

// ────────────────────────────────────────────────
// 坐标包含树构建（JS 注入路径专用）
// ────────────────────────────────────────────────

private fun buildCoordTree(
    allNodes: List<AxNode>,
    parentMap: MutableMap<String, String>,
    childrenMap: MutableMap<String, MutableList<AxNode>>,
) {
    val nodeArea = { n: AxNode -> n.width.toLong() * n.height.toLong() }

    for (node in allNodes) {
        var bestParent: AxNode? = null
        var bestArea = Long.MAX_VALUE

        for (candidate in allNodes) {
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

// ════════════════════════════════════════════════
// clean = false：完整序列化（所有字段，无遗漏）
// ════════════════════════════════════════════════

private fun serializeNode(
    node: AxNode,
    depth: Int,
    sb: StringBuilder,
    childrenMap: Map<String, MutableList<AxNode>>,
) {
    val listIndent = "  ".repeat(depth)
    val fieldIndent = "  ".repeat(depth + 1)

    sb.append(listIndent).append("- refid: ").append(yamlStr(node.refid)).append('\n')
    sb.append(fieldIndent).append("tagName: ").append(yamlStr(node.tagName)).append('\n')
    sb.append(fieldIndent).append("role: ").append(yamlStr(node.role)).append('\n')
    sb.append(fieldIndent).append("id: ").append(yamlStr(node.id)).append('\n')
    sb.append(fieldIndent).append("className: ").append(yamlStr(node.className)).append('\n')
    sb.append(fieldIndent).append("text: ").append(yamlStr(node.text)).append('\n')
    sb.append(fieldIndent).append("isVisible: ").append(node.isVisible).append('\n')
    sb.append(fieldIndent).append("x: ").append(node.x).append('\n')
    sb.append(fieldIndent).append("y: ").append(node.y).append('\n')
    sb.append(fieldIndent).append("width: ").append(node.width).append('\n')
    sb.append(fieldIndent).append("height: ").append(node.height).append('\n')
    sb.append(fieldIndent).append("centerX: ").append(node.centerX).append('\n')
    sb.append(fieldIndent).append("centerY: ").append(node.centerY).append('\n')
    sb.append(fieldIndent).append("childCount: ").append(node.childCount).append('\n')

    if (node.attributes.isEmpty()) {
        sb.append(fieldIndent).append("attributes: {}\n")
    } else {
        sb.append(fieldIndent).append("attributes:\n")
        val attrIndent = fieldIndent + "  "
        for ((key, value) in node.attributes) {
            sb.append(attrIndent).append(yamlStr(key)).append(": ").append(yamlStr(value)).append('\n')
        }
    }

    sb.append(fieldIndent).append("iframeSrc: ").append(yamlStrOrNull(node.iframeSrc)).append('\n')
    sb.append(fieldIndent).append("selector: ").append(yamlStr(node.selector)).append('\n')
    sb.append(fieldIndent).append("occludedBy: ").append(yamlStrOrNull(node.occludedBy)).append('\n')
    sb.append(fieldIndent).append("nodeId: ").append(yamlStr(node.nodeId)).append('\n')

    if (node.childIds.isEmpty()) {
        sb.append(fieldIndent).append("childIds: []\n")
    } else {
        sb.append(fieldIndent).append("childIds: [")
            .append(node.childIds.joinToString(", ") { yamlStr(it) })
            .append("]\n")
    }

    val children = childrenMap[node.refid]
        ?.sortedWith(compareBy({ it.y }, { it.x }))

    if (!children.isNullOrEmpty()) {
        sb.append(fieldIndent).append("children:\n")
        for (child in children) {
            serializeNode(child, depth + 1, sb, childrenMap)
        }
    }
}

// ════════════════════════════════════════════════
// clean = true：紧凑序列化（AI agent 专用）
// ════════════════════════════════════════════════

/**
 * 紧凑序列化一个节点。
 * 应用节点过滤规则，跳过噪音节点，空容器的 children 提升到父级。
 * 只输出有值的字段，attributes 只保留交互相关属性。
 */
private fun serializeNodeCompact(
    node: AxNode,
    depth: Int,
    sb: StringBuilder,
    childrenMap: Map<String, List<AxNode>>,
    left: Int, right: Int, top: Int, bottom: Int,
    compactCache: MutableMap<String, Int>
) {
    if (!hasViewportDescendants(node, childrenMap, left, right, top, bottom)) return

    val sortedChildren = childrenMap[node.refid]
        ?.sortedWith(compareBy({ it.y }, { it.x }))

    // 规则 1: 跳过 StaticText — 文本会被合并到父节点
    if (node.tagName == "#text" || node.role == "StaticText") return

    // 规则 2: 跳过伪元素
    if (node.tagName.startsWith("::")) return

    val isSelfVisibleOrInteractive = node.isVisible || isCleanInteractive(node)
    val isSelfInViewport = node.centerX in left..right && node.centerY in top..bottom

    var shouldSkipSelf = !isSelfInViewport || !isSelfVisibleOrInteractive || isEmptyGenericContainer(node, childrenMap)

    // 分组保护规则：如果跳过自己会导致多于 1 个子节点被提升而产生排序混乱，则必须保留自己作为容器
    if (shouldSkipSelf) {
        val children = childrenMap[node.refid] ?: emptyList()
        val compactDescendantsCount = children.sumOf { countCompactDescendants(it, childrenMap, left, right, top, bottom, compactCache) }
        if (compactDescendantsCount >= 2) {
            shouldSkipSelf = false
        }
    }

    if (shouldSkipSelf) {
        sortedChildren?.forEach { serializeNodeCompact(it, depth, sb, childrenMap, left, right, top, bottom, compactCache) }
        return
    }

    val listIndent = "  ".repeat(depth)
    val fieldIndent = "  ".repeat(depth + 1)

    sb.append(listIndent).append("- refid: ").append(yamlStr(node.refid)).append('\n')

    // role: 跳过 generic/none/presentation，但尝试通过 class name 还原出真实语义（如 button, combobox 等）
    val roleLower = node.role.lowercase()
    val isGeneric = roleLower == "generic" || roleLower == "none" || roleLower == "presentation" || node.role.isEmpty()
    val mappedRole = if (isGeneric) {
        val lowerClass = node.className.lowercase()
        if (isLikelyContainerClass(lowerClass)) {
            null
        } else if (lowerClass.contains("select") || lowerClass.contains("dropdown")) {
            "combobox"
        } else if (lowerClass.contains("btn") || lowerClass.contains("button") || lowerClass.contains("close") || lowerClass.contains("dismiss") || lowerClass.contains("toggle") || lowerClass.contains("trigger")) {
            "button"
        } else null
    } else {
        node.role
    }

    if (mappedRole != null) {
        sb.append(fieldIndent).append("role: ").append(yamlStr(mappedRole)).append('\n')
    }

    // text: 如果节点自身没有 text，从 StaticText 子节点合并
    val effectiveText = if (node.text.isNotEmpty()) {
        node.text
    } else {
        sortedChildren
            ?.filter { it.tagName == "#text" || it.role == "StaticText" }
            ?.joinToString("") { it.text }
            ?.takeIf { it.isNotEmpty() }
            ?: ""
    }
    if (effectiveText.isNotEmpty()) {
        sb.append(fieldIndent).append("text: ").append(yamlStr(effectiveText)).append('\n')
    }

    if (node.id.isNotEmpty()) {
        sb.append(fieldIndent).append("id: ").append(yamlStr(node.id)).append('\n')
    }

    sb.append(fieldIndent).append("centerX: ").append(node.centerX).append('\n')
    sb.append(fieldIndent).append("centerY: ").append(node.centerY).append('\n')

    if (node.selector.isNotEmpty()) {
        sb.append(fieldIndent).append("selector: ").append(yamlStr(node.selector)).append('\n')
    }

    if (node.occludedBy != null) {
        sb.append(fieldIndent).append("occludedBy: ").append(yamlStr(node.occludedBy)).append('\n')
    }

    // attributes: 只保留交互/语义相关属性
    val usefulAttrs = filterUsefulAttributes(node.attributes)
    if (usefulAttrs.isNotEmpty()) {
        sb.append(fieldIndent).append("attributes:\n")
        val attrIndent = fieldIndent + "  "
        for ((key, value) in usefulAttrs) {
            sb.append(attrIndent).append(yamlStr(key)).append(": ").append(yamlStr(value)).append('\n')
        }
    }

    // children: 递归序列化，只在有输出时添加 children 标签
    if (!sortedChildren.isNullOrEmpty()) {
        val childSb = StringBuilder()
        for (child in sortedChildren) {
            serializeNodeCompact(child, depth + 1, childSb, childrenMap, left, right, top, bottom, compactCache)
        }
        if (childSb.isNotEmpty()) {
            sb.append(fieldIndent).append("children:\n")
            sb.append(childSb)
        }
    }
}

private fun hasViewportDescendants(
    node: AxNode,
    childrenMap: Map<String, List<AxNode>>,
    left: Int, right: Int, top: Int, bottom: Int
): Boolean {
    val nodeRight = node.x + node.width
    val nodeBottom = node.y + node.height
    val intersects = node.x <= right && nodeRight >= left && node.y <= bottom && nodeBottom >= top
    if (intersects) return true
    val children = childrenMap[node.refid] ?: return false
    return children.any { hasViewportDescendants(it, childrenMap, left, right, top, bottom) }
}

private fun countCompactDescendants(
    node: AxNode,
    childrenMap: Map<String, List<AxNode>>,
    left: Int, right: Int, top: Int, bottom: Int,
    cache: MutableMap<String, Int>
): Int {
    cache[node.refid]?.let { return it }

    if (!hasViewportDescendants(node, childrenMap, left, right, top, bottom)) {
        cache[node.refid] = 0
        return 0
    }

    if (node.tagName == "#text" || node.role == "StaticText") {
        cache[node.refid] = 0
        return 0
    }
    if (node.tagName.startsWith("::")) {
        cache[node.refid] = 0
        return 0
    }

    val isSelfVisibleOrInteractive = node.isVisible || isCleanInteractive(node)
    val isSelfInViewport = node.centerX in left..right && node.centerY in top..bottom

    val isSelfClean = isSelfInViewport && isSelfVisibleOrInteractive && !isEmptyGenericContainer(node, childrenMap)

    val children = childrenMap[node.refid] ?: emptyList()
    val childrenCount = children.sumOf { countCompactDescendants(it, childrenMap, left, right, top, bottom, cache) }

    val result = if (isSelfClean) {
        1
    } else {
        if (childrenCount >= 2) 1 else childrenCount
    }

    cache[node.refid] = result
    return result
}

// ────────────────────────────────────────────────
// clean 模式：节点分类判断
// ────────────────────────────────────────────────

/** 空的 generic 容器：没有语义角色、没有文本（含子 StaticText）、没有 id、不可交互 → 跳过并提升 children */
private fun isEmptyGenericContainer(node: AxNode, childrenMap: Map<String, List<AxNode>>): Boolean {
    val role = node.role.lowercase()
    // 有语义角色的保留（list, listitem, navigation, heading, img 等）
    if (role != "generic" && role != "none" && role != "presentation" && role.isNotEmpty()) return false
    if (node.text.isNotEmpty()) return false
    if (node.id.isNotEmpty()) return false // 有 id 的节点通常有语义意义
    if (isCleanInteractive(node)) return false
    // 子节点有 StaticText 携带文本 → 不算空（文本会被合并到此节点）
    val children = childrenMap[node.refid]
    if (children != null && children.any { (it.tagName == "#text" || it.role == "StaticText") && it.text.isNotEmpty() }) {
        return false
    }
    return true
}

private val CLEAN_INTERACTIVE_ROLES = setOf(
    "button", "link", "checkbox", "radio", "textbox", "combobox",
    "listbox", "menuitem", "menuitemcheckbox", "menuitemradio", "option",
    "switch", "tab", "treeitem", "spinbutton", "slider", "searchbox", "img"
)

private val CLEAN_INTERACTIVE_TAGS = setOf(
    "a", "button", "input", "textarea", "select", "details", "summary", "img"
)

private fun isLikelyContainerClass(className: String): Boolean {
    val lower = className.lowercase()
    return lower.contains("group") || 
           lower.contains("container") || 
           lower.contains("wrapper") || 
           lower.contains("box") || 
           lower.contains("list") || 
           lower.contains("bar") || 
           lower.contains("panel")
}

private fun isCleanInteractive(node: AxNode): Boolean {
    if (node.role.lowercase() in CLEAN_INTERACTIVE_ROLES) return true
    if (node.tagName.lowercase() in CLEAN_INTERACTIVE_TAGS) return true
    if (node.attributes.containsKey("href")) return true
    if (node.attributes.containsKey("placeholder")) return true
    
    val lowerClass = node.className.lowercase()
    if (!isLikelyContainerClass(lowerClass)) {
        if (lowerClass.contains("close") || lowerClass.contains("dismiss") || 
            lowerClass.contains("btn") || lowerClass.contains("button") || 
            lowerClass.contains("select") || lowerClass.contains("dropdown") || 
            lowerClass.contains("menu") || lowerClass.contains("toggle") || 
            lowerClass.contains("trigger") || lowerClass.contains("switch") || 
            lowerClass.contains("tab")) {
            return true
        }
    }
    return false
}

// ────────────────────────────────────────────────
// clean 模式：attributes 白名单过滤
// ────────────────────────────────────────────────

private val USEFUL_ATTR_NAMES = setOf(
    "href", "placeholder", "type", "value", "checked", "selected",
    "disabled", "readonly", "required", "title", "alt", "src",
    "name", "for", "autocomplete", "action", "method", "target",
    "tabindex", "rel"
)

/** 只保留交互/语义相关属性，aria-* 全部保留 */
private fun filterUsefulAttributes(attrs: Map<String, String>): Map<String, String> {
    if (attrs.isEmpty()) return emptyMap()
    return attrs.filter { (key, value) ->
        value.isNotEmpty() && (key in USEFUL_ATTR_NAMES || key.startsWith("aria-"))
    }
}