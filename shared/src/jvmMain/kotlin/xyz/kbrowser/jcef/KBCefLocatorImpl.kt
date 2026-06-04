package xyz.kbrowser.jcef

import kotlinx.serialization.json.*
import xyz.kbrowser.webview.KBSelectorType
import xyz.kbrowser.webview.LocateResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * CDP 原生元素定位器实现。
 *
 * 通过 CDP 协议直接查询 DOM / Accessibility 树来定位元素，
 * 完全不注入 JS，不受页面 CSP 限制。
 *
 * 支持的选择器类型：
 * - CSS: DOM.getDocument → DOM.querySelectorAll → DOM.getBoxModel
 * - XPath: DOM.performSearch → DOM.getSearchResults → DOM.getBoxModel
 * - 语义选择器 (Role/Text/Label/Placeholder/AltText/Title/TestId):
 *   Accessibility.getFullAXTree → 属性过滤 → DOM.getBoxModel
 */
object KBCefLocatorImpl {

    private const val QUERY_TIMEOUT_SEC = 30L
    private const val CDP_CALL_TIMEOUT_SEC = 5L

    fun findAll(
        browser: org.cef.browser.CefBrowser,
        selector: String,
        selectorType: KBSelectorType,
        name: String?,
        exact: Boolean
    ): List<LocateResult> {
        val devTools = browser.devToolsClient
            ?: throw IllegalStateException("DevTools not available for selector: $selectorType=$selector")
        if (devTools.isClosed) {
            throw IllegalStateException("DevTools is closed for selector: $selectorType=$selector")
        }

        return when (selectorType) {
            KBSelectorType.CSS -> findByCss(devTools, selector)
            KBSelectorType.XPATH -> findByXPath(devTools, selector)
            KBSelectorType.ROLE -> findByRole(devTools, selector, name, exact)
            KBSelectorType.TEXT -> findByText(devTools, selector, exact)
            KBSelectorType.LABEL -> findByLabel(devTools, selector, exact)
            KBSelectorType.PLACEHOLDER -> findByPlaceholder(devTools, selector, exact)
            KBSelectorType.ALT_TEXT -> findByAltText(devTools, selector, exact)
            KBSelectorType.TITLE -> findByTitle(devTools, selector, exact)
            KBSelectorType.TEST_ID -> findByTestId(devTools, selector)
        }
    }

    // ── CSS 选择器 ─────────────────────────────────────────────────────────

    private fun findByCss(devTools: org.cef.browser.CefDevToolsClient, selector: String): List<LocateResult> {
        // 1. DOM.getDocument 获取根 nodeId
        val docJson = executeCdpWithTimeout(devTools, "DOM.getDocument", """{"depth":0}""", selector)
        val docRoot = Json.parseToJsonElement(docJson).jsonObject
        val rootNode = docRoot["root"]?.jsonObject
            ?: docRoot["result"]?.jsonObject?.get("root")?.jsonObject
            ?: docRoot["result"]?.jsonObject?.get("result")?.jsonObject?.get("root")?.jsonObject
            ?: throw RuntimeException("DOM.getDocument failed for selector: $selector")
        val rootNodeId = rootNode["nodeId"]?.jsonPrimitive?.int
            ?: throw RuntimeException("DOM.getDocument returned no nodeId for selector: $selector")

        // 2. DOM.querySelectorAll
        val escapedSelector = selector.replace("\"", "\\\"")
        val queryJson = executeCdpWithTimeout(
            devTools, "DOM.querySelectorAll",
            """{"nodeId":$rootNodeId,"selector":"$escapedSelector"}""",
            selector
        )
        val queryRoot = Json.parseToJsonElement(queryJson).jsonObject
        val nodeIds = queryRoot["nodeIds"]?.jsonArray
            ?: queryRoot["result"]?.jsonObject?.get("nodeIds")?.jsonArray
            ?: queryRoot["result"]?.jsonObject?.get("result")?.jsonObject?.get("nodeIds")?.jsonArray
            ?: return emptyList()

        if (nodeIds.isEmpty()) return emptyList()

        // 3. 对每个 nodeId 调用 DOM.getBoxModel（使用 nodeId，不是 backendNodeId）
        return nodeIds.mapNotNull { nodeIdElement ->
            val nodeId = nodeIdElement.jsonPrimitive.int
            getBoxModelByNodeId(devTools, nodeId)?.copy(
                selector = getSelector(devTools, nodeId = nodeId)
            )
        }
    }

