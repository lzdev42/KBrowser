package xyz.kbrowser.jcef

import kotlinx.serialization.json.*
import xyz.kbrowser.webview.KBSelectorType
import xyz.kbrowser.webview.LocateResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Element locator implemented natively over CDP.
 *
 * Queries the DOM / accessibility tree directly via CDP; no JS is injected, so
 * page CSP does not apply.
 *
 * Supported selector types:
 * - CSS: DOM.getDocument → DOM.querySelectorAll → DOM.getBoxModel
 * - XPath: DOM.performSearch → DOM.getSearchResults → DOM.getBoxModel
 * - Semantic (Role/Text/Label/Placeholder/AltText/Title/TestId):
 *   Accessibility.getFullAXTree → property filtering → DOM.getBoxModel
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

    private fun findByCss(devTools: org.cef.browser.CefDevToolsClient, selector: String): List<LocateResult> {
        val docJson = executeCdpWithTimeout(devTools, "DOM.getDocument", """{"depth":0}""", selector)
        val docRoot = Json.parseToJsonElement(docJson).jsonObject
        val rootNode = docRoot["root"]?.jsonObject
            ?: docRoot["result"]?.jsonObject?.get("root")?.jsonObject
            ?: docRoot["result"]?.jsonObject?.get("result")?.jsonObject?.get("root")?.jsonObject
            ?: throw RuntimeException("DOM.getDocument failed for selector: $selector")
        val rootNodeId = rootNode["nodeId"]?.jsonPrimitive?.int
            ?: throw RuntimeException("DOM.getDocument returned no nodeId for selector: $selector")

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

        // DOM.getBoxModel here takes nodeId, not backendNodeId
        return nodeIds.mapNotNull { nodeIdElement ->
            val nodeId = nodeIdElement.jsonPrimitive.int
            getBoxModelByNodeId(devTools, nodeId)?.copy(
                selector = getSelector(devTools, nodeId = nodeId)
            )
        }
    }

    private fun findByXPath(devTools: org.cef.browser.CefDevToolsClient, query: String): List<LocateResult> {
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

            return nodeIds.mapNotNull { nodeIdElement ->
                val nodeId = nodeIdElement.jsonPrimitive.int
                getBoxModelByNodeId(devTools, nodeId)?.copy(
                    selector = getSelector(devTools, nodeId = nodeId)
                )
            }
        } finally {
            try {
                devTools.executeDevToolsMethod(
                    "DOM.discardSearchResults",
                    """{"searchId":"$searchId"}"""
                ).get(CDP_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // cleanup failure does not affect the results
            }
        }
    }

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
            if (name != null && name.isNotEmpty()) {
                matchText(node.name, name, exact)
            } else {
                true
            }
        }
        return resolveAxNodesToLocateResults(devTools, matched)
    }

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

    private fun findByLabel(
        devTools: org.cef.browser.CefDevToolsClient,
        label: String,
        exact: Boolean
    ): List<LocateResult> {
        val axNodes = fetchAxNodes(devTools, "LABEL=$label")
        val matched = axNodes.filter { node ->
            // match name.value or the aria-label property
            matchText(node.name, label, exact) ||
                node.properties.any { prop ->
                    prop.name == "label" && matchText(prop.value, label, exact)
                }
        }
        return resolveAxNodesToLocateResults(devTools, matched)
    }

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

    private fun findByTestId(
        devTools: org.cef.browser.CefDevToolsClient,
        testId: String
    ): List<LocateResult> {
        val axNodes = fetchAxNodes(devTools, "TEST_ID=$testId")
        // data-testid is not carried in the AX tree; look it up via DOM.describeNode
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

    /**
     * Text matching: exact equality in exact mode, otherwise case-insensitive contains.
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

    /** Internal AX semantic node. */
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

    /** Fetches the full AX tree and parses it into semantic nodes. */
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
     * Converts matched AX semantic nodes to [LocateResult]s, resolving coordinates
     * via DOM.getBoxModel using backendNodeId.
     */
    private fun resolveAxNodesToLocateResults(
        devTools: org.cef.browser.CefDevToolsClient,
        nodes: List<AxSemanticNode>
    ): List<LocateResult> {
        if (nodes.isEmpty()) return emptyList()

        val results = mutableListOf<LocateResult>()
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
     * Fetches DOM.getBoxModel by nodeId and builds a [LocateResult].
     * Note: CSS/XPath queries return nodeId, not backendNodeId.
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
     * Parses a DOM.getBoxModel response into a [LocateResult] from the content quad.
     * Returns null if parsing fails.
     */
    private fun parseBoxModelToLocateResult(
        boxJson: String,
        tagName: String = "",
        role: String = "",
        text: String = ""
    ): LocateResult? {
        return try {
            val boxRoot = Json.parseToJsonElement(boxJson).jsonObject
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

    /** Fetches node attributes via DOM.describeNode (used for TestId matching). */
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
     * Executes a CDP method and awaits the response with a 30s total timeout.
     * Throws on timeout/error, including the CDP method name and selector value.
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
     * Parses searchId and resultCount from a DOM.performSearch response, trying the
     * top level, result, and result.result nesting.
     */
    private fun JsonObject.resolveFields(searchIdField: String, countField: String): Pair<String, Int>? {
        val topSearchId = this[searchIdField]?.jsonPrimitive?.contentOrNull
        val topCount = this[countField]?.jsonPrimitive?.intOrNull
        if (topSearchId != null && topCount != null) return topSearchId to topCount

        val r1 = this["result"]?.jsonObject
        if (r1 != null) {
            val s1 = r1[searchIdField]?.jsonPrimitive?.contentOrNull
            val c1 = r1[countField]?.jsonPrimitive?.intOrNull
            if (s1 != null && c1 != null) return s1 to c1

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
