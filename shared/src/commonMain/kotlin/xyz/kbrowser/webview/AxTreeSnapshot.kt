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

    // ── 重建树形结构 ──
    // 优先使用 CDP AX 树提供的 childIds（真实 DOM 层级），
    // 仅在 CDP 层级信息不可用时回退到坐标包含关系重建。
    //
    // 坐标包含关系对绝对定位元素（下拉菜单、弹窗等）会错误分配父节点：
    // 下拉菜单的视觉坐标不在 DOM 父节点（触发按钮）内，而在面积更大的其他元素内，
    // 导致下拉菜单被错误嵌套到无关的容器节点下。
    val nodeIndex = visibleNodes.associateBy { it.refid }
    val parentMap = mutableMapOf<String, String>() // refid -> parentRefid
    val childrenMap = mutableMapOf<String, MutableList<AxNode>>()
    val hasCdpHierarchy = visibleNodes.any { it.childIds.isNotEmpty() }

    if (hasCdpHierarchy) {
        // ── CDP 路径：使用 childIds 构建真实 DOM 层级 ──
        for (node in visibleNodes) {
            for (childRefid in node.childIds) {
                val childNode = nodeIndex[childRefid] ?: continue
                childrenMap.getOrPut(node.refid) { mutableListOf() }.add(childNode)
                parentMap[childRefid] = node.refid
            }
        }

        // 处理孤儿节点：CDP 父节点被过滤（不可见/噪音标签）后，
        // 其子节点在可见集合中没有父节点，需要用坐标回退找最近的可见祖先
        val orphans = visibleNodes.filter { it.refid !in parentMap }
        if (orphans.isNotEmpty()) {
            val nodeArea = { n: AxNode -> n.width.toLong() * n.height.toLong() }
            for (orphan in orphans) {
                var bestParent: AxNode? = null
                var bestArea = Long.MAX_VALUE
                for (candidate in visibleNodes) {
                    if (candidate.refid == orphan.refid) continue
                    // 跳过已经是孤儿自身后代的节点（避免循环）
                    if (isDescendantOf(candidate.refid, orphan.refid, parentMap)) continue
                    val area = nodeArea(candidate)
                    if (area <= nodeArea(orphan)) continue
                    val contains = orphan.centerX >= candidate.x &&
                            orphan.centerX <= candidate.x + candidate.width &&
                            orphan.centerY >= candidate.y &&
                            orphan.centerY <= candidate.y + candidate.height
                    if (contains && area < bestArea) {
                        bestArea = area
                        bestParent = candidate
                    }
                }
                if (bestParent != null) {
                    parentMap[orphan.refid] = bestParent.refid
                    childrenMap.getOrPut(bestParent.refid) { mutableListOf() }.add(orphan)
                }
            }
        }
    } else {
        // ── JS 注入路径：无 childIds，回退到坐标包含关系重建 ──
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

    // 找根节点（没有父节点的）
    val roots = visibleNodes.filter { it.refid !in parentMap }

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

/**
 * 检查 [candidateRefid] 是否是 [ancestorRefid] 在 [parentMap] 中的后代。
 * 用于孤儿节点回退时避免将祖先分配为自身的子节点（防止循环）。
 */
private fun isDescendantOf(candidateRefid: String, ancestorRefid: String, parentMap: Map<String, String>): Boolean {
    var current = parentMap[candidateRefid]
    var depth = 0
    while (current != null && depth < 50) { // 防止异常数据导致无限循环
        if (current == ancestorRefid) return true
        current = parentMap[current]
        depth++
    }
    return false
}
