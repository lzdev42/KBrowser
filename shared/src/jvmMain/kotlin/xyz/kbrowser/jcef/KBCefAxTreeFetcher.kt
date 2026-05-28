package xyz.kbrowser.jcef

import kotlinx.serialization.json.*
import xyz.kbrowser.webview.AxNode
import xyz.kbrowser.webview.AxTreeData
import java.util.concurrent.TimeUnit

/**
 * 通过 CDP 获取完整的 Accessibility 树 + 节点坐标。
 *
 * 策略：
 * 1. CDP Accessibility.getFullAXTree  → 语义信息（role / name / backendDOMNodeId）
 * 2. CDP Runtime.evaluate 一次调用    → 批量获取所有节点的 getBoundingClientRect 坐标
 *    （用 backendDOMNodeId 作为 key，通过 DOM.resolveNode 拿到 objectId，
 *     再用 Runtime.callFunctionOn 批量查询）
 *
 * 完全不注入持久化 JS，不受 CSP 限制，不污染页面状态。
 */
object KBCefAxTreeFetcher {

    private const val TIMEOUT_SEC = 15L
    private const val BOX_TIMEOUT_SEC = 5L
    private const val BATCH_SIZE = 80

    fun fetch(browser: org.cef.browser.CefBrowser): AxTreeData {
        // ── 0. RemoteBrowser: 等待 native browser 创建完成 ─────────────────
        // 在 JBR 远程模式下，CefBrowser 是 RemoteBrowser，其 native peer 在
        // cef_server 进程中异步创建。必须等待 isNativeBrowserCreated() == true，
        // 否则 addDevToolsMessageObserver 返回 null，导致 CefDevToolsClient
        // 处于 "closed" 状态，所有 CDP 调用立即失败。
        if (!waitForNativeBrowser(browser)) {
            return AxTreeData()
        }

        val devTools = browser.devToolsClient
        if (devTools == null || devTools.isClosed) {
            return AxTreeData()
        }

        // ── 1. 页面基础信息 ────────────────────────────────────────────────
        val pageInfo = fetchPageInfoViaCDP(devTools, browser) ?: fetchPageInfoViaJs(devTools)
        if (pageInfo == null) {
            return AxTreeData()
        }

        // ── 2. CDP AX 树（语义） ───────────────────────────────────────────
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
        // 自适应三种格式：
        //   Remote 模式: {"nodes":[...]}  (顶层直接是 nodes)
        //   本地模式:    {"result":{"nodes":[...]}}  (一层 result)
        //   可能的:      {"result":{"result":{"nodes":[...]}}}  (两层 result)
        val axNodes = axRoot["nodes"]?.jsonArray
            ?: axRoot["result"]?.jsonObject?.get("nodes")?.jsonArray
            ?: axRoot["result"]?.jsonObject?.get("result")?.jsonObject?.get("nodes")?.jsonArray
            ?: return AxTreeData()

        // ── 3. 收集有效节点（有 backendDOMNodeId、非 ignored） ──
        // 注意：不过滤 role 为空的节点，普通 div/span/p 等容器元素 role 可能为空或 "none"，
        // 但它们仍然是有效的 DOM 节点，需要包含在结果中。
        data class SemNode(
            val backendNodeId: Int,
            val role: String,
            val name: String
        )
        val semNodes = mutableListOf<SemNode>()
        for (node in axNodes) {
            val obj = node.jsonObject
            if (obj["ignored"]?.jsonPrimitive?.boolean == true) continue
            val backendNodeId = obj["backendDOMNodeId"]?.jsonPrimitive?.int ?: continue
            val role = obj["role"]?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""
            val name = obj["name"]?.jsonObject?.get("value")?.jsonPrimitive?.content ?: ""
            semNodes.add(SemNode(backendNodeId, role, name))
        }

        if (semNodes.isEmpty()) return AxTreeData(
            url = pageInfo.url, innerWidth = pageInfo.innerWidth,
            innerHeight = pageInfo.innerHeight
        )

        // ── 4. 批量获取坐标 + 元数据 (DOM.getBoxModel + DOM.describeNode, 纯 CDP 无 JS) ──
        val resultNodes = mutableListOf<AxNode>()
        var refCounter = 0

        for (batch in semNodes.chunked(BATCH_SIZE)) {
            // 并发发送本批所有 DOM.getBoxModel 和 DOM.describeNode 请求
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

            // 逐个等待结果，合并坐标和元数据
            for (i in batch.indices) {
                val (sem, boxFuture) = boxFutures[i]
                val (_, describeFuture) = describeFutures[i]

                // 获取坐标（必须成功，否则跳过节点）
                val boxJson = try {
                    boxFuture.get(BOX_TIMEOUT_SEC, TimeUnit.SECONDS)
                } catch (e: Exception) { continue } ?: continue

                val boxRoot = Json.parseToJsonElement(boxJson).jsonObject
                // 三层 fallback 解析
                val model = boxRoot["model"]?.jsonObject
                    ?: boxRoot["result"]?.jsonObject?.get("model")?.jsonObject
                    ?: boxRoot["result"]?.jsonObject?.get("result")?.jsonObject?.get("model")?.jsonObject
                    ?: continue

                // content quad: [x1,y1, x2,y2, x3,y3, x4,y4]
                val content = model["content"]?.jsonArray ?: continue
                if (content.size < 8) continue

                val x = content[0].jsonPrimitive.double.toInt()
                val y = content[1].jsonPrimitive.double.toInt()
                val w = (content[4].jsonPrimitive.double - content[0].jsonPrimitive.double).toInt()
                val h = (content[5].jsonPrimitive.double - content[1].jsonPrimitive.double).toInt()

                // 获取元数据（失败时使用空元数据，不跳过节点）
                val meta = parseDescribeNodeResponse(describeFuture)

                resultNodes.add(
                    AxNode(
                        refid     = "r${refCounter++}",
                        tagName   = meta.tagName,
                        role      = sem.role,
                        id        = meta.id,
                        className = meta.className,
                        text      = sem.name,
                        isVisible = w > 0 && h > 0,
                        x         = x,
                        y         = y,
                        width     = w,
                        height    = h,
                        centerX   = x + w / 2,
                        centerY   = y + h / 2,
                        childCount = 0,
                        attributes = meta.attributes
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

    // ── DOM.describeNode 响应解析 ──────────────────────────────────────────

    private data class NodeMeta(
        val tagName: String,
        val id: String,
        val className: String,
        val attributes: Map<String, String>
    )

    private val EMPTY_META = NodeMeta(tagName = "", id = "", className = "", attributes = emptyMap())

    /**
     * 解析 DOM.describeNode 的 CompletableFuture 响应，提取 tagName、id、className 和 attributes。
     * 失败时返回空元数据（不跳过节点）。
     *
     * 响应格式三层 fallback：
     *   {"node": {...}}  OR  {"result": {"node": {...}}}  OR  {"result": {"result": {"node": {...}}}}
     */
    private fun parseDescribeNodeResponse(future: java.util.concurrent.CompletableFuture<String>): NodeMeta {
        val json = try {
            future.get(BOX_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: Exception) { return EMPTY_META } ?: return EMPTY_META

        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            // 三层 fallback 解析 node 对象
            val node = root["node"]?.jsonObject
                ?: root["result"]?.jsonObject?.get("node")?.jsonObject
                ?: root["result"]?.jsonObject?.get("result")?.jsonObject?.get("node")?.jsonObject
                ?: return EMPTY_META

            // nodeName → tagName（转小写）
            val tagName = node["nodeName"]?.jsonPrimitive?.content?.lowercase() ?: ""

            // attributes 数组：[name, value, name, value, ...] 交替格式
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

    // ── 内部数据类 ─────────────────────────────────────────────────────────

    /**
     * 等待 RemoteBrowser 的 native peer 在 cef_server 中创建完成。
     * 对于非 RemoteBrowser（本地模式），直接返回 true。
     * 超时后返回 false。
     */
    private fun waitForNativeBrowser(browser: org.cef.browser.CefBrowser): Boolean {
        // 尝试通过反射调用 isNativeBrowserCreated()
        val isNativeCreatedMethod = try {
            browser.javaClass.getMethod("isNativeBrowserCreated").also {
                it.isAccessible = true
            }
        } catch (e: NoSuchMethodException) {
            // 不是 RemoteBrowser，本地模式直接 OK
            return true
        } catch (e: Exception) {
            // 其他异常（SecurityException 等），假设 ready
            return true
        }

        val deadlineMs = System.currentTimeMillis() + TIMEOUT_SEC * 1000
        var lastLog = 0L
        while (System.currentTimeMillis() < deadlineMs) {
            val ready = try {
                isNativeCreatedMethod.invoke(browser) as? Boolean ?: return true
            } catch (e: Exception) {
                // 反射调用失败（模块访问限制等），假设 ready
                return true
            }
            if (ready) {
                return true
            }
            val now = System.currentTimeMillis()
            if (now - lastLog > 500) {
                lastLog = now
            }
            Thread.sleep(50)
        }
        return false
    }

    private data class PageInfo(
        val url: String, val innerWidth: Int, val innerHeight: Int,
        val scrollX: Int, val scrollY: Int,
        val docWidth: Int, val docHeight: Int,
        val dpr: Double, val iframeCount: Int
    )

    // ── 辅助函数 ───────────────────────────────────────────────────────────

    /**
     * 通过 CDP Page.getLayoutMetrics 获取页面布局信息。
     * 不执行任何 JavaScript，纯 CDP 原生调用。
     * 5 秒超时，失败返回 null（调用方可 fallback 到 fetchPageInfoViaJs）。
     */
    private fun fetchPageInfoViaCDP(devTools: org.cef.browser.CefDevToolsClient, browser: org.cef.browser.CefBrowser? = null): PageInfo? {
        val json = try {
            devTools.executeDevToolsMethod("Page.getLayoutMetrics", "{}")
                .get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return null
        } ?: return null

        val root = Json.parseToJsonElement(json).jsonObject

        // 三层 fallback 解析：顶层 / result / result.result
        val cssLayoutViewport = root["cssLayoutViewport"]?.jsonObject
            ?: root["result"]?.jsonObject?.get("cssLayoutViewport")?.jsonObject
            ?: root["result"]?.jsonObject?.get("result")?.jsonObject?.get("cssLayoutViewport")?.jsonObject
            ?: return null

        val cssContentSize = root["cssContentSize"]?.jsonObject
            ?: root["result"]?.jsonObject?.get("cssContentSize")?.jsonObject
            ?: root["result"]?.jsonObject?.get("result")?.jsonObject?.get("cssContentSize")?.jsonObject

        val cssVisualViewport = root["cssVisualViewport"]?.jsonObject
            ?: root["result"]?.jsonObject?.get("cssVisualViewport")?.jsonObject
            ?: root["result"]?.jsonObject?.get("result")?.jsonObject?.get("cssVisualViewport")?.jsonObject

        // 从 cssVisualViewport.scale 提取 DPR，缺失时默认 1.0
        val dpr = cssVisualViewport?.get("scale")?.jsonPrimitive?.double ?: 1.0

        // URL 从 browser.url 获取（Page.getLayoutMetrics 不返回 URL）
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
            iframeCount = 0  // Page.getLayoutMetrics 不提供 iframe 计数，后续可通过其他方式补充
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
        // 自适应：顶层 value / 一层 result.value / 两层 result.result.value
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
