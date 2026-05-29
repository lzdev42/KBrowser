package xyz.kbrowser.webview

/**
 * 将 AxTreeData 转换为 KBrowser YAML Snapshot 格式。
 *
 * 格式设计目标：语义清晰、token 紧凑、AI 一读就懂。
 * 参考 Playwright aria snapshot，融合 KBrowser 特色（坐标、选择器、遮挡信息）。
 *
 * 输出示例：
 * ```
 * - document "【BOSS直聘注册登录】" [url:https://www.zhipin.com/web/user/]
 *   - link "找工作 BOSS直聘直接谈" @r73 [center:309,262] [selector:body>div>a]
 *   - generic "APP扫码登录" @r88 [center:441,201] [selector:...] [class:btn-sign-switch]
 *   - heading "验证码登录/注册" @r91 [center:657,236]
 *   - textbox [placeholder:手机号] @r29 [center:691,380]
 *   - textbox [placeholder:短信验证码] @r30 [center:657,456] [occludedBy:@r88]
 *   - button "登录/注册" @r117 [center:657,534]
 * ```
 */
fun AxTreeData.toYamlSnapshot(): String {
    if (nodes.isEmpty()) return ""

    // 过滤：只保留可见节点，去掉纯技术标签和 overlay
    val noiseTags = setOf("script", "style", "link", "meta", "noscript", "head", "::before", "::after")
    val visibleNodes = nodes.filter { node ->
        if (!node.isVisible) return@filter false
        val tag = node.tagName.lowercase()
        if (tag in noiseTags) return@filter false
        if (node.id == "__kb_overlay__") return@filter false
        val style = node.attributes["style"] ?: ""
        if (style.contains("outline:") && style.contains("rgba") &&
            style.contains("position: absolute") && style.contains("box-sizing: border-box")) {
            return@filter false
        }
        true
    }

    if (visibleNodes.isEmpty()) return ""

    // 用坐标包含关系重建树形结构
    // 策略：对每个节点，找面积最小的包含它的祖先节点作为父节点
    val nodeArea = { n: AxNode -> n.width.toLong() * n.height.toLong() }

    // 按面积从大到小排序，面积大的更可能是父节点
    val sorted = visibleNodes.sortedByDescending { nodeArea(it) }

    // 为每个节点找父节点（面积最小的包含它的节点）
    val parentMap = mutableMapOf<String, String?>() // refid -> parentRefid
    for (node in visibleNodes) {
        var bestParent: AxNode? = null
        var bestArea = Long.MAX_VALUE
        for (candidate in visibleNodes) {
            if (candidate.refid == node.refid) continue
            val area = nodeArea(candidate)
            if (area <= nodeArea(node)) continue // 父节点面积必须更大
            // 检查包含关系：候选节点的边界包含当前节点的中心点
            val contains = node.centerX >= candidate.x &&
                    node.centerX <= candidate.x + candidate.width &&
                    node.centerY >= candidate.y &&
                    node.centerY <= candidate.y + candidate.height
            if (contains && area < bestArea) {
                bestArea = area
                bestParent = candidate
            }
        }
        parentMap[node.refid] = bestParent?.refid
    }

    // 找根节点（没有父节点的）
    val roots = visibleNodes.filter { parentMap[it.refid] == null }
    // 找子节点映射
    val childrenMap = mutableMapOf<String, MutableList<AxNode>>()
    for (node in visibleNodes) {
        val pid = parentMap[node.refid]
        if (pid != null) {
            childrenMap.getOrPut(pid) { mutableListOf() }.add(node)
        }
    }

    // 文本上浮：如果节点 text 为空，从直接子节点中收集 #text 节点的文本
    fun collectText(node: AxNode): String {
        if (node.text.isNotBlank()) return node.text
        val children = childrenMap[node.refid] ?: return ""
        val textParts = children
            .filter { it.tagName == "#text" && it.text.isNotBlank() }
            .map { it.text.trim() }
        return textParts.joinToString(" ").trim()
    }

    // 序列化节点
    val sb = StringBuilder()

    fun serializeNode(node: AxNode, depth: Int) {
        // #text 节点不单独输出（已上浮到父节点）
        if (node.tagName == "#text") return

        val indent = "  ".repeat(depth)
        val role = when {
            node.role.isNotBlank() && node.role.lowercase() != "generic" && node.role.lowercase() != "none" ->
                node.role.lowercase()
            node.tagName.isNotBlank() && node.tagName != "#document" -> node.tagName.lowercase()
            else -> "generic"
        }

        val text = collectText(node)
        val placeholder = node.attributes["placeholder"]
        val href = node.attributes["href"]
        val checked = when {
            node.role.lowercase() == "checkbox" || node.role.lowercase() == "radio" ->
                node.attributes["checked"] ?: node.attributes["aria-checked"]
            else -> null
        }
        val isActive = node.attributes["aria-selected"] == "true" ||
                node.className.contains("active") ||
                node.attributes["aria-current"] != null

        sb.append(indent).append("- ").append(role)

        // 文本或 placeholder
        when {
            text.isNotBlank() -> sb.append(" \"").append(text.take(80)).append("\"")
            placeholder != null -> sb.append(" [placeholder:").append(placeholder).append("]")
        }

        // refid
        if (node.refid.isNotBlank()) sb.append(" @").append(node.refid)

        // 坐标
        if (node.centerX != 0 || node.centerY != 0) {
            sb.append(" [center:").append(node.centerX).append(",").append(node.centerY).append("]")
        }

        // 选择器（缩短显示）
        if (node.selector.isNotBlank()) {
            val shortSelector = node.selector.take(60).let {
                if (node.selector.length > 60) "$it…" else it
            }
            sb.append(" [selector:").append(shortSelector).append("]")
        }

        // 遮挡信息
        if (node.occludedBy != null) {
            sb.append(" [occludedBy:@").append(node.occludedBy).append("]")
        }

        // 状态标记
        if (isActive) sb.append(" [active]")
        if (checked == "true" || checked == "mixed") sb.append(" [checked]")
        if (checked == "false") sb.append(" [unchecked]")

        // href（link 节点）
        if (href != null && href != "javascript:;" && !href.startsWith("javascript")) {
            sb.append(" [href:").append(href.take(60)).append("]")
        }

        // className（有语义价值时保留，过滤纯样式 class）
        if (node.className.isNotBlank() && text.isBlank() && placeholder == null) {
            sb.append(" [class:").append(node.className.take(40)).append("]")
        }

        sb.append("\n")

        // 递归子节点（按 y 坐标排序，从上到下）
        val children = childrenMap[node.refid]
            ?.filter { it.tagName != "#text" } // #text 已上浮，不再递归输出
            ?.sortedWith(compareBy({ it.y }, { it.x }))
            ?: emptyList()

        for (child in children) {
            serializeNode(child, depth + 1)
        }
    }

    // 输出页面头部信息
    sb.append("# KBrowser Snapshot\n")
    sb.append("# url: ").append(url).append("\n")
    sb.append("# viewport: ").append(innerWidth).append("x").append(innerHeight).append("\n")
    sb.append("#\n")
    sb.append("# Format: - role \"text\" @refid [center:x,y] [selector:...] [occludedBy:@refid]\n")
    sb.append("# @refid  → use with page.click(refid) or page.locator(selector)\n")
    sb.append("# occludedBy → coordinate click blocked; dismiss that element first\n")
    sb.append("#\n")

    val sortedRoots = roots.sortedWith(compareBy({ it.y }, { it.x }))
    for (root in sortedRoots) {
        serializeNode(root, 0)
    }

    return sb.toString().trim()
}
