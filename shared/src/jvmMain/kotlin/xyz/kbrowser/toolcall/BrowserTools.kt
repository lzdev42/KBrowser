package xyz.kbrowser.toolcall

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import xyz.kbrowser.webview.KBPage
import xyz.kbrowser.webview.KeyboardKey
import xyz.kbrowser.webview.OperationResult
import xyz.kbrowser.webview.SnapshotMode
import java.util.Base64

/**
 * The KBrowser AI tool surface: six tools covering the agent loop
 * perceive (snapshot) -> decide -> act -> observe.
 *
 * - browser_navigate      : loadUrl (+ optional newTab), returns snapshot automatically
 * - browser_navigate_back : history back, returns snapshot
 * - browser_snapshot      : AX tree as compact YAML; refids are the only element handle
 * - browser_act           : one dispatcher for click/type/fill/press/select/hover/scroll/
 *                           drag/upload/dialog/wait_for/wait; physical-first with
 *                           automatic JS fallback on occlusion
 * - browser_observe       : delta (what changed) / network body / health / screenshot / tabs
 * - browser_tabs          : list / close / close_others — multi-tab hygiene
 * - browser_eval          : JS escape hatch
 *
 * Every tool accepts an optional `pageId`; omitted = active tab. Intentionally NOT
 * exposed: selector-based locators (refid workflow replaces them), manual js* variants
 * (fallback is automatic), executeCdp (host escape hatch only).
 *
 * Bind via [listSpecs] (for the host's tool registry) + [call] (dispatch), against a
 * host-provided [BrowserToolSession].
 */
class BrowserTools(private val session: BrowserToolSession) {

    private val MAX_TEXT = 80_000

    // ── Tool specs (single source of truth for the host registry) ─────────

    fun listSpecs(): List<ToolSpec> = SPECS

    fun findSpec(name: String): ToolSpec? = SPECS.firstOrNull { it.name == name }

