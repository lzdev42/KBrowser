# KBrowser API Reference

> [← Back to README](../README.md)

English | [简体中文](KBrowser_API_Reference_zh.md)

> **Coordinate System**: All coordinates in this documentation are in **CSS document pixels**. Screenshot coordinates align 1:1 with interaction coordinates.

---

## 1. Quick Start

JVM platform must be initialized before calling `application {}`:

```kotlin
// main.kt
fun main() {
    // 1. Configure storage path
    KBrowser.configure(BrowserConfig(storageDir = "/path/to/cache"))
    // 2. Initialize JCEF engine (must be called before any UI initialization)
    initializeKBrowser()

    // 3. Start Compose application
    application {
        Window(onCloseRequest = ::exitApplication) {
            App()
        }
    }
}
```

---

## 2. KBWebView — UI Component Layer

`KBWebView` is a platform-independent interface representing a web page rendering instance. It is created using `rememberKBWebView` and rendered using the `@Composable KBWebView` component.

### StateFlows

| Property | Type | Description |
|------|------|------|
| `currentUrl` | `StateFlow<String?>` | Current page URL |
| `currentTitle` | `StateFlow<String?>` | Current page title |
| `loadingState` | `StateFlow<LoadingState>` | Loading state: `IDLE` / `LOADING` / `FINISHED` / `ERROR` |
| `progress` | `StateFlow<Float>` | Loading progress (0.0f to 1.0f) |
| `canGoBack` | `StateFlow<Boolean>` | Whether backward navigation is available |
| `canGoForward` | `StateFlow<Boolean>` | Whether forward navigation is available |

### Navigation Methods

| Method | Description |
|------|------|
| `loadUrl(url: String)` | Loads the specified network URL |
| `loadHtml(html: String)` | Loads the HTML string |
| `reload()` | Reloads the current page |
| `stopLoading()` | Stops current loading operations |
| `goBack()` | Navigates backward |
| `goForward()` | Navigates forward |

### JS Interaction

| Method | Description |
|------|------|
| `evaluateJavascript(script, callback?)` | Evaluates Javascript, returning the result as a JSON string via callback |
| `registerJsCallback(name, callback)` | Registers a Native callback, callable from JS via `window.<name>(data)` |
| `unregisterJsCallback(name)` | Unregisters a JS callback |

### Lifecycle & Others

| Method | Description |
|------|------|
| `clearCacheAndCookies()` | Clears the cache and cookies of the current Profile |
| `destroy()` | Destroys the WebView and releases resources |
| `setWebViewClient(client?)` | Sets callback for page loading events |
| `setWebChromeClient(client?)` | Sets callback for JS dialogs / permissions |
| `suspend takeScreenshot(): ByteArray?` | Takes screenshot via CDP, returning PNG in CSS pixel size. Solves black-screen issues |
| `var onNewWindowRequest: ((url: String) -> Unit)?` | Callback for new tab/window requests; silently discarded if null |

### Composable Usage Example

```kotlin
@Composable
fun BrowserScreen() {
    val webView = rememberKBWebView(initialUrl = "https://example.com")

    LaunchedEffect(webView) {
        webView.setWebViewClient(object : KBWebViewClient {
            override fun onPageStarted(url: String) { println("Start loading: $url") }
            override fun onPageFinished(url: String) { println("Page finished: $url") }
            override fun onReceivedError(error: Diagnostics) { println("Page failed: ${error.description}") }
        })
        // Intercept new window requests
        webView.onNewWindowRequest = { url -> println("New window: $url") }
    }

    Column(Modifier.fillMaxSize()) {
        val url by webView.currentUrl.collectAsState()
        Text("Current page: $url")

        KBWebView(webView = webView, modifier = Modifier.weight(1f))

        Row {
            Button(onClick = { webView.goBack() }) { Text("Back") }
            Button(onClick = { webView.goForward() }) { Text("Forward") }
            Button(onClick = { webView.reload() }) { Text("Reload") }
        }
    }
}
```

---

## 3. KBPage — Automation Layer

`KBPage` is a coroutine-based wrapper around `KBWebView`. All its methods are `suspend` functions and can be safely called from any coroutine context. It is created using `KBrowser.newPage()`.

### StateFlows

Delegates directly to `KBWebView`: `currentUrl`, `title`, `loadingState`, `progress`.

