package xyz.kbrowser.webview.debug

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.cef.browser.CefBrowser
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import xyz.kbrowser.jcef.KBCefClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM implementation of [KBDebug], backed by CefDevToolsClient (CDP).
 *
 * All CDP access is in-process (no remote debugging port needed).
 * Setup is asynchronous — [enable] starts a background thread that waits for
 * native browser creation before attaching listeners.
 */
internal class KBDebugJvm(
    private val cefBrowser: CefBrowser,
    private val cefClient: KBCefClient
) : KBDebug {

    private val enabledFlag = AtomicBoolean(false)
    @Volatile private var devTools: org.cef.browser.CefDevToolsClient? = null
    @Volatile private var crashedRecently = false

    // ── Event stream (ring buffer 500, drop oldest) ──────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _events = MutableSharedFlow<DebugEvent>(
        replay = 500,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<DebugEvent> = _events.asSharedFlow()

    // ── Network request lifecycle tracking ───────────────────────────────

    private data class NetBuilder(
        val requestId: String,
        val url: String,
        val method: String,
        var status: Int? = null,
        var mimeType: String? = null,
        var resourceType: String? = null,
        var encodedDataLength: Long? = null,
        var failed: Boolean = false,
        var errorText: String? = null,
        val timestamp: Long
    )

    private val networkRequests = ConcurrentHashMap<String, NetBuilder>()

    // ── Raw CDP event listeners (escape hatch) ───────────────────────────

    private val cdpEventListeners = CopyOnWriteArrayList<(String, String) -> Unit>()

    // ── Crash recovery handler ──────────────────────────────────────────

    private val crashHandler = object : CefRequestHandlerAdapter() {
        override fun onRenderProcessTerminated(
            browser: CefBrowser,
            status: CefRequestHandler.TerminationStatus,
            errorCode: Int,
            errorString: String?
        ) {
            crashedRecently = true
            _events.tryEmit(DebugEvent.RenderCrash(
                timestamp = System.currentTimeMillis(),
                status = status.name,
                errorCode = errorCode,
                errorString = errorString
            ))
            javax.swing.SwingUtilities.invokeLater { browser.reload() }
        }
    }

    // ── enable / disable ────────────────────────────────────────────────

    override fun enable() {
        if (!enabledFlag.compareAndSet(false, true)) return
        Thread({
            try {
                waitForNativeBrowserCreated(cefBrowser)
                if (!enabledFlag.get()) return@Thread
                val dt = cefBrowser.devToolsClient ?: return@Thread
                if (dt.isClosed) return@Thread
                devTools = dt

                for (domain in listOf("Runtime", "Network", "Performance")) {
                    try {
                        dt.executeDevToolsMethod("$domain.enable", "{}").get(3, TimeUnit.SECONDS)
                    } catch (_: Exception) {}
                }

                try { cefClient.addRequestHandler(crashHandler, cefBrowser) } catch (_: Exception) {}

                dt.addEventListener { method, paramsJson ->
                    if (!enabledFlag.get()) return@addEventListener
                    cdpEventListeners.forEach { runCatching { it(method, paramsJson) } }
                    runCatching { handleCdpEvent(method, paramsJson) }
                }
            } catch (_: Exception) {}
        }, "KB-Debug-Setup").apply { isDaemon = true }.start()
    }

    override fun disable() {
        if (!enabledFlag.compareAndSet(true, false)) return
        val dt = devTools
        if (dt != null && !dt.isClosed) {
            for (domain in listOf("Runtime", "Network", "Performance")) {
                try { dt.executeDevToolsMethod("$domain.disable", "{}") } catch (_: Exception) {}
            }
        }
        try { cefClient.removeRequestHandler(crashHandler, cefBrowser) } catch (_: Exception) {}
        networkRequests.clear()
        cdpEventListeners.clear()
        @OptIn(ExperimentalCoroutinesApi::class)
        _events.resetReplayCache()
        crashedRecently = false
        devTools = null
    }

    // ── snapshot ────────────────────────────────────────────────────────

    override suspend fun snapshot(): DebugSnapshot {
        val dt = devTools
        val metrics = if (dt != null && !dt.isClosed) fetchMetrics(dt) else null
        val cache = events.replayCache

        val consoleErrors = cache.count { it is DebugEvent.ConsoleLog && it.level == ConsoleLevel.ERROR }
        val jsExceptions = cache.count { it is DebugEvent.JsException }
        val failedReqs = cache.count { it is DebugEvent.NetworkRequest && it.failed }
        val totalReqs = cache.count { it is DebugEvent.NetworkRequest }

        val recentErrors = cache.filter { e ->
            (e is DebugEvent.ConsoleLog && e.level == ConsoleLevel.ERROR) ||
            e is DebugEvent.JsException ||
            (e is DebugEvent.NetworkRequest && e.failed) ||
            e is DebugEvent.RenderCrash
        }.takeLast(20)

        return DebugSnapshot(
            jsHeapUsedSize = metrics?.get("JSHeapUsedSize") ?: 0L,
            jsHeapTotalSize = metrics?.get("JSHeapTotalSize") ?: 0L,
            domNodeCount = (metrics?.get("Nodes") ?: 0L).toInt(),
            jsEventListeners = (metrics?.get("JSEventListeners") ?: 0L).toInt(),
            documents = (metrics?.get("Documents") ?: 0L).toInt(),
            consoleErrorCount = consoleErrors,
            jsExceptionCount = jsExceptions,
            failedRequestCount = failedReqs,
            totalRequestCount = totalReqs,
            crashedRecently = crashedRecently,
            recentErrors = recentErrors
        )
    }

    private suspend fun fetchMetrics(
        dt: org.cef.browser.CefDevToolsClient
    ): Map<String, Long>? {
        return withContext(Dispatchers.IO) {
            try {
                val json = dt.executeDevToolsMethod("Performance.getMetrics", "{}")
                    .get(5, TimeUnit.SECONDS) ?: return@withContext null
                val root = Json.parseToJsonElement(json).jsonObject
                val arr = (root["result"]?.jsonObject?.get("metrics") ?: root["metrics"])
                    ?.jsonArray ?: return@withContext null
                arr.associate {
                    val m = it.jsonObject
                    val name: String = m["name"]?.jsonPrimitive?.content ?: ""
                    val value: Long = m["value"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong() ?: 0L
                    name to value
                }.filterKeys { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }
    }

    // ── getResponseBody ─────────────────────────────────────────────────

    override suspend fun getResponseBody(requestId: String): String? {
        val dt = devTools ?: return null
        if (dt.isClosed) return null
        return withContext(Dispatchers.IO) {
            try {
                val escaped = Json.encodeToString(JsonPrimitive(requestId))
                val result = dt.executeDevToolsMethod(
                    "Network.getResponseBody", """{"requestId":$escaped}"""
                ).get(5, TimeUnit.SECONDS) ?: return@withContext null
                val root = Json.parseToJsonElement(result).jsonObject
                (root["result"]?.jsonObject?.get("body") ?: root["body"])
                    ?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
        }
    }

    // ── executeCdp / onCdpEvent ─────────────────────────────────────────

    override suspend fun executeCdp(method: String, params: String): String? {
        val dt = devTools ?: return null
        if (dt.isClosed) return null
        return withContext(Dispatchers.IO) {
            try {
                dt.executeDevToolsMethod(method, params).get(15, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun onCdpEvent(listener: (method: String, params: String) -> Unit) {
        cdpEventListeners.add(listener)
    }

    // ── CDP event parsing ────────────────────────────────────────────────

    private fun handleCdpEvent(method: String, paramsJson: String) {
        when (method) {
            "Runtime.consoleAPICalled" -> handleConsoleApi(paramsJson)
            "Runtime.exceptionThrown" -> handleException(paramsJson)
            "Network.requestWillBeSent" -> handleNetRequest(paramsJson)
            "Network.responseReceived" -> handleNetResponse(paramsJson)
            "Network.loadingFinished" -> handleNetFinished(paramsJson)
            "Network.loadingFailed" -> handleNetFailed(paramsJson)
        }
    }

    private fun handleConsoleApi(paramsJson: String) {
        val p = Json.parseToJsonElement(paramsJson).jsonObject
        val type = p["type"]?.jsonPrimitive?.content ?: "log"
        val level = when (type) {
            "log" -> ConsoleLevel.LOG
            "info" -> ConsoleLevel.INFO
            "warning" -> ConsoleLevel.WARNING
            "error" -> ConsoleLevel.ERROR
            "debug" -> ConsoleLevel.DEBUG
            else -> ConsoleLevel.LOG
        }
        val text = p["args"]?.jsonArray?.joinToString(" ") { arg ->
            val o = arg.jsonObject
            o["value"]?.jsonPrimitive?.content
                ?: o["description"]?.jsonPrimitive?.content
                ?: o["type"]?.jsonPrimitive?.content
                ?: ""
        } ?: ""

        val frames = p["stackTrace"]?.jsonObject?.get("callFrames")?.jsonArray
        val firstFrame = frames?.firstOrNull()?.jsonObject
        val stackTrace = frames?.joinToString("\n") { f ->
            val fo = f.jsonObject
            "    at ${fo["functionName"]?.jsonPrimitive?.content ?: "<anon>"}" +
                " (${fo["url"]?.jsonPrimitive?.content ?: ""}:${fo["lineNumber"]?.jsonPrimitive?.content ?: 0})"
        }

        _events.tryEmit(DebugEvent.ConsoleLog(
            timestamp = System.currentTimeMillis(),
            level = level,
            text = text,
            url = firstFrame?.get("url")?.jsonPrimitive?.content,
            lineNo = firstFrame?.get("lineNumber")?.jsonPrimitive?.content?.toIntOrNull(),
            columnNo = firstFrame?.get("columnNumber")?.jsonPrimitive?.content?.toIntOrNull(),
            stackTrace = stackTrace
        ))
    }

    private fun handleException(paramsJson: String) {
        val p = Json.parseToJsonElement(paramsJson).jsonObject
        val details = p["exceptionDetails"]?.jsonObject ?: return
        val exc = details["exception"]?.jsonObject
        val message = exc?.get("description")?.jsonPrimitive?.content
            ?: details["text"]?.jsonPrimitive?.content
            ?: "Unknown exception"
        val frames = details["stackTrace"]?.jsonObject?.get("callFrames")?.jsonArray
        val firstFrame = frames?.firstOrNull()?.jsonObject
        val stackTrace = frames?.joinToString("\n") { f ->
            val fo = f.jsonObject
            "    at ${fo["functionName"]?.jsonPrimitive?.content ?: "<anon>"}" +
                " (${fo["url"]?.jsonPrimitive?.content ?: ""}:${fo["lineNumber"]?.jsonPrimitive?.content ?: 0})"
        }

        _events.tryEmit(DebugEvent.JsException(
            timestamp = System.currentTimeMillis(),
            message = message,
            sourceUrl = firstFrame?.get("url")?.jsonPrimitive?.content
                ?: details["url"]?.jsonPrimitive?.content,
            lineNo = (firstFrame?.get("lineNumber")?.jsonPrimitive?.content?.toIntOrNull()
                ?: details["lineNumber"]?.jsonPrimitive?.content?.toIntOrNull()),
            columnNo = (firstFrame?.get("columnNumber")?.jsonPrimitive?.content?.toIntOrNull()
                ?: details["columnNumber"]?.jsonPrimitive?.content?.toIntOrNull()),
            stackTrace = stackTrace
        ))
    }

    private fun handleNetRequest(paramsJson: String) {
        val p = Json.parseToJsonElement(paramsJson).jsonObject
        val requestId = p["requestId"]?.jsonPrimitive?.content ?: return
        val request = p["request"]?.jsonObject ?: return
        val url = request["url"]?.jsonPrimitive?.content ?: return
        val method = request["method"]?.jsonPrimitive?.content ?: "GET"
        val type = p["type"]?.jsonPrimitive?.content
        networkRequests[requestId] = NetBuilder(
            requestId = requestId,
            url = url,
            method = method,
            resourceType = type,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun handleNetResponse(paramsJson: String) {
        val p = Json.parseToJsonElement(paramsJson).jsonObject
        val requestId = p["requestId"]?.jsonPrimitive?.content ?: return
        val builder = networkRequests[requestId] ?: return
        val response = p["response"]?.jsonObject ?: return
        builder.status = response["status"]?.jsonPrimitive?.content?.toIntOrNull()
        builder.mimeType = response["mimeType"]?.jsonPrimitive?.content
        p["type"]?.jsonPrimitive?.content?.let { builder.resourceType = it }
    }

    private fun handleNetFinished(paramsJson: String) {
        val p = Json.parseToJsonElement(paramsJson).jsonObject
        val requestId = p["requestId"]?.jsonPrimitive?.content ?: return
        val builder = networkRequests.remove(requestId) ?: return
        builder.encodedDataLength = p["encodedDataLength"]?.jsonPrimitive?.content?.toLongOrNull()
        emitNetwork(builder)
    }

    private fun handleNetFailed(paramsJson: String) {
        val p = Json.parseToJsonElement(paramsJson).jsonObject
        val requestId = p["requestId"]?.jsonPrimitive?.content ?: return
        val builder = networkRequests.remove(requestId) ?: return
        builder.failed = true
        builder.errorText = p["errorText"]?.jsonPrimitive?.content
        emitNetwork(builder)
    }

    private fun emitNetwork(b: NetBuilder) {
        _events.tryEmit(DebugEvent.NetworkRequest(
            timestamp = b.timestamp,
            requestId = b.requestId,
            url = b.url,
            method = b.method,
            status = b.status,
            mimeType = b.mimeType,
            resourceType = b.resourceType,
            encodedDataLength = b.encodedDataLength,
            failed = b.failed,
            errorText = b.errorText
        ))
    }

    // ── Native browser readiness ────────────────────────────────────────

    private fun waitForNativeBrowserCreated(browser: CefBrowser) {
        val method = try {
            browser.javaClass.getMethod("isNativeBrowserCreated")
        } catch (e: NoSuchMethodException) {
            return
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            try {
                if (method.invoke(browser) as? Boolean == true) return
            } catch (_: Exception) {
                return
            }
            Thread.sleep(50)
        }
    }
}