    private val SPECS: List<ToolSpec> = buildList {
        add(ToolSpec("browser_navigate",
            "Navigate to a URL and wait until loaded. Returns the page snapshot (compact YAML with refids) automatically. Set newTab=true to open in a new tab (returns its pageId).",
            schema {
                prop("url", "string", "Absolute URL to load", required = true)
                prop("newTab", "boolean", "Open in a new managed tab instead of the active one (default false)")
                prop("pageId", "string", "Target tab; omitted = active tab")
            }))
        add(ToolSpec("browser_navigate_back",
            "Go back to the previous page in history. Returns the page snapshot after navigation.",
            schema {
                prop("pageId", "string", "Target tab; omitted = active tab")
            }))
        add(ToolSpec("browser_snapshot",
            "Capture the page as compact YAML. Each element has a refid; pass refids to browser_act. Do this first if you have not navigated yet, and re-do it after page changes (refids may go stale).",
            schema {
                prop("mode", "string", "viewport = visible elements only (default, token-efficient); clean = whole page", enum = listOf("viewport", "clean"))
                prop("pageId", "string", "Target tab; omitted = active tab")
            }))
        add(ToolSpec("browser_act",
            "Perform one interaction on the page. Refids come from browser_snapshot. The result tells you whether the action hit the target (occlusion is detected), so you usually do NOT need a new snapshot unless the result indicates the page changed.",
            schema {
                prop("action", "string", "click=physical click; type=click focus then physical typing (human-like); fill=JS set value + verify (for plain fields); press=key after focusing (or global); select=pick option in <select>; hover; scroll; drag; upload=set files on input[type=file]; dialog=answer a JS alert/confirm/prompt reported by observe; wait_for=wait until text appears/disappears or URL changes; scroll_to=scroll an element into view (refid, returns its viewport rect); reload=re-render current page (returns fresh snapshot); wait=sleep", required = true, enum = listOf("click","type","fill","press","select","hover","scroll","scroll_to","drag","upload","dialog","wait_for","reload","wait"))
                prop("refid", "string", "Target element refid from snapshot (required for click/type/fill/select/hover/upload/scroll_to; drag uses startRefid)")
                prop("text", "string", "Text for type/fill/select; wait_for: text to wait for")
                prop("textGone", "string", "wait_for: wait until this text disappears")
                prop("urlPattern", "string", "wait_for: wait until URL contains this fragment")
                prop("timeoutMs", "integer", "wait_for: max wait in ms (default 10000, max 60000)")
                prop("key", "string", "press: e.g. \"Enter\", \"Tab\", \"Control+a\", \"Meta+c\"")
                prop("startRefid", "string", "drag: source refid (or use refid)")
                prop("endRefid", "string", "drag: destination refid (required for drag)")
                prop("x", "integer", "scroll: document X when no refid")
                prop("y", "integer", "scroll: document Y when no refid")
                prop("dx", "integer", "scroll: horizontal delta, positive = right")
                prop("dy", "integer", "scroll: vertical delta, positive = down")
                prop("paths", "array", "upload: absolute file paths")
                prop("accept", "boolean", "dialog: true=OK/accept, false=cancel/dismiss")
                prop("promptText", "string", "dialog: text for prompt dialogs")
                prop("ms", "integer", "wait: milliseconds (max 10000)")
                prop("fallbackJs", "boolean", "click: retry via DOM click when physically occluded (default true)")
                prop("pageId", "string", "Target tab; omitted = active tab")
            }))
        add(ToolSpec("browser_observe",
            "Query what happened on the page without taking a snapshot: navigation changes, JS dialogs, console/JS/network errors, XHR requests (+ response body), performance health, a screenshot, or the list of open tabs.",
            schema {
                prop("kind", "string", "delta (default) = URL change + active dialog + errors + XHR requests since last observe; body = response body of a request (needs requestId from delta); health = heap/DOM/error counters; screenshot = image of the page; tabs = open tabs", enum = listOf("delta","body","health","screenshot","tabs"))
                prop("requestId", "string", "body: requestId from a previous observe kind=delta")
                prop("quality", "integer", "screenshot: 1-99 JPEG quality (smaller payload); omit = full PNG")
                prop("pageId", "string", "Target tab; omitted = active tab")
            }))
        add(ToolSpec("browser_tabs",
            "Manage tabs: list open tabs (with idle time), close one, or close every tab except the active one. Use close_others after finishing a multi-tab task.",
            schema {
                prop("action", "string", "list (default) | close | close_others", enum = listOf("list","close","close_others"))
                prop("pageId", "string", "close: id of the tab to close")
            }))
        add(ToolSpec("browser_eval",
            "Run a JavaScript expression in the page and return the result. Use for reading values not in the accessibility tree. The expression should return a string/number/JSON-serializable value.",
            schema {
                prop("expression", "string", "JS expression, e.g. \"document.title\" or \"JSON.stringify(location)\"", required = true)
                prop("pageId", "string", "Target tab; omitted = active tab")
            }))
    }

    /** JSON Schema object builder with required-prop tracking. */
    private fun schema(block: SchemaBuilder.() -> Unit): JsonObject {
        val b = SchemaBuilder().apply(block)
        return buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { b.props.forEach { (n, s) -> put(n, s) } })
            if (b.required.isNotEmpty()) {
                put("required", buildJsonArray { b.required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            }
        }
    }

