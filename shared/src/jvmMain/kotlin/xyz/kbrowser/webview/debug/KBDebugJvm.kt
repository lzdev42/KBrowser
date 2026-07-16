package xyz.kbrowser.webview.debug

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.cef.browser.CefBrowser
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import xyz.kbrowser.jcef.KBCefClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class KBDebugJvm(
    private val cefBrowser: CefBrowser,
    private val cefClient: KBCefClient
) : KBDebug {

    private val enabledFlag = AtomicBoolean(false)
    @Volatile private var devTools: org.cef.browser.CefDevToolsClient? = null
    @Volatile private var crashedRecently = false

    @Volatile private var lastInspectMs: Long = 0L
    @Volatile private var lastKnownUrl: String = ""
    @Volatile private var pendingDialog: DialogInfo? = null

    private val _events = MutableSharedFlow<DebugEvent>(
        replay = 500,
        extraBufferCapacity = 0,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

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

    override val isEnabled: Boolean get() = enabledFlag.get()

    override fun enable() {
        if (!enabledFlag.compareAndSet(false, true)) return
        lastInspectMs = System.currentTimeMillis()
        lastKnownUrl = cefBrowser.url ?: ""
        Thread({
            try {
                waitForNativeBrowserCreated(cefBrowser)
                if (!enabledFlag.get()) return@Thread
                val dt = cefBrowser.devToolsClient ?: return@Thread
                if (dt.isClosed) return@Thread
                devTools = dt

                for (domain in listOf("Runtime", "Network", "Performance", "Page")) {
                    try {
                        dt.executeDevToolsMethod("$domain.enable", "{}").get(3, TimeUnit.SECONDS)
                    } catch (_: Exception) {}
                }

                try { cefClient.addRequestHandler(crashHandler, cefBrowser) } catch (_: Exception) {}

                dt.addEventListener { method, paramsJson ->
                    if (!enabledFlag.get()) return@addEventListener
                    runCatching { handleCdpEvent(method, paramsJson) }
                }
            } catch (_: Exception) {}
        }, "KB-Debug-Setup").apply { isDaemon = true }.start()
    }

    override fun disable() {
        if (!enabledFlag.compareAndSet(true, false)) return
        val dt = devTools
        if (dt != null && !dt.isClosed) {
            for (domain in listOf("Runtime", "Network", "Performance", "Page")) {
                try { dt.executeDevToolsMethod("$domain.disable", "{}") } catch (_: Exception) {}
            }
            try { dt.executeDevToolsMethod("Page.handleJavaScriptDialog", """{"accept":false}""") } catch (_: Exception) {}
        }
        try { cefClient.removeRequestHandler(crashHandler, cefBrowser) } catch (_: Exception) {}
        networkRequests.clear()
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        _events.resetReplayCache()
        crashedRecently = false
        devTools = null
        pendingDialog = null
        lastInspectMs = 0L
    }

    override suspend fun inspect(): PageInspection {
        val now = System.currentTimeMillis()
        val sinceMs = lastInspectMs
        val cache = _events.replayCache

        val recentEvents = cache.filter { it.timestamp > sinceMs }

        val currentUrl = cefBrowser.url ?: ""
        val navigated = lastKnownUrl.isNotEmpty() && currentUrl != lastKnownUrl

        val errors = mutableListOf<PageError>()
        recentEvents.filter { it is DebugEvent.ConsoleLog && it.level == ConsoleLevel.ERROR }
            .forEach { e -> e as DebugEvent.ConsoleLog; errors.add(PageError("console_error", e.text)) }
        recentEvents.filterIsInstance<DebugEvent.JsException>()
            .forEach { errors.add(PageError("js_exception", it.message)) }
        recentEvents.filterIsInstance<DebugEvent.NetworkRequest>().filter { it.failed }
            .forEach { errors.add(PageError("network_failed", "${it.method} ${it.url} ${it.errorText ?: ""}")) }
        recentEvents.filterIsInstance<DebugEvent.RenderCrash>()
            .forEach { errors.add(PageError("render_crash", "${it.status}: ${it.errorString ?: ""}")) }

        val requests = recentEvents.filterIsInstance<DebugEvent.NetworkRequest>()
            .filter { isXhrLike(it.resourceType, it.url) }
            .map { RequestSummary(it.requestId, it.method, it.url, it.status, it.failed) }

        val inspection = PageInspection(
            currentUrl = currentUrl,
            navigated = navigated,
            activeDialog = pendingDialog,
            errors = errors,
            requests = requests
        )

        lastInspectMs = now
        lastKnownUrl = currentUrl
        return inspection
    }

    override suspend fun respondDialog(accept: Boolean, promptText: String?): Boolean {
        val dt = devTools
        if (dt == null || dt.isClosed) return false
        if (pendingDialog == null) return false
        return withContext(Dispatchers.IO) {
            try {
                val params = buildJsonObject {
                    put("accept", accept)
                    if (promptText != null) put("promptText", promptText)
                }.toString()
                dt.executeDevToolsMethod("Page.handleJavaScriptDialog", params)
                    .get(5, TimeUnit.SECONDS)
                pendingDialog = null
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    override suspend fun snapshot(): DebugSnapshot {
        val dt = devTools
        val metrics = if (dt != null && !dt.isClosed) fetchMetrics(dt) else null
        val cache = _events.replayCache

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

    // ── CDP event parsing ────────────────────────────────────────────────

    private fun handleCdpEvent(method: String, paramsJson: String) {
        when (method) {
            "Runtime.consoleAPICalled" -> handleConsoleApi(paramsJson)
            "Runtime.exceptionThrown" -> handleException(paramsJson)
            "Network.requestWillBeSent" -> handleNetRequest(paramsJson)
            "Network.responseReceived" -> handleNetResponse(paramsJson)
            "Network.loadingFinished" -> handleNetFinished(paramsJson)
            "Network.loadingFailed" -> handleNetFailed(paramsJson)
            "Page.frameNavigated" -> handleNavigation(paramsJson)
            "Page.javascriptDialogOpening" -> handleDialogOpening(paramsJson)
            "Page.javascriptDialogClosed" -> handleDialogClosed(paramsJson)
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

    private fun handleNavigation(paramsJson: String) {
        val p = Json.parseToJsonElement(paramsJson).jsonObject
        val frame = p["frame"]?.jsonObject ?: return
        val url = frame["url"]?.jsonPrimitive?.content ?: return
        _events.tryEmit(DebugEvent.Navigation(
            timestamp = System.currentTimeMillis(),
            url = url
        ))
    }

    private fun handleDialogOpening(paramsJson: String) {
        val p = Json.parseToJsonElement(paramsJson).jsonObject
        val type = p["type"]?.jsonPrimitive?.content ?: "alert"
        val dialogType = when (type) {
            "confirm" -> DialogType.CONFIRM
            "prompt" -> DialogType.PROMPT
            "beforeunload" -> DialogType.BEFOREUNLOAD
            else -> DialogType.ALERT
        }
        val message = p["message"]?.jsonPrimitive?.content ?: ""
        val url = p["url"]?.jsonPrimitive?.content
        val defaultPrompt = p["defaultPrompt"]?.jsonPrimitive?.content

        pendingDialog = DialogInfo(dialogType, message, defaultPrompt)
        _events.tryEmit(DebugEvent.Dialog(
            timestamp = System.currentTimeMillis(),
            type = dialogType,
            message = message,
            url = url,
            defaultPrompt = defaultPrompt
        ))
    }

    private fun handleDialogClosed(paramsJson: String) {
        pendingDialog = null
    }

    private fun isXhrLike(resourceType: String?, url: String): Boolean {
        if (resourceType in XHR_LIKE_TYPES) return true
        val ext = url.substringAfterLast('?', "").substringAfterLast('#', "").substringAfterLast('.', "")
        return ext.lowercase() !in STATIC_EXTENSIONS
    }

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

    companion object {
        private val XHR_LIKE_TYPES = setOf("XHR", "Fetch", "Document", "WebSocket", "EventSource", "Other")
        private val STATIC_EXTENSIONS = setOf(
            "css", "js", "mjs", "png", "jpg", "jpeg", "gif", "svg", "ico",
            "woff", "woff2", "ttf", "otf", "eot", "mp4", "webm", "mp3",
            "wav", "ogg", "flac", "webp", "avif", "map"
        )
    }
}