### Navigation

| Method | Description |
|------|------|
| `suspend loadUrl(url: String)` | Loads the URL and suspends until `onPageFinished` is received. Automatically calls `stopLoading()` if cancelled. |
| `suspend evaluateJavascript(script: String): String` | Evaluates Javascript, returning the result as a JSON string. |
| `suspend clearCacheAndCookies()` | Clears cache and cookies. |
| `suspend setCookieViaJs(cookieString: String)` | Injects cookie string via `document.cookie`. |
| `suspend screenshot(): ByteArray?` | Takes screenshot via CDP, returning PNG bytes in CSS pixel size. |
| `close()` | Destroys the underlying WebView. |

### AXTree (Accessibility Tree)

| Method | Description |
|------|------|
| `suspend getRawAxTree(): AxTreeData` | Retrieves the full accessibility tree and updates the internal coordinates cache `nodeCache`. |
| `AxTreeData.getCleanedAxTree(): AxTreeData` | Extension function to filter out noisy nodes (invisible elements, layout-only containers, etc.). |
| `AxTreeData.getViewportAxTree(): AxTreeData` | Extension function to crop nodes to the current viewport area. |

These two extension functions are pure Kotlin computations. They execute in the caller's coroutine context without switching threads.

### Interaction (refid-based)

Must execute `getRawAxTree()` beforehand to refresh the cache. Throws `ElementNotFoundException` if the refid does not exist.

| Method | Description |
|------|------|
| `suspend click(refid: String)` | Resolves coordinates from the cache and dispatches physical click via CDP. |
| `suspend hover(refid: String)` | Resolves coordinates from the cache and dispatches hover event via CDP. |
| `suspend scroll(refid: String, deltaX: Int, deltaY: Int)` | Resolves coordinates from the cache and dispatches wheel event via CDP. |

### Interaction (Coordinates-based, CSS document pixels)

| Method | Description |
|------|------|
| `suspend clickByCoordinates(x: Int, y: Int)` | CDP `Input.dispatchMouseEvent` (automatically converts to viewport coordinates). |
| `suspend hoverByCoordinates(x: Int, y: Int)` | CDP hover. |
| `suspend scrollByCoordinates(x: Int, y: Int, deltaX: Int, deltaY: Int)` | CDP wheel scroll. |
| `suspend dragByCoordinates(startX: Int, startY: Int, endX: Int, endY: Int)` | CDP simulated mouse drag. |

### Keyboard

| Method | Description |
|------|------|
| `suspend press(key: KeyboardKey)` | Presses and releases a single key. |
| `suspend pressKeyCombination(modifier: KeyboardKey, key: KeyboardKey)` | Key combination, e.g. `Ctrl+A`. |
| `suspend typeChar(char: Char)` | Types a single character. |
| `suspend type(text: String)` | Types text character-by-character with a random delay of 30~150ms to simulate human typing. |

### Locator Factory

| Method | Description |
|------|------|
| `locator("css=...")` or `locator("xpath=...")` | CSS/XPath locator, defaults to CSS. |
| `getByRole(role, name?)` | Locates by ARIA role, optional name filter. |
| `getByText(text, exact)` | Locates by text content, `exact=true` for exact match. |
| `getByLabel(label)` | Locates by associated label. |
| `getByPlaceholder(text)` | Locates by placeholder attribute. |
| `getByAltText(text)` | Locates by alt attribute. |
| `getByTitle(title)` | Locates by title attribute. |
| `getByTestId(testId)` | Locates by `data-testid` attribute. |

### New Window

| Property | Description |
|------|------|
| `var onNewPage: ((url: String) -> Unit)?` | Delegates to `webView.onNewWindowRequest`; triggered by both `target="_blank"` and `window.open()`. Silently discarded if null. |

---

## 4. KBLocator — Declarative Locator

`KBLocator` evaluates lazily, re-finding elements for every action. The JVM platform prefers CDP (no JS injection, CSP-safe), while Android/iOS fall back to JS.

### Interaction Methods

