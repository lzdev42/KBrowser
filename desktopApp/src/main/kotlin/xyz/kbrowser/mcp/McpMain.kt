package xyz.kbrowser.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.kbrowser.toolcall.BrowserToolSession
import xyz.kbrowser.toolcall.BrowserTools
import xyz.kbrowser.toolcall.ToolContent
import xyz.kbrowser.toolcall.ToolResult
import xyz.kbrowser.toolcall.ToolTabInfo
import xyz.kbrowser.webview.KBPage
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * KBrowser Demo — MCP mode (DEBUG BACKDOOR, TRANSPORT ONLY).
 *
 * ⚠️ 定位：开发调试工具，不是 KBrowser 的功能。宿主接入 AI 工具请直接内嵌
 *    xyz.kbrowser.toolcall.BrowserTools（进程内调用），不要走 MCP。
 *
 * 这里唯一的用途：把 demo 进程挂到外部 AI 助手（opencode / Claude）上，让 AI 真实
 * 驱动一个浏览器跑 agent 循环来调试 KBrowser / 验证工具设计。MCP 客户端直接拉起本进程。
 *
 * All tool semantics live in the KBrowser library (xyz.kbrowser.toolcall.BrowserTools);
 * this file only provides:
 *   1. the stdio JSON-RPC transport (newline-delimited, protocol 2025-06-18)
 *   2. a demo BrowserToolSession implementation (tab lifecycle + ownership policy)
 *
 * The browser page is headless (fixed viewport, no UI).
 */
private const val PROTOCOL_VERSION = "2025-06-18"
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
private const val VIEWPORT_W = 1280
private const val VIEWPORT_H = 720

fun main() = runBlocking {
    // 1. Protocol channel first, THEN route all other stdout noise (JCEF, CEF,
    //    coroutine logs) to stderr — it must never touch the protocol stream.
    val protocolOut = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8")
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err)))

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 2. Init engine + one headless page + the tool layer backed by our session.
    val session = DemoTabSession(scope)
    val tools = BrowserTools(session)

    scope.launch(Dispatchers.Main) {
        try {
            val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
            KBrowser.initializeConfig(storageDir, useOsr = true)
            initializeKBrowser()
            session.openInitialTab()
            session.onLog("page ready")
        } catch (t: Throwable) {
            session.onLog("FATAL init failed: ${t.message}")
            exitProcess(1)
        }
    }
    // 3. stdio loop: newline-delimited JSON-RPC 2.0.
    val reader = java.io.BufferedReader(java.io.InputStreamReader(System.`in`, Charsets.UTF_8))
    for (line in reader.lines()) {
        if (line.isBlank()) continue
        val response = McpTransport.handle(line, tools)
        if (response != null) {
            protocolOut.write((response + "\n").toByteArray(Charsets.UTF_8))
            protocolOut.flush()
        }
    }
    scope.cancel()
}

/**
 * Demo tab policy: every tab is owned by the session, new-window requests become
 * managed tabs, closing respects the last-tab rule, "active" = most recently used.
 * (An embedding host can back this interface with its own ownership system.)
 */
private class DemoTabSession(private val appScope: CoroutineScope) : BrowserToolSession {
    private val tabMutex = Mutex()
    private val tabs = ConcurrentHashMap<String, KBPage>()
    @Volatile private var activeId: String? = null
    @Volatile private var lastActiveAt: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    override fun onLog(msg: String) {
        System.err.println("[kbrowser-mcp] $msg")
    }

    suspend fun openInitialTab() {
        register(KBrowser.newPage(viewportWidth = VIEWPORT_W, viewportHeight = VIEWPORT_H))
            .debug.enable() // intercepts JS dialogs + captures console/network for observe
    }

    private fun register(page: KBPage): KBPage {
        tabs[page.uuid] = page
        lastActiveAt[page.uuid] = System.currentTimeMillis()
        activeId = page.uuid
        page.onNewPage = { url ->
            onLog("new window request: $url -> opening managed tab")
            appScope.launch(Dispatchers.Main) {
                try { newTab(url) } catch (t: Throwable) { onLog("newTab($url) failed: ${t.message}") }
            }
        }
        return page
    }

    override fun resolve(pageId: String?): KBPage {
        val id = pageId ?: activeId
            ?: throw IllegalStateException("no page ready yet, retry in a few seconds")
        val page = tabs[id]
            ?: throw IllegalArgumentException("unknown pageId '$id'; use browser_tabs action=list to see open tabs")
        lastActiveAt[id] = System.currentTimeMillis()
        activeId = id
        return page
    }