    private class SchemaBuilder {
        val props = mutableListOf<Pair<String, JsonObject>>()
        val required = mutableListOf<String>()
        fun prop(name: String, type: String, description: String, required: Boolean = false, enum: List<String>? = null) {
            props.add(name to buildJsonObject {
                put("type", type)
                put("description", description)
                if (enum != null) put("enum", buildJsonArray { enum.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            })
            if (required) this@SchemaBuilder.required.add(name)
        }
    }

    // ── Dispatch ──────────────────────────────────────────────────────────

    suspend fun call(name: String, args: JsonObject): ToolResult = try {
        val content = when (name) {
            "browser_navigate" -> navigate(
                args.str("url") ?: throw IllegalArgumentException("url is required"),
                args.bool("newTab") ?: false, args.str("pageId")
            )
            "browser_navigate_back" -> navigateBack(args.str("pageId"))
            "browser_snapshot" -> listOf(ToolContent.Text(snapshotOf(session.resolve(args.str("pageId")), args.str("mode") ?: "viewport")))
            "browser_act" -> act(args)
            "browser_observe" -> observe(args)
            "browser_tabs" -> tabs(args)
            "browser_eval" -> listOf(ToolContent.Text(eval(args.str("expression") ?: throw IllegalArgumentException("expression is required"), args.str("pageId"))))
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
        ToolResult(content, isError = false)
    } catch (t: Throwable) {
        // Tool failures are DATA for the model (it can self-correct), not protocol errors.
        // Stale-refid is the most common agent mistake: give the recovery recipe inline.
        val msg = when (t) {
            is xyz.kbrowser.webview.ElementNotFoundException ->
                "STALE_REFID: ${t.message} The page has changed since your last browser_snapshot " +
                "(refids do not survive navigation or dynamic updates). Take a fresh browser_snapshot and retry with the new refid."
            else -> t.message ?: t.toString()
        }.take(500)
        ToolResult(listOf(ToolContent.Text("ERROR: $msg")), isError = true)
    }

    // ── navigate ──────────────────────────────────────────────────────────

    private suspend fun navigate(url: String, newTab: Boolean, pageId: String?): List<ToolContent> {
        require(url.isNotBlank()) { "url is required" }
        val page = if (newTab) session.newTab(null) else session.resolve(pageId)
        page.loadUrl(url)
        val settled = snapshotSettled(page)
        return listOf(ToolContent.Text(settled))
    }

    /**
     * Snapshot with a render-race guard: right after load, the AX tree may be built
     * before the page's widgets register (empty/partial tree). Retry briefly until
     * the tree is non-empty; otherwise return whatever we have.
     */
    private suspend fun snapshotSettled(page: KBPage, mode: String = "viewport"): String {
        var attempts = 0
        while (true) {
            val result = page.snapshot(if (mode == "clean") SnapshotMode.CLEAN else SnapshotMode.VIEWPORT)
            if (result.rawTree.totalElements > 0 || attempts >= 6) {
                val yaml = result.yaml
                val body = if (yaml.length > MAX_TEXT) yaml.take(MAX_TEXT) + "\n...[truncated ${yaml.length} chars total]" else yaml
                return "pageId: \"${page.uuid}\"\n$body"
            }
            attempts++
            delay(300)
        }
    }

    private suspend fun navigateBack(pageId: String?): List<ToolContent> {
        val page = session.resolve(pageId)
        page.goBack()
        return listOf(ToolContent.Text(snapshotOf(page, "viewport")))
    }

    /** YAML snapshot with the owning pageId as the first (valid YAML) key. */
    private suspend fun snapshotOf(page: KBPage, mode: String): String {
        val yaml = page.snapshot(if (mode == "clean") SnapshotMode.CLEAN else SnapshotMode.VIEWPORT).yaml
        val body = if (yaml.length > MAX_TEXT) yaml.take(MAX_TEXT) + "\n...[truncated ${yaml.length} chars total]" else yaml
        return "pageId: \"${page.uuid}\"\n$body"
    }

    // ── act ───────────────────────────────────────────────────────────────

    private suspend fun act(a: JsonObject): List<ToolContent> {
        val action = a.str("action") ?: throw IllegalArgumentException("action is required")
        val page = session.resolve(a.str("pageId"))
        val out = mutableListOf<String>()
        return listOf(ToolContent.Text(when (action) {
            "click" -> {
                val refid = a.str("refid") ?: throw IllegalArgumentException("refid is required for click")
                var r = page.click(refid)
                out.add(r.describe())
                if (r is OperationResult.Failure && a.bool("fallbackJs") != false) {
                    page.jsClick(refid)
                    out.add("physical click failed (${r.reason}); retried via DOM click")
                }
                out.joinToString("; ")
            }
            "hover" -> { page.hover(a.refidOrThrow("hover")); "hover sent" }
            "scroll" -> {
                val dx = a.int("dx") ?: 0
                val dy = a.int("dy") ?: 0
                val r = a.str("refid")?.let { page.scroll(it, dx, dy) }
                    ?: page.scrollByCoordinates(a.int("x") ?: 640, a.int("y") ?: 360, dx, dy)
                r.describe()
            }
            "drag" -> {
                val start = a.str("startRefid") ?: a.str("refid")
                    ?: throw IllegalArgumentException("startRefid is required for drag")
                val end = a.str("endRefid") ?: throw IllegalArgumentException("endRefid is required for drag")
                page.drag(start, end)
                "drag $start -> $end sent"
            }
            "type" -> {
                val refid = a.refidOrThrow("type")
                val text = a.str("text") ?: throw IllegalArgumentException("text is required for type")
                page.click(refid) // focus
                page.type(text)
                "typed ${text.length} chars"
            }
            "fill" -> {
                val refid = a.refidOrThrow("fill")
                val text = a.str("text") ?: throw IllegalArgumentException("text is required for fill")
                val selector = selectorOf(page, refid)
                page.locator(selector).fill(text).describe()
            }
            "select" -> {
                val refid = a.refidOrThrow("select")
                val value = a.str("text") ?: throw IllegalArgumentException("text is required for select")
                page.locator(selectorOf(page, refid)).selectOption(value)
                "selected '$value'"
            }
            "press" -> {
                val key = parseKey(a.str("key") ?: throw IllegalArgumentException("key is required for press"))
                if (a.str("refid") != null) page.click(a.str("refid")!!) // focus first
                val modifier = a.str("key")!!.takeIf { it.contains("+") }?.substringBefore("+")
                if (modifier != null) page.pressKeyCombination(parseKey(modifier), parseKey(a.str("key")!!.substringAfter("+")))
                else page.press(key)
                "pressed ${a.str("key")}"
            }
            "upload" -> {
                val paths = (a["paths"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    ?: throw IllegalArgumentException("paths is required for upload")
                page.uploadFile(a.refidOrThrow("upload"), paths)
                "uploaded ${paths.size} file(s)"
            }
            "dialog" -> {
                val ok = page.debug.respondDialog(a.bool("accept") ?: true, a.str("promptText"))
                if (ok) "dialog handled" else "no active dialog"
            }
            "wait_for" -> {
                val text = a.str("text")
                val textGone = a.str("textGone")
                val urlPattern = a.str("urlPattern")
                if (text == null && textGone == null && urlPattern == null) {
                    throw IllegalArgumentException("wait_for needs at least one of text / textGone / urlPattern")
                }
                page.waitFor(text, textGone, urlPattern, (a.int("timeoutMs") ?: 10_000).toLong())
                "condition met (text=$text, textGone=$textGone, urlPattern=$urlPattern)"
            }
            "scroll_to" -> page.scrollIntoView(a.refidOrThrow("scroll_to")).describe()
            "reload" -> {
                page.reload()
                "reloaded; take browser_snapshot for fresh refids"
            }
            "wait" -> withContext(Dispatchers.IO) { delay((a.int("ms") ?: 1000).coerceIn(1, 10_000).toLong()) }.let { "waited ${a.int("ms") ?: 1000}ms" }
            else -> throw IllegalArgumentException("unknown action: $action")
        }))
    }

    private fun OperationResult.describe(): String = when (this) {
        is OperationResult.Success -> if (verified) "ok ($detail)" else "sent, unverified (${detail})"
        is OperationResult.Failure -> "FAILED: $reason"
        OperationResult.Acknowledged -> "sent (no verification available)"
    }

    /**
     * Resolves a refid to a CSS selector with a FRESH tree fetch, so the lookup is
     * self-consistent (refid -> selector come from the same fetch). refids on the JVM
     * CDP path are stable DOM backend ids, so this is safe between snapshots.
     */
    private suspend fun selectorOf(page: KBPage, refid: String): String {
        val node = page.snapshot(SnapshotMode.CLEAN).rawTree.nodes.firstOrNull { it.refid == refid }
            ?: throw IllegalArgumentException("refid '$refid' not found; take a new browser_snapshot")
        return node.selector
    }

    private fun parseKey(s: String): KeyboardKey =
        KeyboardKey.entries.firstOrNull { it.name == s.trim().uppercase().replace(" ", "_") }
            ?: throw IllegalArgumentException("unknown key '$s' (e.g. Enter, Tab, Escape, ArrowDown, Control+a)")

    // ── tabs ──────────────────────────────────────────────────────────────

    private suspend fun tabs(a: JsonObject): List<ToolContent> = when (a.str("action") ?: "list") {
        "list" -> listOf(ToolContent.Text(buildJsonObject {
            put("tabs", buildJsonArray {
                session.listTabs().forEach { t ->
                    add(buildJsonObject {
                        put("pageId", t.pageId); put("url", t.url); put("title", t.title)
                        put("active", t.active); put("idleSeconds", t.idleMs / 1000)
                    })
                }
            })
            put("hint", "close unused tabs with action=close_others, or action=close + pageId")
        }.toString()))
        "close" -> listOf(ToolContent.Text("closed tab ${session.closeTab(a.str("pageId") ?: throw IllegalArgumentException("pageId is required to close a tab"))}"))
        "close_others" -> {
            val closed = session.closeOthers()
            listOf(ToolContent.Text(if (closed.isEmpty()) "no unused tabs to close"
                else "closed ${closed.size} unused tab(s): ${closed.joinToString()}; active tab kept"))
        }
        else -> throw IllegalArgumentException("unknown tabs action (list | close | close_others)")
    }

    // ── observe ───────────────────────────────────────────────────────────

    private suspend fun observe(a: JsonObject): List<ToolContent> = when (a.str("kind") ?: "delta") {
        "delta" -> {
            val insp = session.resolve(a.str("pageId")).debug.inspect()
            listOf(ToolContent.Text(buildJsonObject {
                put("url", insp.currentUrl)
                put("navigated", insp.navigated)
                put("activeDialog", insp.activeDialog?.let { "${it.type}: ${it.message}" })
                put("errors", buildJsonArray { insp.errors.forEach { add(kotlinx.serialization.json.JsonPrimitive("${it.type}: ${it.message}")) } })
                put("requests", buildJsonArray {
                    insp.requests.forEach { r ->
                        add(buildJsonObject {
                            put("requestId", r.requestId); put("method", r.method)
                            put("url", r.url); put("status", r.status); put("failed", r.failed)
                        })
                    }
                })
            }.toString()))
        }
        "body" -> listOf(ToolContent.Text(session.resolve(a.str("pageId")).debug.getResponseBody(a.str("requestId") ?: throw IllegalArgumentException("requestId is required")) ?: "(body unavailable or evicted)"))
        "health" -> {
            val s = session.resolve(a.str("pageId")).debug.snapshot()
            listOf(ToolContent.Text(buildJsonObject {
                put("jsHeapUsedMB", s.jsHeapUsedSize / 1_000_000)
                put("domNodes", s.domNodeCount)
                put("consoleErrors", s.consoleErrorCount)
                put("jsExceptions", s.jsExceptionCount)
                put("failedRequests", s.failedRequestCount)
                put("totalRequests", s.totalRequestCount)
                put("crashedRecently", s.crashedRecently)
            }.toString()))
        }
        "screenshot" -> screenshot(a)
        "tabs" -> tabs(buildJsonObject { put("action", "list") })
        else -> throw IllegalArgumentException("unknown kind")
    }

    private suspend fun screenshot(a: JsonObject): List<ToolContent> {
        val bytes = session.resolve(a.str("pageId")).screenshot() ?: throw IllegalStateException("screenshot failed")
        val q = a.int("quality")
        if (q != null && q in 1..99) {
            val decoded = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
                ?: throw IllegalStateException("screenshot decode failed")
            // JPEG has no alpha: CDP screenshots are ARGB, so flatten onto RGB first
            // or the writer fails with "Bogus input colorspace"
            val rgb = java.awt.image.BufferedImage(decoded.width, decoded.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = rgb.createGraphics()
            g.drawImage(decoded, 0, 0, null)
            g.dispose()
            val baos = java.io.ByteArrayOutputStream()
            val writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpg").next()
            val iwp = writer.defaultWriteParam
            iwp.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            iwp.compressionQuality = q / 100f
            val out = javax.imageio.stream.MemoryCacheImageOutputStream(baos)
            writer.output = out
            writer.write(null, javax.imageio.IIOImage(rgb, null, null), iwp)
            writer.dispose(); out.close()
            return listOf(ToolContent.Image(Base64.getEncoder().encodeToString(baos.toByteArray()), "image/jpeg"))
        }
        return listOf(ToolContent.Image(Base64.getEncoder().encodeToString(bytes), "image/png"))
    }

    private suspend fun eval(expression: String, pageId: String?): String {
        val raw = session.resolve(pageId).evaluateJavascript(expression).trim()
        // evaluateJavascript may return a JSON-encoded value or a bare string
        // depending on platform; unwrap JSON strings, keep everything else as-is.
        val v = try {
            kotlinx.serialization.json.Json.parseToJsonElement(raw).let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: raw
            }
        } catch (_: Exception) { raw }
        return if (v.length > MAX_TEXT) v.take(MAX_TEXT) + "...[truncated]" else v
    }
}

// ── JsonObject arg helpers ───────────────────────────────────────────────

internal fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun JsonObject.refidOrThrow(action: String): String =
    str("refid") ?: throw IllegalArgumentException("refid is required for $action")
