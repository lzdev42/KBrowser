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
    KBrowser.setConfigPath("/path/to/cache")
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

### KBrowser Object

| Method / Property | Description |
|------|------|
| `KBrowser.setConfigPath(path: String)` | Sets the root directory for KBrowser data. KBrowser creates a `kbrowser_profile` subdirectory inside for its own data (cookies, cache). Must be called before `initializeKBrowser()` and `newPage()`. |
| `KBrowser.newPage(url: String? = null): KBPage` | Creates a new headless browser page, optionally navigating to `url` on creation. KBrowser manages its own profile internally — no profile parameter needed. |
| `KBrowser.pages: StateFlow<List<KBPage>>` | Reactive stream of all currently open pages. Collect with `collectAsState()` to observe page list changes. |
| `KBrowser.getPages(): List<KBPage>` | Synchronously returns a snapshot of all currently open pages. |
| `KBrowser.shutdown()` | Closes all pages and performs global resource cleanup. Call on application exit. |
| `KBrowser.registerPage(page: KBPage)` | Manually registers a `KBPage` into KBrowser's page list. Normally not needed — `newPage()` registers automatically. |
| `KBrowser.unregisterPage(page: KBPage)` | Removes a page from KBrowser's page list. Normally not needed — `page.close()` handles this automatically. |

---

## 2. KBWebView — UI Component Layer

`KBWebView` is a platform-independent interface representing a web page rendering instance. It is created using `rememberKBWebView(initialUrl, profile?)` and rendered using the `@Composable KBWebView` component. The optional `profile` parameter accepts a `KBProfile` to isolate cookies and cache for that WebView instance.

### StateFlows

| Property | Type | Description |
|------|------|------|
| `currentUrl` | `StateFlow<String?>` | Current page URL |
| `currentTitle` | `StateFlow<String?>` | Current page title |
| `loadingState` | `StateFlow<LoadingState>` | Loading state — a sealed interface with subtypes: `Initializing` / `Loading` / `Finished` / `Error(errorCode, description, failingUrl)` |
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

### JS Interaction (Native <-> Web)

KBrowser provides two ways for Web-to-Native communication: one-way callbacks and two-way handlers with Promise support.

| Method | Description |
|------|------|
| `evaluateJavascript(script, callback?)` | Evaluates Javascript from Kotlin, optionally returning the result via callback. |
| `registerJsCallback(name, callback)` | **One-way (Fire-and-Forget)**: Registers a callback in Kotlin. JS calls it via `window.<name>(data)`. Ideal for logging or events where JS doesn't need a response. |
| `unregisterJsCallback(name)` | Unregisters a JS callback. |
| `registerJsHandler(name, handler)` | **Two-way (Request-Response)**: Registers a handler that returns a String. KBrowser injects it as a Promise-based function in JS. JS can call `await window.<name>(data)` to get the result from Kotlin. |
| `unregisterJsHandler(name)` | Unregisters a JS handler. |

**Example: Two-way Promise Handler**
```kotlin
// In Kotlin: Register the handler
webView.registerJsHandler("getConfig") { jsonString ->
    // Process request and return a string
    """{"theme":"dark","version":"1.0"}"""
}
```
```javascript
// In JS: Await the result
async function fetchConfig() {
    try {
        const configStr = await window.getConfig(JSON.stringify({ key: "theme" }));
        console.log(JSON.parse(configStr).theme); // "dark"
    } catch (e) {
        console.error("Handler failed", e);
    }
}
```

### Lifecycle & Others

| Method | Description |
|------|------|
| `clearCacheAndCookies()` | Clears the cache and cookies of the current Profile |
| `destroy()` | Destroys the WebView and releases resources |
| `setWebViewClient(client?)` | Sets callback for page loading events |
| `setWebChromeClient(client?)` | Sets callback for JS dialogs / permissions |
| `suspend takeScreenshot(): ByteArray?` | Takes screenshot via CDP, returning PNG in CSS pixel size. Solves black-screen issues |
| `var onNewWindowRequest: ((url: String) -> Unit)?` | Callback for new tab/window requests; silently discarded if null |
| `setInteractionLocked(locked: Boolean)` | Locks/unlocks user interaction. When `true`, overlays an AWT intercept layer on the browser component that blocks all user mouse/keyboard input; automation (CDP) is unaffected. The overlay renders a mouse trail and click ripple animations as visual feedback during automation. **JVM only; no-op on Android/iOS.** |
| `updateMouseTrail(viewportX: Int, viewportY: Int)` | Updates the mouse trail position on the lock overlay (viewport CSS pixels). Normally no need to call manually — `clickByCoordinates`, `hoverByCoordinates`, and `dragByCoordinates` all call this automatically. **JVM only.** |