    override suspend fun newTab(url: String?): KBPage {
        val page = tabMutex.withLock {
            withContext(Dispatchers.Main) {
                KBrowser.newPage(viewportWidth = VIEWPORT_W, viewportHeight = VIEWPORT_H)
            }.also { p ->
                p.debug.enable()
                register(p)
            }
        }
        if (!url.isNullOrBlank()) page.loadUrl(url)
        onLog("tab opened: ${page.uuid} url=$url")
        return page
    }

    override suspend fun closeTab(pageId: String?): String {
        val pid = pageId ?: throw IllegalArgumentException("pageId is required to close a tab")
        val page = tabMutex.withLock {
            val p = tabs[pid] ?: throw IllegalArgumentException("unknown pageId '$pid'; use browser_tabs action=list")
            if (tabs.size == 1) throw IllegalStateException("refusing to close the last remaining tab")
            tabs.remove(pid)
            if (activeId == pid) {
                activeId = tabs.entries.maxByOrNull { lastActiveAt[it.key] ?: 0 }?.key
            }
            p
        }
        withContext(Dispatchers.Main) { page.close() }
        onLog("tab closed: $pid (${tabs.size} remaining)")
        return pid
    }

    override suspend fun closeOthers(): List<String> {
        val victims = tabMutex.withLock {
            val keep = activeId
            val v = tabs.entries.filter { it.key != keep }.map { it.key to it.value }
            v.forEach { (id, _) -> tabs.remove(id) }
            v
        }
        victims.forEach { (_, p) -> withContext(Dispatchers.Main) { p.close() } }
        if (victims.isNotEmpty()) onLog("closed ${victims.size} unused tab(s), kept $activeId")
        return victims.map { it.first }
    }

    override fun listTabs(): List<ToolTabInfo> = tabs.map { (id, p) ->
        ToolTabInfo(
            pageId = id,
            url = p.webView.currentUrl.value ?: "",
            title = p.webView.currentTitle.value ?: "",
            active = id == activeId,
            idleMs = System.currentTimeMillis() - (lastActiveAt[id] ?: 0)
        )
    }
}

/** Pure JSON-RPC transport: no business logic. */
object McpTransport {
    private val callMutex = Mutex() // serializes tool calls: KBPage ops are not safe to interleave

    /** Returns null for notifications (no id), a JSON string response otherwise. */
    suspend fun handle(line: String, tools: BrowserTools): String? {
        val msg = try { json.parseToJsonElement(line).jsonObject } catch (t: Throwable) {
            return error(null, -32700, "parse error: ${t.message}")
        }
        val id = msg["id"]
        val method = msg["method"]?.jsonPrimitive?.content
        val params = msg["params"]?.jsonObject ?: JsonObject(emptyMap())

        // MCP host may answer requests we never send; ignore silently.
        if (id == null) return null

        val result: kotlinx.serialization.json.JsonElement = try {
            when (method) {
                "initialize" -> buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") { put("name", "kbrowser"); put("version", "0.2.0") }
                }
                "notifications/initialized" -> return null // notification, no response
                "ping" -> JsonObject(emptyMap())
                "tools/list" -> buildJsonObject {
                    put("tools", kotlinx.serialization.json.buildJsonArray {
                        tools.listSpecs().forEach { s ->
                            add(buildJsonObject {
                                put("name", s.name)
                                put("description", s.description)
                                put("inputSchema", s.inputSchema)
                            })
                        }
                    })
                }
                "tools/call" -> {
                    val name = params["name"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("name is required")
                    val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                    val r: ToolResult = callMutex.withLock { tools.call(name, args) } // serialize tool calls
                    buildJsonObject {
                        put("content", kotlinx.serialization.json.buildJsonArray {
                            r.content.forEach { c ->
                                add(buildJsonObject {
                                    put("type", when (c) { is ToolContent.Text -> "text"; is ToolContent.Image -> "image" })
                                    when (c) {
                                        is ToolContent.Text -> put("text", c.text)
                                        is ToolContent.Image -> { put("data", c.data); put("mimeType", c.mimeType) }
                                    }
                                })
                            }
                        })
                        put("isError", r.isError)
                    }
                }
                else -> return error(id, -32601, "unknown method: $method")
            }
        } catch (t: Throwable) {
            return error(id, -32000, (t.message ?: t.toString()).take(500))
        }

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.let { json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it) }
    }

    private fun error(id: kotlinx.serialization.json.JsonElement?, code: Int, message: String): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            id?.let { put("id", it) }
            putJsonObject("error") { put("code", code); put("message", message) }
        }.let { json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it) }
}