| Method | Description |
|------|------|
| `suspend click()` | Locates the element and sends physical click to its center coordinates. |
| `suspend hover()` | Locates the element and dispatches a hover event. |
| `suspend scroll(deltaX, deltaY)` | Locates the element and dispatches a wheel scroll event. |
| `suspend fill(value: String)` | Focuses and sets the input value via JS (fast fill). |
| `suspend type(text: String)` | Focuses → Ctrl+A → Delete → Types text character-by-character physically (high anti-detection). |
| `suspend focus()` | Focuses on the element. |
| `suspend check()` | Clicks the checkbox/radio button. |
| `suspend selectOption(value: String)` | Clicks dropdown, then clicks the matching option. |
| `suspend press(key: KeyboardKey)` | Sends single key event after focusing. |
| `suspend pressKeyCombination(modifier, key)` | Sends key combination after focusing. |

### Query Methods

| Method | Description |
|------|------|
| `suspend isVisible(): Boolean` | Whether the element is visible. |
| `suspend getText(): String` | Gets text content of the element. |
| `suspend getAttribute(name: String): String?` | Gets value of the specified attribute. |
| `suspend count(): Int` | Number of matching elements. |
| `suspend boundingBox(): Rect?` | Gets bounding box of the element (CSS document pixels). |

### Chainable Filters

| Method | Description |
|------|------|
| `filter(predicate: (LocateResult) -> Boolean): KBLocator` | Further filters the matched results. |
| `nth(index: Int): KBLocator` | Selects the n-th match (0-indexed, -1 represents the last one). |
| `first(): KBLocator` | Selects the first match. |
| `last(): KBLocator` | Selects the last match. |

### Chainable Examples

```kotlin
// Find the second of all visible "Submit" buttons and click it
page.getByRole("button", name = "Submit")
    .filter { it.isVisible }
    .nth(1)
    .click()

// XPath locating + fill
page.locator("xpath=//input[@name='email']").fill("user@example.com")
```

---

## 5. Data Structures

### AxNode

All coordinates are in **CSS document pixels**.

```kotlin
data class AxNode(
    val refid: String,          // Unique node ID for interactions like click(refid)
    val tagName: String,        // HTML tag name, e.g. "button", "input"
    val role: String,           // ARIA role, e.g. "button", "textbox"
    val id: String,             // DOM id attribute
    val className: String,      // CSS class
    val text: String,           // Node text content
    val isVisible: Boolean,     // Whether it is visible in the viewport
    val x: Int,                 // Node top-left X (CSS document pixels)
    val y: Int,                 // Node top-left Y (CSS document pixels)
    val width: Int,             // Node width
    val height: Int,            // Node height
    val centerX: Int,           // Center X (CSS document pixels)
    val centerY: Int,           // Center Y (CSS document pixels)
    val childCount: Int,        // Number of children nodes
    val attributes: Map<String, String>, // Node attributes map
    val iframeSrc: String?      // Source URL if node is an iframe
)
```

### AxTreeData

```kotlin
data class AxTreeData(
    val url: String,            // Current page URL
    val innerWidth: Int,        // Viewport width (CSS pixels)
    val innerHeight: Int,       // Viewport height (CSS pixels)
    val scrollX: Int,           // Horizontal scroll offset (CSS pixels)
    val scrollY: Int,           // Vertical scroll offset (CSS pixels)
    val documentWidth: Int,     // Total document width (CSS pixels)
    val documentHeight: Int,    // Total document height (CSS pixels)
    val devicePixelRatio: Double, // DPR, used internally for screenshots
    val totalElements: Int,
    val visibleElements: Int,
    val hiddenElements: Int,
    val iframeCount: Int,
    val nodes: List<AxNode>
)
```

### KeyboardKey Enum

```kotlin
enum class KeyboardKey {
    // Special Keys
    ENTER, TAB, ESCAPE, BACKSPACE, DELETE,
    // Arrow Keys
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
    // Modifiers
    SHIFT, CONTROL, ALT, META,  // META = Mac Command
    // Space & Navigation
    SPACE, HOME, END, PAGE_UP, PAGE_DOWN, INSERT,
    // Function Keys
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    // Common Shortcut Letters
    A, C, V, X, S, Z
}
```

### Other Data Classes

```kotlin
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

data class Diagnostics(
    val errorCode: Int,
    val description: String,
    val failingUrl: String
)

data class BrowserConfig(val storageDir: String)

data class KBProfile(val profileId: String, val storageDir: String)
```

---

## 6. Callbacks

### KBWebViewClient

