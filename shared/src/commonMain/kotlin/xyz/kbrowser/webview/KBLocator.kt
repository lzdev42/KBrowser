package xyz.kbrowser.webview

import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlin.random.Random

data class KBLocator(
    val page: KBPage,
    val selector: String,
    val selectorType: KBSelectorType,
    val name: String? = null,
    val exact: Boolean = true,
    val filterPredicate: ((LocateResult) -> Boolean)? = null,
    val index: Int? = null
) {
    private val jsonParser = Json { ignoreUnknownKeys = true }

    // ===== Core Search Logic =====

    /**
     * Finds a single element. Resolves lazily on each operation.
     */
    private suspend fun findElement(): LocateResult? {
        var elements = findAllElements()

        filterPredicate?.let { condition ->
            elements = elements.filter(condition)
        }

        if (elements.isEmpty()) return null

        return when (index) {
            null -> elements.first()
            -1 -> elements.last()
            in elements.indices -> elements[index]
            else -> null
        }
    }

    /**
     * Finds all matching elements.
     */
    private fun unwrapJsonString(raw: String): String {
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
            return try {
                jsonParser.decodeFromString<String>(raw)
            } catch (e: Exception) {
                raw
            }
        }
        return raw
    }

    private suspend fun findAllElements(): List<LocateResult> {
        // JVM 平台优先走 CDP 原生路线（不注入 JS，不受 CSP 限制）
        val nativeResults = findElementsNative(page.webView, selector, selectorType, name, exact)
        if (nativeResults != null) return nativeResults

        // fallback: JS 注入路线 (Android / iOS)
        val jsScript = buildFindAllJs()
        val resultJson = page.evaluateJavascript(jsScript)
        
        val cleanJson = unwrapJsonString(resultJson)

        if (cleanJson.isBlank() || cleanJson == "null" || cleanJson == "[]") {
            return emptyList()
        }
        
        return try {
            jsonParser.decodeFromString<List<LocateResult>>(cleanJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Builds the JS query script based on selectorType.
     */
    private fun buildFindAllJs(): String {
        return when (selectorType) {
            KBSelectorType.CSS -> JsScripts.FIND_ALL_BY_CSS.replace("__SELECTOR__", escapeJs(selector))
            KBSelectorType.XPATH -> JsScripts.FIND_ALL_BY_XPATH.replace("__SELECTOR__", escapeJs(selector))
            KBSelectorType.TEXT -> JsScripts.FIND_ALL_BY_TEXT.replace("__TEXT__", escapeJs(selector)).replace("__EXACT__", exact.toString())
            KBSelectorType.ROLE -> JsScripts.FIND_ALL_BY_ROLE.replace("__ROLE__", escapeJs(selector)).replace("__NAME__", escapeJs(name ?: ""))
            KBSelectorType.LABEL -> JsScripts.FIND_ALL_BY_LABEL.replace("__LABEL__", escapeJs(selector))
            KBSelectorType.PLACEHOLDER -> JsScripts.FIND_ALL_BY_PLACEHOLDER.replace("__TEXT__", escapeJs(selector))
            KBSelectorType.ALT_TEXT -> JsScripts.FIND_ALL_BY_ALT_TEXT.replace("__TEXT__", escapeJs(selector))
            KBSelectorType.TITLE -> JsScripts.FIND_ALL_BY_TITLE.replace("__TITLE__", escapeJs(selector))
            KBSelectorType.TEST_ID -> JsScripts.FIND_ALL_BY_TEST_ID.replace("__TESTID__", escapeJs(selector))
        }
    }

    private fun escapeJs(s: String): String {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n")
    }

    // ===== Interaction Operations =====

    suspend fun click() {
        val node = findElement() ?: throw ElementNotFoundException("Locator: $selectorType=$selector")
        page.clickByCoordinates(node.centerX, node.centerY)
    }

    suspend fun hover() {
        val node = findElement() ?: throw ElementNotFoundException("Locator: $selectorType=$selector")
        page.hoverByCoordinates(node.centerX, node.centerY)
    }

    suspend fun scroll(deltaX: Int, deltaY: Int) {
        val node = findElement() ?: throw ElementNotFoundException("Locator: $selectorType=$selector")
        page.scrollByCoordinates(node.centerX, node.centerY, deltaX, deltaY)
    }

    suspend fun fill(value: String) {
        val node = findElement() ?: throw ElementNotFoundException("Locator: $selectorType=$selector")
        // 1. Click to focus
        page.clickByCoordinates(node.centerX, node.centerY)
        // 2. Wait briefly
        delay(100)
        // 3. Set value using native event emulation
        val escapedValue = escapeJs(value)
        val escapedSelector = escapeJs(selector)
        val fillJs = JsScripts.SET_VALUE_NATIVE
            .replace("__SELECTOR__", escapedSelector)
            .replace("__VALUE__", escapedValue)
            .replace("__SELECTOR_TYPE__", selectorType.name)
        page.evaluateJavascript(fillJs)
    }

    /**
     * Types text character by character using native platform key events.
     * Each character has a random delay (30-150ms) to simulate real human typing.
     * This is the highest anti-detection input method — no JS injection, no DOM modification.
     *
     * Steps:
     * 1. Click to focus the element (DOM-direct if possible, otherwise coordinate click)
     * 2. Clear existing value: Ctrl+A → Delete (native key events)
     * 3. Type each character via native key event API with random delay
     */
    suspend fun type(text: String) {
        val node = findElement() ?: throw ElementNotFoundException("Locator: $selectorType=$selector")
        // 1. Click to focus
        page.clickByCoordinates(node.centerX, node.centerY)
        delay(100)
        // 2. Clear existing value: Ctrl+A → Delete
        page.pressKeyCombination(KeyboardKey.CONTROL, KeyboardKey.A)
        delay(50)
        page.press(KeyboardKey.DELETE)
        delay(50)
        // 3. Type each character with random delay
        for (char in text) {
            page.typeChar(char)
            delay(Random.nextLong(30, 150))
        }
    }

    suspend fun focus() {
        val node = findElement() ?: throw ElementNotFoundException("Locator: $selectorType=$selector")
        page.clickByCoordinates(node.centerX, node.centerY)
    }

    suspend fun check() {
        val node = findElement() ?: throw ElementNotFoundException("Locator: $selectorType=$selector")
        page.clickByCoordinates(node.centerX, node.centerY)
    }

    suspend fun selectOption(value: String) {
        // 1. Click to open dropdown
        val node = findElement() ?: throw ElementNotFoundException("Locator: $selectorType=$selector")
        page.clickByCoordinates(node.centerX, node.centerY)
        delay(200)
        // 2. Locate and click option by coordinates
        val escapedValue = escapeJs(value)
        val escapedSelector = escapeJs(selector)
        val optionJs = JsScripts.FIND_OPTION_AND_CLICK
            .replace("__SELECTOR__", escapedSelector)
            .replace("__VALUE__", escapedValue)
        val optionResultJson = page.evaluateJavascript(optionJs)
        
        val cleanJson = unwrapJsonString(optionResultJson)

        if (cleanJson.isNotBlank() && cleanJson != "null") {
            try {
                val optionResult = jsonParser.decodeFromString<LocateResult>(cleanJson)
                page.clickByCoordinates(optionResult.centerX, optionResult.centerY)
            } catch (e: Exception) {
                // ignore or log
            }
        }
    }

    /**
     * Presses a single key after focusing the element.
     * Uses native platform key event API (JCEF sendKeyEvent on JVM).
     */
    suspend fun press(key: KeyboardKey) {
        val node = findElement() ?: throw ElementNotFoundException("Locator: $selectorType=$selector")
        page.clickByCoordinates(node.centerX, node.centerY)
        delay(50)
        page.press(key)
    }

    /**
     * Presses a key combination (modifier + key) after focusing the element.
     * e.g. Ctrl+A, Ctrl+C, Ctrl+V
     * Uses native platform key event API.
     */
    suspend fun pressKeyCombination(modifier: KeyboardKey, key: KeyboardKey) {
        val node = findElement() ?: throw ElementNotFoundException("Locator: $selectorType=$selector")
        page.clickByCoordinates(node.centerX, node.centerY)
        delay(50)
        page.pressKeyCombination(modifier, key)
    }

    // ===== Query Methods =====

    suspend fun isVisible(): Boolean {
        val node = findElement()
        return node?.isVisible == true
    }

    suspend fun getText(): String {
        val node = findElement() ?: throw ElementNotFoundException("Locator: $selectorType=$selector")
        return node.text
    }

    suspend fun getAttribute(name: String): String? {
        val node = findElement() ?: throw ElementNotFoundException("Locator: $selectorType=$selector")
        return node.attributes[name]
    }

    suspend fun count(): Int {
        return findAllElements().size
    }

    suspend fun boundingBox(): Rect? {
        val node = findElement() ?: throw ElementNotFoundException("Locator: $selectorType=$selector")
        return Rect(
            x = node.centerX - node.width / 2,
            y = node.centerY - node.height / 2,
            width = node.width,
            height = node.height
        )
    }

    // ===== Chain / Composite Operations =====

    fun filter(predicate: (LocateResult) -> Boolean): KBLocator {
        return KBLocator(
            page = page,
            selector = selector,
            selectorType = selectorType,
            name = name,
            exact = exact,
            filterPredicate = predicate,
            index = index
        )
    }

    fun nth(index: Int): KBLocator {
        return KBLocator(
            page = page,
            selector = selector,
            selectorType = selectorType,
            name = name,
            exact = exact,
            filterPredicate = filterPredicate,
            index = index
        )
    }

    fun first(): KBLocator = nth(0)
    fun last(): KBLocator = nth(-1)
}