    // ── XPath 选择器 ───────────────────────────────────────────────────────

    private fun findByXPath(devTools: org.cef.browser.CefDevToolsClient, query: String): List<LocateResult> {
        // 1. DOM.performSearch
        val escapedQuery = query.replace("\"", "\\\"")
        val searchJson = executeCdpWithTimeout(
            devTools, "DOM.performSearch",
            """{"query":"$escapedQuery"}""",
            query
        )
        val searchRoot = Json.parseToJsonElement(searchJson).jsonObject
        val searchResult = searchRoot.resolveFields("searchId", "resultCount")
            ?: throw RuntimeException("DOM.performSearch failed for xpath: $query")
        val searchId = searchResult.first
        val resultCount = searchResult.second

        if (resultCount <= 0) return emptyList()

        try {
            // 2. DOM.getSearchResults
            val resultsJson = executeCdpWithTimeout(
                devTools, "DOM.getSearchResults",
                """{"searchId":"$searchId","fromIndex":0,"toIndex":$resultCount}""",
                query
            )
            val resultsRoot = Json.parseToJsonElement(resultsJson).jsonObject
            val nodeIds = resultsRoot["nodeIds"]?.jsonArray
                ?: resultsRoot["result"]?.jsonObject?.get("nodeIds")?.jsonArray
                ?: resultsRoot["result"]?.jsonObject?.get("result")?.jsonObject?.get("nodeIds")?.jsonArray
                ?: return emptyList()

            // 3. 对每个 nodeId 调用 DOM.getBoxModel
            return nodeIds.mapNotNull { nodeIdElement ->
                val nodeId = nodeIdElement.jsonPrimitive.int
                getBoxModelByNodeId(devTools, nodeId)?.copy(
                    selector = getSelector(devTools, nodeId = nodeId)
                )
            }
        } finally {
            // 4. 清理搜索结果
            try {
                devTools.executeDevToolsMethod(
                    "DOM.discardSearchResults",
                    """{"searchId":"$searchId"}"""
                ).get(CDP_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // 清理失败不影响结果
            }
        }
    }

    // ── 语义选择器：Role ───────────────────────────────────────────────────

    private fun findByRole(
        devTools: org.cef.browser.CefDevToolsClient,
        role: String,
        name: String?,
        exact: Boolean
    ): List<LocateResult> {
        val axNodes = fetchAxNodes(devTools, "ROLE=$role")
        val matched = axNodes.filter { node ->
            val nodeRole = node.role
            val roleMatch = nodeRole.equals(role, ignoreCase = true)
            if (!roleMatch) return@filter false
            // 如果提供了 name 参数，还需要匹配 name
            if (name != null && name.isNotEmpty()) {
                matchText(node.name, name, exact)
            } else {
                true
            }
        }
        return resolveAxNodesToLocateResults(devTools, matched)
    }

    // ── 语义选择器：Text ──────────────────────────────────────────────────

    private fun findByText(
        devTools: org.cef.browser.CefDevToolsClient,
        text: String,
        exact: Boolean
    ): List<LocateResult> {
        val axNodes = fetchAxNodes(devTools, "TEXT=$text")
        val matched = axNodes.filter { node ->
            matchText(node.name, text, exact)
        }
        return resolveAxNodesToLocateResults(devTools, matched)
    }

    // ── 语义选择器：Label ─────────────────────────────────────────────────

    private fun findByLabel(
        devTools: org.cef.browser.CefDevToolsClient,
        label: String,
        exact: Boolean
    ): List<LocateResult> {
        val axNodes = fetchAxNodes(devTools, "LABEL=$label")
        val matched = axNodes.filter { node ->
            // 匹配 name.value 或 properties 中的 aria-label
            matchText(node.name, label, exact) ||
                node.properties.any { prop ->
                    prop.name == "label" && matchText(prop.value, label, exact)
                }
        }
        return resolveAxNodesToLocateResults(devTools, matched)
    }

    // ── 语义选择器：Placeholder ───────────────────────────────────────────

    private fun findByPlaceholder(
        devTools: org.cef.browser.CefDevToolsClient,
        placeholder: String,
        exact: Boolean
    ): List<LocateResult> {
        val axNodes = fetchAxNodes(devTools, "PLACEHOLDER=$placeholder")
        val matched = axNodes.filter { node ->
            node.properties.any { prop ->
                prop.name == "placeholder" && matchText(prop.value, placeholder, exact)
            }
        }
        return resolveAxNodesToLocateResults(devTools, matched)
    }

    // ── 语义选择器：AltText ───────────────────────────────────────────────

    private fun findByAltText(
        devTools: org.cef.browser.CefDevToolsClient,
        altText: String,
        exact: Boolean
    ): List<LocateResult> {
        val axNodes = fetchAxNodes(devTools, "ALT_TEXT=$altText")
        val matched = axNodes.filter { node ->
            node.properties.any { prop ->
                (prop.name == "description" || prop.name == "alt") &&
                    matchText(prop.value, altText, exact)
            }
        }
        return resolveAxNodesToLocateResults(devTools, matched)
    }

    // ── 语义选择器：Title ─────────────────────────────────────────────────

    private fun findByTitle(
        devTools: org.cef.browser.CefDevToolsClient,
        title: String,
        exact: Boolean
    ): List<LocateResult> {
        val axNodes = fetchAxNodes(devTools, "TITLE=$title")
        val matched = axNodes.filter { node ->
            node.properties.any { prop ->
                prop.name == "title" && matchText(prop.value, title, exact)
            }
        }
        return resolveAxNodesToLocateResults(devTools, matched)
    }

    // ── 语义选择器：TestId ────────────────────────────────────────────────

    private fun findByTestId(
        devTools: org.cef.browser.CefDevToolsClient,
        testId: String
    ): List<LocateResult> {
        val axNodes = fetchAxNodes(devTools, "TEST_ID=$testId")
        // TestId 需要通过 DOM.describeNode 检查 data-testid 属性
        val matched = mutableListOf<AxSemanticNode>()
        for (node in axNodes) {
            if (node.backendNodeId <= 0) continue
            val attrs = describeNodeAttributes(devTools, node.backendNodeId)
            if (attrs["data-testid"] == testId) {
                matched.add(node)
            }
        }
        return resolveAxNodesToLocateResults(devTools, matched)
    }

    // ── 辅助方法 ───────────────────────────────────────────────────────────

    /**
     * 文本匹配：exact 模式精确匹配，非 exact 模式包含匹配（忽略大小写）
     */
    private fun matchText(actual: String, expected: String, exact: Boolean): Boolean {
        if (actual.isEmpty() && expected.isEmpty()) return true
        if (actual.isEmpty()) return false
        return if (exact) {
            actual == expected
        } else {
            actual.contains(expected, ignoreCase = true)
        }
    }

    /**
     * 内部 AX 语义节点数据类
     */
    private data class AxSemanticNode(
        val backendNodeId: Int,
        val role: String,
        val name: String,
        val properties: List<AxProperty>
    )

    private data class AxProperty(
        val name: String,
        val value: String
    )

    /**
     * 获取完整 AX 树并解析为语义节点列表
     */
    private fun fetchAxNodes(devTools: org.cef.browser.CefDevToolsClient, selectorInfo: String): List<AxSemanticNode> {
        val axJson = executeCdpWithTimeout(devTools, "Accessibility.getFullAXTree", "{}", selectorInfo)
        val axRoot = Json.parseToJsonElement(axJson).jsonObject
        val axNodes = axRoot["nodes"]?.jsonArray
            ?: axRoot["result"]?.jsonObject?.get("nodes")?.jsonArray
            ?: axRoot["result"]?.jsonObject?.get("result")?.jsonObject?.get("nodes")?.jsonArray
            ?: return emptyList()

        val result = mutableListOf<AxSemanticNode>()
        for (node in axNodes) {
            val obj = node.jsonObject
            if (obj["ignored"]?.jsonPrimitive?.booleanOrNull == true) continue
            val backendNodeId = obj["backendDOMNodeId"]?.jsonPrimitive?.intOrNull ?: continue
            val role = obj["role"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull ?: ""
            val name = obj["name"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull ?: ""

            // 解析 properties 数组
            val properties = mutableListOf<AxProperty>()
            obj["properties"]?.jsonArray?.forEach { propElement ->
                val propObj = propElement.jsonObject
                val propName = propObj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val propValue = propObj["value"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull ?: ""
                properties.add(AxProperty(propName, propValue))
            }

            result.add(AxSemanticNode(backendNodeId, role, name, properties))
        }
        return result
    }

    /**
     * 将匹配的 AX 语义节点转换为 LocateResult 列表。
     * 使用 backendNodeId 调用 DOM.getBoxModel 获取坐标。
     */
    private fun resolveAxNodesToLocateResults(
        devTools: org.cef.browser.CefDevToolsClient,
        nodes: List<AxSemanticNode>
    ): List<LocateResult> {
        if (nodes.isEmpty()) return emptyList()

        val results = mutableListOf<LocateResult>()
        // 并发发送 DOM.getBoxModel 请求
        val futures = nodes.map { node ->
            node to devTools.executeDevToolsMethod(
                "DOM.getBoxModel",
                """{"backendNodeId":${node.backendNodeId}}"""
            )
        }

        for ((node, future) in futures) {
            val boxJson = try {
                future.get(CDP_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (_: Exception) { continue } ?: continue

            val locateResult = parseBoxModelToLocateResult(
                boxJson,
                tagName = "",
                role = node.role,
                text = node.name
            ) ?: continue

            val selector = getSelector(devTools, backendNodeId = node.backendNodeId)
            results.add(locateResult.copy(selector = selector))
        }
        return results
    }

    /**
     * 通过 nodeId 获取 DOM.getBoxModel 并构建 LocateResult。
     * 注意：CSS/XPath 查询返回的是 nodeId，不是 backendNodeId。
     */
    private fun getBoxModelByNodeId(
        devTools: org.cef.browser.CefDevToolsClient,
        nodeId: Int
    ): LocateResult? {
        val boxJson = try {
            devTools.executeDevToolsMethod(
                "DOM.getBoxModel",
                """{"nodeId":$nodeId}"""
            ).get(CDP_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (_: Exception) { return null } ?: return null

        return parseBoxModelToLocateResult(boxJson)
    }

    /**
     * 解析 DOM.getBoxModel 响应，提取 content quad 坐标并构建 LocateResult。
     * 返回 null 如果解析失败。
     */
    private fun parseBoxModelToLocateResult(
        boxJson: String,
        tagName: String = "",
        role: String = "",
        text: String = ""
    ): LocateResult? {
        return try {
            val boxRoot = Json.parseToJsonElement(boxJson).jsonObject
            // 三层 fallback 解析
            val model = boxRoot["model"]?.jsonObject
                ?: boxRoot["result"]?.jsonObject?.get("model")?.jsonObject
                ?: boxRoot["result"]?.jsonObject?.get("result")?.jsonObject?.get("model")?.jsonObject
                ?: return null

            // content quad: [x1,y1, x2,y2, x3,y3, x4,y4]
            val content = model["content"]?.jsonArray ?: return null
            if (content.size < 8) return null

            val x1 = content[0].jsonPrimitive.double.toInt()
            val y1 = content[1].jsonPrimitive.double.toInt()
            val x3 = content[4].jsonPrimitive.double.toInt()
            val y3 = content[5].jsonPrimitive.double.toInt()
            val width = x3 - x1
            val height = y3 - y1

            val isVisible = width > 0 && height > 0
            val centerX = x1 + width / 2
            val centerY = y1 + height / 2

            LocateResult(
                centerX = centerX,
                centerY = centerY,
                width = width,
                height = height,
                tagName = tagName,
                role = role,
                text = text,
                isVisible = isVisible,
                attributes = emptyMap()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getSelector(
        devTools: org.cef.browser.CefDevToolsClient,
        nodeId: Int? = null,
        backendNodeId: Int? = null
    ): String {
        return try {
            val params = when {
                nodeId != null -> """{"nodeId":$nodeId}"""
                backendNodeId != null -> """{"backendNodeId":$backendNodeId}"""
                else -> return ""
            }
            val resolveJson = devTools.executeDevToolsMethod(
                "DOM.resolveNode",
                params
            ).get(CDP_CALL_TIMEOUT_SEC, TimeUnit.SECONDS) ?: return ""
            val r = Json.parseToJsonElement(resolveJson).jsonObject
            val objectId = r["object"]?.jsonObject?.get("objectId")?.jsonPrimitive?.content
                ?: r["result"]?.jsonObject?.get("object")?.jsonObject?.get("objectId")?.jsonPrimitive?.content
                ?: r["result"]?.jsonObject?.get("result")?.jsonObject?.get("object")?.jsonObject?.get("objectId")?.jsonPrimitive?.content
                ?: return ""
            
            val escapedObjId = Json.encodeToString(JsonPrimitive(objectId))
            val selectorFn = xyz.kbrowser.webview.JsScripts.BUILD_SELECTOR_CALL_FN
            val callJson = devTools.executeDevToolsMethod(
                "Runtime.callFunctionOn",
                """{"objectId":$escapedObjId,"functionDeclaration":${Json.encodeToString(JsonPrimitive(selectorFn))},"returnByValue":true}"""
            ).get(CDP_CALL_TIMEOUT_SEC, TimeUnit.SECONDS) ?: return ""
            val cr = Json.parseToJsonElement(callJson).jsonObject
            cr["result"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                ?: cr["result"]?.jsonObject?.get("result")?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 通过 DOM.describeNode 获取节点属性（用于 TestId 匹配）。
     */
    private fun describeNodeAttributes(
        devTools: org.cef.browser.CefDevToolsClient,
        backendNodeId: Int
    ): Map<String, String> {
        val json = try {
            devTools.executeDevToolsMethod(
                "DOM.describeNode",
                """{"backendNodeId":$backendNodeId}"""
            ).get(CDP_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (_: Exception) { return emptyMap() } ?: return emptyMap()

        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            val node = root["node"]?.jsonObject
                ?: root["result"]?.jsonObject?.get("node")?.jsonObject
                ?: root["result"]?.jsonObject?.get("result")?.jsonObject?.get("node")?.jsonObject
                ?: return emptyMap()

            val attrsArray = node["attributes"]?.jsonArray ?: return emptyMap()
            val attrs = mutableMapOf<String, String>()
            var i = 0
            while (i + 1 < attrsArray.size) {
                val attrName = attrsArray[i].jsonPrimitive.content
                val attrValue = attrsArray[i + 1].jsonPrimitive.content
                attrs[attrName] = attrValue
                i += 2
            }
            attrs
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 执行 CDP 方法并等待响应，带 30 秒总超时。
     * 超时或错误时抛出异常，包含 CDP 方法名和选择器值。
     */
    private fun executeCdpWithTimeout(
        devTools: org.cef.browser.CefDevToolsClient,
        method: String,
        params: String,
        selectorInfo: String
    ): String {
        val future: CompletableFuture<String> = devTools.executeDevToolsMethod(method, params)
        return try {
            future.get(QUERY_TIMEOUT_SEC, TimeUnit.SECONDS)
                ?: throw RuntimeException("$method returned null for selector: $selectorInfo")
        } catch (e: TimeoutException) {
            throw RuntimeException("$method timed out (${QUERY_TIMEOUT_SEC}s) for selector: $selectorInfo", e)
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException("$method failed for selector: $selectorInfo — ${e.message}", e)
        }
    }

    /**
     * 从 DOM.performSearch 响应中解析 searchId 和 resultCount。
     * 支持三层 fallback 解析。
     */
    private fun JsonObject.resolveFields(searchIdField: String, countField: String): Pair<String, Int>? {
        // 尝试顶层
        val topSearchId = this[searchIdField]?.jsonPrimitive?.contentOrNull
        val topCount = this[countField]?.jsonPrimitive?.intOrNull
        if (topSearchId != null && topCount != null) return topSearchId to topCount

        // 尝试一层 result
        val r1 = this["result"]?.jsonObject
        if (r1 != null) {
            val s1 = r1[searchIdField]?.jsonPrimitive?.contentOrNull
            val c1 = r1[countField]?.jsonPrimitive?.intOrNull
            if (s1 != null && c1 != null) return s1 to c1

            // 尝试两层 result
            val r2 = r1["result"]?.jsonObject
            if (r2 != null) {
                val s2 = r2[searchIdField]?.jsonPrimitive?.contentOrNull
                val c2 = r2[countField]?.jsonPrimitive?.intOrNull
                if (s2 != null && c2 != null) return s2 to c2
            }
        }
        return null
    }
}