> **Click ripple animation**: Regardless of whether `setInteractionLocked` is active, every coordinate-based automation click (`clickByCoordinates`, etc.) triggers a ripple animation at the click position as visual feedback. When locked, the animation renders on the overlay layer; when unlocked, the animation is invisible because the overlay is not mounted. To show the animation without blocking user input, call `setInteractionLocked(true)` first.

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

### Properties

| Property | Type | Description |
|------|------|------|
| `uuid` | `String` | Unique string ID auto-generated when the `KBPage` instance is created. Useful for identifying and tracking pages in multi-page scenarios. |

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

### Interaction Lock & Visual Feedback

| Method | Description |
|------|------|
| `setInteractionLocked(locked: Boolean)` | Locks/unlocks user interaction. When `true`, overlays an AWT intercept layer on the browser that blocks all user mouse/keyboard input while automation (CDP) continues to work normally. The overlay also renders mouse trail and click ripple animations. JVM only; no-op on Android/iOS. |
| `updateMouseTrail(viewportX: Int, viewportY: Int)` | Updates the mouse trail position on the overlay (viewport CSS pixels). Called automatically by `clickByCoordinates`, `hoverByCoordinates`, and `dragByCoordinates`. |

### AXTree (Accessibility Tree)

| Method | Description |
|------|------|
| `suspend getRawAxTree(): AxTreeData` | Retrieves the full accessibility tree and updates the internal coordinates cache `nodeCache`. |
| `suspend snapshot(): String` | Returns the current page as a KBrowser YAML Snapshot string. Fetches AXTree, applies minimal cleaning, converts to tree-structured YAML with text uplifted, coordinates, selectors, and occlusion info inline. Recommended for AI agents. |
| `AxTreeData.getCleanedAxTree(): AxTreeData` | Extension function to filter out technical noise (invisible elements, script/style tags, debug overlay). |
| `AxTreeData.getViewportAxTree(): AxTreeData` | Extension function to crop nodes to the current viewport area. |
| `AxTreeData.toYamlSnapshot(): String` | Converts the tree to KBrowser YAML Snapshot format — tree-structured, text-uplifted, with refid/selector/coordinates/occlusion inline. Recommended for AI agents. See [Snapshot Format Guide](KBrowser_Snapshot_Format.md). |

These two extension functions are pure Kotlin computations. They execute in the caller's coroutine context without switching threads.

### Interaction (refid-based)

Must execute `getRawAxTree()` beforehand to refresh the cache. Throws `ElementNotFoundException` if the refid does not exist.

| Method | Description |
|------|------|
| `suspend click(refid: String)` | Resolves `centerX`/`centerY` from cache and dispatches a physical click via CDP. May fail if the element is covered by an overlay. |
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
| `suspend type(text: String)` | Types text character-by-character with a random delay of 30~150ms to simulate human typing. No focus management — caller is responsible for focusing the target element first. |

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
| `suspend click()` | Locates the element and clicks it using coordinate-based click. |
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
    val iframeSrc: String?,     // Source URL if node is an iframe
    val selector: String,       // Dynamically generated, unique CSS selector bound to this snapshot's DOM. Pass directly to page.locator(selector). Regenerated on every getRawAxTree() so it never goes stale. Resistant to anti-bot class-name obfuscation (falls back to structural nth-of-type path).
    val occludedBy: String?     // refid of the element covering this node's center point, or null if unobstructed. When non-null, a coordinate click will hit the covering element instead. The AI agent should dismiss the covering element first (e.g. close an ad/modal), or use locator(selector).fill() to bypass coordinate hit-testing entirely.
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
```

### LoadingState

`loadingState` is a `sealed interface`. Use `is` checks to handle each state:

```kotlin
sealed interface LoadingState {
    data object Initializing : LoadingState  // WebView just created, not yet loading
    data object Loading : LoadingState       // Currently loading
    data object Finished : LoadingState      // Load complete
    data class Error(                        // Load failed
        val errorCode: Int,
        val description: String,
        val failingUrl: String
    ) : LoadingState
}

