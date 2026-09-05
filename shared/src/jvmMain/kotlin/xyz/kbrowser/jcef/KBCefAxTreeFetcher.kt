package xyz.kbrowser.jcef

import kotlinx.serialization.json.*
import xyz.kbrowser.webview.AxNode
import xyz.kbrowser.webview.AxTreeData
import java.util.concurrent.TimeUnit

/**
 * Fetches the full accessibility tree plus node coordinates over CDP.
 *
 * Strategy:
 * 1. CDP Accessibility.getFullAXTree  → semantic info (role / name / backendDOMNodeId)
 * 2. One CDP Runtime.evaluate call    → batched getBoundingClientRect coordinates for
 *    all nodes (keyed by backendDOMNodeId, resolved to objectId via DOM.resolveNode,
 *    then queried in bulk via Runtime.callFunctionOn)
 *
 * No persistent JS is injected, so page CSP does not apply and page state is untouched.
 */
object KBCefAxTreeFetcher {

    private const val TIMEOUT_SEC = 15L
    private const val BOX_TIMEOUT_SEC = 5L
    private const val BATCH_SIZE = 80

    fun fetch(browser: org.cef.browser.CefBrowser): AxTreeData {
        // In JBR remote mode the CefBrowser is a RemoteBrowser whose native peer is
        // created asynchronously in the cef_server process. Wait until
        // isNativeBrowserCreated(), otherwise addDevToolsMessageObserver returns null,
        // the CefDevToolsClient stays "closed", and every CDP call fails immediately.
        if (!waitForNativeBrowser(browser)) {
            return AxTreeData()
        }

        val devTools = browser.devToolsClient
        if (devTools == null || devTools.isClosed) {
            return AxTreeData()
        }

        val pageInfo = fetchPageInfoViaCDP(devTools, browser) ?: fetchPageInfoViaJs(devTools)
        if (pageInfo == null) {
            return AxTreeData()
        }

        val axJson = try {
            devTools.executeDevToolsMethod("Accessibility.getFullAXTree", "{}")
                .get(TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return AxTreeData()
        }
        if (axJson == null) {
            return AxTreeData()
        }

        val axRoot = Json.parseToJsonElement(axJson).jsonObject
        // The response shape differs between modes, so try three layouts:
        //   Remote mode: {"nodes":[...]}
        //   Local mode:  {"result":{"nodes":[...]}}
        //   Possibly:    {"result":{"result":{"nodes":[...]}}}
        val axNodes = axRoot["nodes"]?.jsonArray
            ?: axRoot["result"]?.jsonObject?.get("nodes")?.jsonArray
            ?: axRoot["result"]?.jsonObject?.get("result")?.jsonObject?.get("nodes")?.jsonArray
            ?: return AxTreeData()

        // Do not filter out nodes with an empty role: plain containers (div/span/p)
        // may have an empty or "none" role but are still valid DOM nodes that must
        // be included in the result.
        data class SemNode(
            val nodeId: String,
            val backendNodeId: Int,
            val role: String,
            val name: String,
            val childIds: List<String>
        )
        val childToParentMap = mutableMapOf<String, String>()
        for (node in axNodes) {
            val parentId = node.jsonObject["nodeId"]?.jsonPrimitive?.contentOrNull ?: continue
            val childIds = node.jsonObject["childIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: continue
            for (childId in childIds) {
                childToParentMap[childId] = parentId
            }
        }

        val allAxNodesMap = axNodes.associateBy { it.jsonObject["nodeId"]?.jsonPrimitive?.contentOrNull ?: "" }

        fun isValidSemNode(nodeId: String): Boolean {
            val nodeObj = allAxNodesMap[nodeId]?.jsonObject ?: return false
            val isIgnored = nodeObj["ignored"]?.jsonPrimitive?.booleanOrNull == true
            val backendId = nodeObj["backendDOMNodeId"]?.jsonPrimitive?.intOrNull
            return !isIgnored && backendId != null
        }

        val resolvedChildrenMap = mutableMapOf<String, MutableList<String>>()
        for (node in axNodes) {
            val nodeId = node.jsonObject["nodeId"]?.jsonPrimitive?.contentOrNull ?: continue
            if (!isValidSemNode(nodeId)) continue

            var ancestorId = childToParentMap[nodeId]
            var steps = 0
            while (ancestorId != null && !isValidSemNode(ancestorId) && steps < 200) {
                ancestorId = childToParentMap[ancestorId]
                steps++
            }

            if (ancestorId != null && isValidSemNode(ancestorId)) {
                resolvedChildrenMap.getOrPut(ancestorId) { mutableListOf() }.add(nodeId)
            }
        }

        val semNodes = mutableListOf<SemNode>()
        for (node in axNodes) {
            val obj = node.jsonObject
            if (obj["ignored"]?.jsonPrimitive?.booleanOrNull == true) continue
            val backendNodeId = obj["backendDOMNodeId"]?.jsonPrimitive?.intOrNull ?: continue
            val role = obj["role"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull ?: ""
            val name = obj["name"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull ?: ""
            val nodeId = obj["nodeId"]?.jsonPrimitive?.contentOrNull ?: ""
            val resolvedChildIds = resolvedChildrenMap[nodeId] ?: emptyList()
            semNodes.add(SemNode(nodeId, backendNodeId, role, name, resolvedChildIds))
        }

        if (semNodes.isEmpty()) return AxTreeData(
            url = pageInfo.url, innerWidth = pageInfo.innerWidth,
            innerHeight = pageInfo.innerHeight
        )

        val resultNodes = mutableListOf<AxNode>()

        val selectorFn = xyz.kbrowser.webview.JsScripts.BUILD_SELECTOR_CALL_FN

        val backendIdToRefid = semNodes.associate { it.backendNodeId to "r${it.backendNodeId}" }
        val nodeIdToRefid = semNodes.associate { it.nodeId to "r${it.backendNodeId}" }

        for (batch in semNodes.chunked(BATCH_SIZE)) {
            val boxFutures = batch.map { sem ->
                sem to devTools.executeDevToolsMethod(
                    "DOM.getBoxModel",
                    """{"backendNodeId":${sem.backendNodeId}}"""
                )
            }
            val describeFutures = batch.map { sem ->
                sem to devTools.executeDevToolsMethod(
                    "DOM.describeNode",
                    """{"backendNodeId":${sem.backendNodeId}}"""
                )
            }
            val resolveFutures = batch.map { sem ->
                sem to devTools.executeDevToolsMethod(
                    "DOM.resolveNode",
                    """{"backendNodeId":${sem.backendNodeId}}"""
                )
            }

            for (i in batch.indices) {
                val (sem, boxFuture) = boxFutures[i]
                val (_, describeFuture) = describeFutures[i]
                val (_, resolveFuture) = resolveFutures[i]

                val boxJson = try {
                    boxFuture.get(BOX_TIMEOUT_SEC, TimeUnit.SECONDS)
                } catch (e: Exception) { continue } ?: continue

                val boxRoot = Json.parseToJsonElement(boxJson).jsonObject
                val model = boxRoot["model"]?.jsonObject
                    ?: boxRoot["result"]?.jsonObject?.get("model")?.jsonObject
                    ?: boxRoot["result"]?.jsonObject?.get("result")?.jsonObject?.get("model")?.jsonObject
                    ?: continue

                val content = model["content"]?.jsonArray ?: continue
                if (content.size < 8) continue

                val x = content[0].jsonPrimitive.double.toInt()
                val y = content[1].jsonPrimitive.double.toInt()
                val w = (content[4].jsonPrimitive.double - content[0].jsonPrimitive.double).toInt()
                val h = (content[5].jsonPrimitive.double - content[1].jsonPrimitive.double).toInt()
                val isVisible = w > 0 && h > 0

                val meta = parseDescribeNodeResponse(describeFuture)

                val selector: String = try {
                    val resolveJson = resolveFuture.get(BOX_TIMEOUT_SEC, TimeUnit.SECONDS)
                    val objectId = if (resolveJson != null) {
                        val r = Json.parseToJsonElement(resolveJson).jsonObject
                        r["object"]?.jsonObject?.get("objectId")?.jsonPrimitive?.content
                            ?: r["result"]?.jsonObject?.get("object")?.jsonObject?.get("objectId")?.jsonPrimitive?.content
                            ?: r["result"]?.jsonObject?.get("result")?.jsonObject?.get("object")?.jsonObject?.get("objectId")?.jsonPrimitive?.content
                    } else null

                    if (objectId != null) {
                        val escapedObjId = Json.encodeToString(JsonPrimitive(objectId))
                        val callJson = devTools.executeDevToolsMethod(
                            "Runtime.callFunctionOn",
                            """{"objectId":$escapedObjId,"functionDeclaration":${Json.encodeToString(JsonPrimitive(selectorFn))},"returnByValue":true}"""
                        ).get(BOX_TIMEOUT_SEC, TimeUnit.SECONDS)
                        if (callJson != null) {
                            val cr = Json.parseToJsonElement(callJson).jsonObject
                            cr["result"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                                ?: cr["result"]?.jsonObject?.get("result")?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                                ?: ""
                        } else ""
                    } else ""
                } catch (_: Exception) { "" }

                // Occlusion is checked only for interactive nodes; containers are skipped,
                // since being covered by a child at their center point is normal.
                val interactiveRoles = setOf("button", "link", "checkbox", "radio", "textbox",
                    "combobox", "menuitem", "tab", "option", "slider", "spinbutton")
                val interactiveTags = setOf("a", "button", "input", "select", "textarea", "label")
                val isInteractive = sem.role.lowercase() in interactiveRoles ||
                    meta.tagName.lowercase() in interactiveTags ||
                    meta.attributes.containsKey("onclick") ||
                    meta.attributes.containsKey("tabindex")
                val occludedBy: String? = if (isVisible && isInteractive) {
                    val viewportCx = x + w / 2 - pageInfo.scrollX
                    val viewportCy = y + h / 2 - pageInfo.scrollY
                    try {
                        val locJson = devTools.executeDevToolsMethod(
                            "DOM.getNodeForLocation",
                            """{"x":$viewportCx,"y":$viewportCy,"includeUserAgentShadowDOM":false}"""
                        ).get(BOX_TIMEOUT_SEC, TimeUnit.SECONDS)
                        if (locJson != null) {
                            val locRoot = Json.parseToJsonElement(locJson).jsonObject
                            val topBackendId = locRoot["backendNodeId"]?.jsonPrimitive?.intOrNull
                                ?: locRoot["result"]?.jsonObject?.get("backendNodeId")?.jsonPrimitive?.intOrNull
                                ?: locRoot["result"]?.jsonObject?.get("result")?.jsonObject?.get("backendNodeId")?.jsonPrimitive?.intOrNull
                            if (topBackendId != null && topBackendId != sem.backendNodeId) {
                                backendIdToRefid[topBackendId] ?: "r$topBackendId"
                            } else null
                        } else null
                    } catch (_: Exception) { null }
                } else null

                // childIds reference AX nodeIds; drop the ones with no mapping
                // (ignored nodes, which are not in the result set)
                val childRefids = sem.childIds.mapNotNull { cid -> nodeIdToRefid[cid] }

                resultNodes.add(
                    AxNode(
                        refid      = "r${sem.backendNodeId}",
                        tagName    = meta.tagName,
                        role       = sem.role,
                        id         = meta.id,
                        className  = meta.className,
                        text       = sem.name,
                        isVisible  = isVisible,
                        x          = x,
                        y          = y,
                        width      = w,
                        height     = h,
                        centerX    = x + w / 2,
                        centerY    = y + h / 2,
                        childCount = childRefids.size,
                        attributes = meta.attributes,
                        selector   = selector,
                        occludedBy = occludedBy,
                        nodeId     = sem.nodeId,
                        childIds   = childRefids
                    )
                )
            }
        }

        return AxTreeData(
            url             = pageInfo.url,
            innerWidth      = pageInfo.innerWidth,
            innerHeight     = pageInfo.innerHeight,
            scrollX         = pageInfo.scrollX,
            scrollY         = pageInfo.scrollY,
            documentWidth   = pageInfo.docWidth,
            documentHeight  = pageInfo.docHeight,
            devicePixelRatio = pageInfo.dpr,
            totalElements   = resultNodes.size,
            visibleElements = resultNodes.count { it.isVisible },
            hiddenElements  = resultNodes.count { !it.isVisible },
            iframeCount     = pageInfo.iframeCount,
            nodes           = resultNodes
        )
    }

    private data class NodeMeta(
        val tagName: String,
        val id: String,
        val className: String,
        val attributes: Map<String, String>
    )

    private val EMPTY_META = NodeMeta(tagName = "", id = "", className = "", attributes = emptyMap())

    /**
     * Parses a DOM.describeNode response into tagName/id/className/attributes.
     * Returns empty metadata on failure (the node is not skipped).
     *
     * The response nests inconsistently, so try all three layouts:
     * {"node":{...}} / {"result":{"node":{...}}} / {"result":{"result":{"node":{...}}}}
     */
    private fun parseDescribeNodeResponse(future: java.util.concurrent.CompletableFuture<String>): NodeMeta {
        val json = try {
            future.get(BOX_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: Exception) { return EMPTY_META } ?: return EMPTY_META

        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            val node = root["node"]?.jsonObject
                ?: root["result"]?.jsonObject?.get("node")?.jsonObject
                ?: root["result"]?.jsonObject?.get("result")?.jsonObject?.get("node")?.jsonObject
                ?: return EMPTY_META

            val tagName = node["nodeName"]?.jsonPrimitive?.content?.lowercase() ?: ""

            // attributes is a flat alternating array: [name, value, name, value, ...]
            val attrsArray = node["attributes"]?.jsonArray
            var id = ""
            var className = ""
            val otherAttrs = mutableMapOf<String, String>()

            if (attrsArray != null) {
                var i = 0
                while (i + 1 < attrsArray.size) {
                    val attrName = attrsArray[i].jsonPrimitive.content
                    val attrValue = attrsArray[i + 1].jsonPrimitive.content
                    when (attrName) {
                        "id" -> id = attrValue
                        "class" -> className = attrValue
                        else -> otherAttrs[attrName] = attrValue
                    }
                    i += 2
                }
            }

            NodeMeta(tagName = tagName, id = id, className = className, attributes = otherAttrs)
        } catch (e: Exception) {
            EMPTY_META
        }
    }

    /**
     * Waits until the RemoteBrowser native peer is created in the cef_server process.
     * Returns true immediately for non-remote (local) browsers, false on timeout.
     */
    private fun waitForNativeBrowser(browser: org.cef.browser.CefBrowser): Boolean {
        return CefNativeReadyLatch.awaitBlocking(browser, TIMEOUT_SEC)
    }

    private data class PageInfo(
        val url: String, val innerWidth: Int, val innerHeight: Int,
        val scrollX: Int, val scrollY: Int,
        val docWidth: Int, val docHeight: Int,
        val dpr: Double, val iframeCount: Int
    )

    /**
     * Fetches page layout info via CDP Page.getLayoutMetrics — pure CDP, no JavaScript.
     * 5s timeout; returns null on failure so the caller can fall back to fetchPageInfoViaJs.
     */
    private fun fetchPageInfoViaCDP(devTools: org.cef.browser.CefDevToolsClient, browser: org.cef.browser.CefBrowser? = null): PageInfo? {
        val json = try {
            devTools.executeDevToolsMethod("Page.getLayoutMetrics", "{}")
                .get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return null
        } ?: return null

        val root = Json.parseToJsonElement(json).jsonObject

        val cssLayoutViewport = root["cssLayoutViewport"]?.jsonObject
            ?: root["result"]?.jsonObject?.get("cssLayoutViewport")?.jsonObject
            ?: root["result"]?.jsonObject?.get("result")?.jsonObject?.get("cssLayoutViewport")?.jsonObject
            ?: return null

        val cssContentSize = root["cssContentSize"]?.jsonObject
            ?: root["result"]?.jsonObject?.get("cssContentSize")?.jsonObject
            ?: root["result"]?.jsonObject?.get("result")?.jsonObject?.get("cssContentSize")?.jsonObject

        // DPR = layoutViewport.clientWidth / cssLayoutViewport.clientWidth
        // (the scale field is page zoom, not the device pixel ratio)
        val layoutViewport = root["layoutViewport"]?.jsonObject
            ?: root["result"]?.jsonObject?.get("layoutViewport")?.jsonObject
            ?: root["result"]?.jsonObject?.get("result")?.jsonObject?.get("layoutViewport")?.jsonObject

        val physW = layoutViewport?.get("clientWidth")?.jsonPrimitive?.double
        val cssW  = cssLayoutViewport["clientWidth"]?.jsonPrimitive?.double
        val dpr = if (physW != null && cssW != null && cssW > 0) physW / cssW else 1.0

        // Page.getLayoutMetrics does not return a URL, so read it from browser.url
        val url = try { browser?.url ?: "" } catch (_: Exception) { "" }

        return PageInfo(
            url         = url,
            innerWidth  = cssLayoutViewport["clientWidth"]?.jsonPrimitive?.int ?: 0,
            innerHeight = cssLayoutViewport["clientHeight"]?.jsonPrimitive?.int ?: 0,
            scrollX     = cssLayoutViewport["pageX"]?.jsonPrimitive?.int ?: 0,
            scrollY     = cssLayoutViewport["pageY"]?.jsonPrimitive?.int ?: 0,
            docWidth    = cssContentSize?.get("contentWidth")?.jsonPrimitive?.int ?: 0,
            docHeight   = cssContentSize?.get("contentHeight")?.jsonPrimitive?.int ?: 0,
            dpr         = dpr,
            iframeCount = 0  // Page.getLayoutMetrics does not report iframe counts
        )
    }

    private fun fetchPageInfoViaJs(devTools: org.cef.browser.CefDevToolsClient): PageInfo? {
        val expr = """JSON.stringify({
            iw:window.innerWidth, ih:window.innerHeight,
            sx:Math.round(window.scrollX), sy:Math.round(window.scrollY),
            dw:document.documentElement.scrollWidth,
            dh:document.documentElement.scrollHeight,
            dpr:window.devicePixelRatio||1,
            ifc:document.querySelectorAll('iframe').length,
            url:window.location.href
        })""".trimIndent().replace("\n", " ")

        val json = try {
            val future = devTools.executeDevToolsMethod(
                "Runtime.evaluate",
                """{"expression":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(expr))},"returnByValue":true}"""
            )
            future.get(TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            return null
        } catch (e: Exception) {
            return null
        } ?: run {
            return null
        }

        val parsed = Json.parseToJsonElement(json).jsonObject
        val resultObj = parsed["result"]?.jsonObject
        val str = parsed["value"]?.jsonPrimitive?.content
            ?: resultObj?.get("value")?.jsonPrimitive?.content
            ?: resultObj?.get("result")?.jsonObject?.get("value")?.jsonPrimitive?.content
        if (str == null) {
            return null
        }
        val o = Json.parseToJsonElement(str).jsonObject
        return PageInfo(
            url         = o["url"]?.jsonPrimitive?.content ?: "",
            innerWidth  = o["iw"]?.jsonPrimitive?.int ?: 0,
            innerHeight = o["ih"]?.jsonPrimitive?.int ?: 0,
            scrollX     = o["sx"]?.jsonPrimitive?.int ?: 0,
            scrollY     = o["sy"]?.jsonPrimitive?.int ?: 0,
            docWidth    = o["dw"]?.jsonPrimitive?.int ?: 0,
            docHeight   = o["dh"]?.jsonPrimitive?.int ?: 0,
            dpr         = o["dpr"]?.jsonPrimitive?.double ?: 1.0,
            iframeCount = o["ifc"]?.jsonPrimitive?.int ?: 0
        )
    }

}