```kotlin
interface KBWebViewClient {
    fun onPageStarted(url: String)
    fun onPageFinished(url: String)
    fun onReceivedError(error: Diagnostics)
}
```

### KBWebChromeClient

```kotlin
interface KBWebChromeClient {
    fun onJsAlert(url: String, message: String, callback: JsResultCallback)
    fun onJsConfirm(url: String, message: String, callback: JsResultCallback)
    fun onJsPrompt(url: String, message: String, defaultValue: String?, callback: JsPromptResultCallback)
    fun onPermissionRequest(request: PermissionRequest)
}

interface JsResultCallback {
    fun confirm()
    fun cancel()
}

interface JsPromptResultCallback {
    fun confirm(value: String?)
    fun cancel()
}
```

---

## 7. Full Examples

### Example 1: UI Browser (KBWebView Composable)

```kotlin
@Composable
fun BrowserApp() {
    val webView = rememberKBWebView(initialUrl = "https://example.com")
    val url by webView.currentUrl.collectAsState()
    val loading by webView.loadingState.collectAsState()
    var inputUrl by remember { mutableStateOf("") }

    LaunchedEffect(webView) {
        webView.setWebViewClient(object : KBWebViewClient {
            override fun onPageStarted(url: String) {}
            override fun onPageFinished(url: String) {}
            override fun onReceivedError(error: Diagnostics) {
                println("Error ${error.errorCode}: ${error.description}")
            }
        })
        webView.onNewWindowRequest = { newUrl ->
            // Load in the current webview instead of opening a new window
            webView.loadUrl(newUrl)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            TextField(
                value = inputUrl,
                onValueChange = { inputUrl = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Enter URL") }
            )
            Button(onClick = { webView.loadUrl(inputUrl) }) { Text("Go") }
        }

        if (loading == LoadingState.LOADING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        KBWebView(webView = webView, modifier = Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = { webView.goBack() }) { Text("←") }
            Button(onClick = { webView.goForward() }) { Text("→") }
            Button(onClick = { webView.reload() }) { Text("Reload") }
        }
    }
}
```

### Example 2: Headless Automation (KBPage Coroutine Pipeline)

```kotlin
suspend fun runAutomation() {
    // Initialization (done in main.kt, shown here for illustration)
    // KBrowser.configure(BrowserConfig(storageDir = "/tmp/kbrowser"))
    // initializeKBrowser()

    val page = KBrowser.newPage(
        url = "https://example.com/login",
        profile = KBProfile("session_001", "/tmp/kbrowser/session_001")
    )

    try {
        // Intercept new window requests
        page.onNewPage = { url ->
            println("New page request: $url")
            // To handle: val newPage = KBrowser.newPage(url)
        }

        // Wait for page loading to complete
        page.loadUrl("https://example.com/login")

        // Option 1: Use Locator (Recommended, CSP-safe)
        page.getByLabel("Username").fill("admin")
        page.getByLabel("Password").type("secret123")  // Physical typing, anti-detection
        page.getByRole("button", name = "Login").click()

        // Wait for dashboard loading
        page.loadUrl("https://example.com/dashboard")

        // Option 2: Use AXTree (Accessibility Tree)
        val rawTree = page.getRawAxTree()
        val cleanTree = rawTree.getCleanedAxTree()
        val viewportTree = cleanTree.getViewportAxTree()

        println("Visible elements in viewport: ${viewportTree.visibleElements}")

        // Find the first link and click it
        val firstLink = viewportTree.nodes.firstOrNull { it.role == "link" }
        if (firstLink != null) {
            page.click(firstLink.refid)
        }

        // Take a screenshot (CSS pixels, aligned with coordinate system)
        val png = page.screenshot()
        if (png != null) {
            File("/tmp/screenshot.png").writeBytes(png)
            println("Screenshot saved, matches coordinates 1:1")
        }

        // Keyboard operations example
        page.locator("css=.search-input").click()
        page.press(KeyboardKey.CONTROL)  // Press modifier key
        page.pressKeyCombination(KeyboardKey.CONTROL, KeyboardKey.A)  // Select all (Ctrl+A)
        page.type("New search terms")

    } catch (e: ElementNotFoundException) {
        println("Element not found: ${e.message}")
    } finally {
        page.close()
    }
}
```