// Usage example
val state by webView.loadingState.collectAsState()
when (state) {
    is LoadingState.Loading  -> LinearProgressIndicator()
    is LoadingState.Finished -> Text("Loaded")
    is LoadingState.Error    -> Text("Error: ${(state as LoadingState.Error).description}")
    else -> {}
}
```

### KBSelectorType

The selector type enum used internally by `KBLocator`. Set automatically by `KBPage` factory methods — normally no need to use directly:

```kotlin
enum class KBSelectorType {
    CSS,         // CSS selector, e.g. ".btn-login"
    XPATH,       // XPath, e.g. "//button[@id='submit']"
    TEXT,        // Match by text content
    ROLE,        // Match by ARIA role
    LABEL,       // Match by associated label text
    PLACEHOLDER, // Match by placeholder attribute
    ALT_TEXT,    // Match by alt attribute
    TITLE,       // Match by title attribute
    TEST_ID      // Match by data-testid attribute
}
```

### LocateResult

The parameter type in `KBLocator.filter { }` callbacks, containing basic info about a located element:

```kotlin
data class LocateResult(
    val centerX: Int,                        // Element center X (CSS document pixels)
    val centerY: Int,                        // Element center Y (CSS document pixels)
    val width: Int,                          // Element width
    val height: Int,                         // Element height
    val tagName: String,                     // HTML tag name, e.g. "button"
    val role: String,                        // ARIA role
    val text: String,                        // Element text content
    val isVisible: Boolean,                  // Whether the element is visible
    val attributes: Map<String, String>      // Element attributes map
)
```

### KBProfile

`KBProfile` is for use with `KBWebView` when you need isolated browser data (separate cookies/cache per WebView instance). `KBBrowser` manages its own profile internally — you don't need to pass `KBProfile` to `newPage()`.

```kotlin
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

// Permission request interface
interface PermissionRequest {
    val origin: String                       // Requesting origin domain
    val resources: List<PermissionResource>  // List of requested permissions
    fun grant()                              // Grant the permission
    fun deny()                               // Deny the permission
}

enum class PermissionResource {
    CAMERA,
    MICROPHONE,
    GEOLOCATION,
    PROTECTED_MEDIA_IDENTIFIER,
    AUDIO_CAPTURE,
    VIDEO_CAPTURE
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

        if (loading == LoadingState.Loading) {
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
    // KBrowser.setConfigPath("/tmp/kbrowser")
    // initializeKBrowser()

    val page = KBrowser.newPage(url = "https://example.com/login")

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
            // Option A: refid coordinate click
            page.click(firstLink.refid)

            // Option B: use the node's dynamically-generated selector (anti-bot resistant)
            // page.locator(firstLink.selector).click()
        }

        // Find an input and fill it via its selector
        val searchInput = viewportTree.nodes.firstOrNull { it.role == "textbox" }
        if (searchInput != null) {
            page.locator(searchInput.selector).type("search terms")
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

---

## 8. Debug Utilities

### showScreenshotPreview

```kotlin
fun showScreenshotPreview(bytes: ByteArray)
```

On JVM, opens a standalone Swing preview window displaying the screenshot. Moving the mouse over the window shows real-time 1:1 CSS document pixel coordinates in the title bar — useful for debugging click coordinates. **JVM only; no-op on Android/iOS.**

```kotlin
val png = page.screenshot()
if (png != null) {
    showScreenshotPreview(png)  // Opens preview window with live coordinate display
}
```

### JcefChecker

```kotlin
object JcefChecker {
    val isJcefAvailable: Boolean
}
```

Checks whether the current JDK is a JetBrains Runtime (JBR) with JCEF support. The `KBWebView` Composable checks this internally and shows an error message instead of crashing when `false`. You can also check it at startup to show a friendly prompt:

```kotlin
if (!JcefChecker.isJcefAvailable) {
    println("Please switch the SDK to JBR 25 with JCEF")
}
```
