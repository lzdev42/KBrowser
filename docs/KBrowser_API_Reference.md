# KBrowser API Reference

> [← Back to README](../README.md)

English | [简体中文](KBrowser_API_Reference_zh.md)

> **Coordinate System**: All coordinates in this documentation are in **CSS document pixels**. Screenshot coordinates align 1:1 with interaction coordinates.

---

## 1. Quick Start

JVM platform must be initialized before calling `application {}`:

```kotlin
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import androidx.compose.ui.window.application

fun main() {
    // 1. Configure storage path and rendering mode
    KBrowser.initializeConfig(
        storageDir = "/path/to/cache",
        useOsr = false  // Set to true only if Compose UI overlay on JCEF is needed
    )

    // 2. Initialize JCEF engine (must be called before any UI initialization)
    kotlinx.coroutines.runBlocking {
        initializeKBrowser()
    }

    // 3. Start Compose application
    application {
        Window(onCloseRequest = ::exitApplication) {
            App()
        }
    }
}
```

### Required JVM Arguments

The following JVM arguments must be added to the `compose.desktop` configuration. Without them, OSR mode will not support Chinese input:

```kotlin
compose.desktop {
    application {
        jvmArgs += listOf(
            "--enable-native-access=jcef",
            "--add-opens=jcef/com.jetbrains.cef.remote.browser=ALL-UNNAMED",
            "--add-opens=jcef/com.jetbrains.cef.remote=ALL-UNNAMED"
        )
    }
}
```

### KBrowser Object

| Method / Property | Description |
|-------------------|-------------|
| `KBrowser.initializeConfig(storageDir: String?, useOsr: Boolean = true)` | Configures storage directory and rendering mode. Must be called before `initializeKBrowser()` and `newPage()`. `useOsr` determines the rendering mode (see Rendering Modes section in README) and cannot be changed after initialization. |
| `KBrowser.newPage(url: String? = null, viewportWidth: Int? = null, viewportHeight: Int? = null, headless: Boolean = true): KBPage` | Creates a new browser page. Optionally navigates to `url` on creation. `viewportWidth`/`viewportHeight` set the initial viewport size (defaults to screen size if null). `headless = true` creates a headless WebView with no UI container (JVM: transparent JFrame); `headless = false` creates a WebView for Compose display without a headless container. |
| `KBrowser.pages: StateFlow<List<KBPage>>` | Reactive stream of all currently open pages. |
| `KBrowser.getPages(): List<KBPage>` | Synchronously returns a snapshot of all currently open pages. |
| `KBrowser.shutdown()` | Closes all pages and performs global resource cleanup. |
| `KBrowser.registerPage(page: KBPage)` | Manually registers a `KBPage`. Normally not needed — `newPage()` registers automatically. |
| `KBrowser.unregisterPage(page: KBPage)` | Removes a page from the list. Normally not needed — `page.close()` handles this. |

---

## 2. KBWebView — UI Component Layer

`KBWebView` is a platform-independent interface representing a pure WebView rendering instance. It is created using `rememberKBWebView(initialUrl, profile?)` and rendered using the `@Composable KBWebView` component. The optional `profile` parameter accepts a `KBProfile` to isolate cookies and cache for that WebView instance.

### StateFlows

| Property | Type | Description |
|----------|------|-------------|
| `currentUrl` | `StateFlow<String?>` | Current page URL |
| `currentTitle` | `StateFlow<String?>` | Current page title |
| `loadingState` | `StateFlow<LoadingState>` | Loading state — sealed interface: `Initializing` / `Loading` / `Finished` / `Error(errorCode, description, failingUrl)` |
| `progress` | `StateFlow<Float>` | Loading progress (0.0f to 1.0f) |
| `canGoBack` | `StateFlow<Boolean>` | Whether backward navigation is available |
| `canGoForward` | `StateFlow<Boolean>` | Whether forward navigation is available |

### Navigation Methods

| Method | Description |
|--------|-------------|
| `loadUrl(url: String)` | Loads the specified URL |
| `loadHtml(html: String)` | Loads the HTML string |
| `reload()` | Reloads the current page |
| `stopLoading()` | Stops current loading |
| `goBack()` | Navigates backward |
| `goForward()` | Navigates forward |

### JS Interaction (Native <-> Web)

KBrowser provides two Web-to-Native communication mechanisms:

| Method | Description |
|--------|-------------|
| `evaluateJavascript(script, callback?)` | Evaluates Javascript from Kotlin, optionally returning the result via callback. |
| `registerJsCallback(name, callback)` | **One-way (Fire-and-Forget)**: Registers a callback. JS calls it via `window.<name>(data)`. No return value. |
| `unregisterJsCallback(name)` | Unregisters a JS callback. |
| `registerJsHandler(name, handler)` | **Two-way (Request-Response)**: Registers a handler that returns a String. Injected as a Promise-based function in JS. JS can call `await window.<name>(data)`. |
| `unregisterJsHandler(name)` | Unregisters a JS handler. |

> **Note**: Handlers execute on background threads. Do not operate UI directly inside handlers.

### Lifecycle & Others

| Method | Description |
|--------|-------------|
| `clearCacheAndCookies()` | Clears cache and cookies of the current Profile |
| `destroy()` | Destroys the WebView and releases resources |
| `setWebViewClient(client?)` | Sets callback for page loading events |
| `setWebChromeClient(client?)` | Sets callback for JS dialogs / permissions |
| `suspend takeScreenshot(): ByteArray?` | Takes screenshot via CDP, returning PNG in CSS pixel size |
| `var onNewWindowRequest: ((url: String) -> Unit)?` | Callback for new tab/window requests; silently discarded if null |
| `setInteractionLocked(locked: Boolean)` | Locks/unlocks user interaction. When `true`, overlays an AWT intercept layer that blocks all user mouse/keyboard input; automation (CDP) is unaffected. The overlay renders mouse trail and click ripple animations. **JVM only; no-op on Android/iOS.** |
| `updateMouseTrail(viewportX: Int, viewportY: Int)` | Updates mouse trail position on the overlay (viewport CSS pixels). Called automatically by coordinate-based automation methods. **JVM only.** |
| `var onFileDialogRequest: ((request: KBFileDialogRequest, callback: KBFileDialogCallback) -> Unit)?` | Callback for file dialog requests. When set, file selection is delegated to the caller; when not set, file dialogs are silently cancelled. |

### Composable Usage Example

```kotlin
@Composable
fun BrowserScreen() {
    val webView = rememberKBWebView(initialUrl = "https://example.com")

    LaunchedEffect(webView) {
        webView.setWebViewClient(object : KBWebViewClient {
            override fun onPageStarted(url: String) {}
            override fun onPageFinished(url: String) {}
            override fun onReceivedError(error: Diagnostics) {}
        })
        webView.onNewWindowRequest = { url -> webView.loadUrl(url) }
    }

    Column(Modifier.fillMaxSize()) {
        KBWebView(webView = webView, modifier = Modifier.weight(1f))
        Row {
            Button(onClick = { webView.goBack() }) { Text("←") }
            Button(onClick = { webView.goForward() }) { Text("→") }
            Button(onClick = { webView.reload() }) { Text("↺") }
        }
    }
}
```

---

## 3. KBPage — Automation Layer

`KBPage` is a coroutine-based wrapper around `KBWebView`. All its `suspend` methods internally switch to `Dispatchers.Main`, so they can be safely called from any coroutine context. Created via `KBrowser.newPage()`.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `uuid` | `String` | Unique string ID generated at creation |
| `webView` | `KBWebView` | The underlying WebView instance |

### StateFlows

Delegates directly to `KBWebView`: `currentUrl`, `title`, `loadingState`, `progress`.

### Navigation

| Method | Description |
|--------|-------------|
| `suspend loadUrl(url: String)` | Loads the URL and suspends until `onPageFinished`. Cancelling calls `stopLoading()`. |
| `suspend evaluateJavascript(script: String): String` | Evaluates Javascript, returns result as string |
| `suspend clearCacheAndCookies()` | Clears cache and cookies |
| `suspend setCookieViaJs(cookieString: String)` | Injects cookie via `document.cookie` |
| `suspend screenshot(): ByteArray?` | CDP screenshot, PNG bytes in CSS pixel size |
| `close()` | Destroys the underlying WebView |

### Interaction Lock & Visual Feedback

| Method | Description |
|--------|-------------|
| `setInteractionLocked(locked: Boolean)` | Locks/unlocks user interaction. JVM only; no-op on Android/iOS. |
| `updateMouseTrail(viewportX: Int, viewportY: Int)` | Updates mouse trail position. Called automatically by coordinate-based methods. |

### AXTree (Accessibility Tree)

| Method | Description |
|--------|-------------|
| `suspend getRawAxTree(): AxTreeData` | Retrieves the full accessibility tree and updates the internal node cache |
| `suspend snapshot(mode: SnapshotMode = SnapshotMode.VIEWPORT): SnapshotResult` | Returns a `SnapshotResult` containing both the YAML string and the raw `AxTreeData` from the same fetch, guaranteeing refid consistency |
| `AxTreeData.getCleanedAxTree(): AxTreeData` | Extension: filters out invisible elements, script/style tags, debug overlays |
| `AxTreeData.getViewportAxTree(): AxTreeData` | Extension: crops nodes to the current viewport area |
| `AxTreeData.toYamlSnapshot(mode: SnapshotMode = SnapshotMode.VIEWPORT): String` | Converts to KBrowser YAML Snapshot format. See [Snapshot Format](KBrowser_Snapshot_Format.md). |

Extension functions are pure Kotlin computations that execute in the caller's coroutine context.

#### SnapshotMode Enum

```kotlin
enum class SnapshotMode {
    VIEWPORT,  // Viewport-only compact serialization, suitable for AI consumption
    CLEAN      // Full-page compact serialization, noise removed but all nodes preserved (including off-viewport)
}
```

| Mode | Off-viewport Nodes | StaticText / Pseudo-elements | Empty Containers | Use Case |
|------|-------------------|------------------------------|-----------------|----------|
| `VIEWPORT` | Filtered out | Filtered out | Filtered out | AI consumption, minimal tokens |
| `CLEAN` | Preserved | Filtered out | Filtered out | Full page structure needed, minus noise |

#### SnapshotResult Data Class

```kotlin
data class SnapshotResult(
    val yaml: String,       // KBrowser YAML Snapshot string, for AI consumption
    val rawTree: AxTreeData // Raw data from the same fetch, refids consistent with yaml
)
```

> **Important**: The `yaml` and `rawTree` in `SnapshotResult` come from the same `getRawAxTree()` call, guaranteeing refid consistency. Do not call `snapshot()` and `getRawAxTree()` separately, as refids may change between calls.

#### Usage Example

```kotlin
// Get viewport-only YAML for AI, with raw data preserved
val result = page.snapshot(SnapshotMode.VIEWPORT)
val yaml = result.yaml          // For AI
val rawTree = result.rawTree    // Raw data for further processing

// Get full-page YAML (includes off-viewport nodes)
val fullResult = page.snapshot(SnapshotMode.CLEAN)
```

### Interaction (refid-based)

Must execute `getRawAxTree()` beforehand to refresh the cache. Throws `ElementNotFoundException` if the refid does not exist.

#### Coordinate Mode (Physical System Events)

| Method | Description |
|--------|-------------|
| `suspend click(refid: String): OperationResult` | Physical click on element coordinates. Verifies via `elementFromPoint` after click. |
| `suspend hover(refid: String)` | Physical hover on element coordinates |
| `suspend scroll(refid: String, deltaX: Int, deltaY: Int): OperationResult` | Physical scroll on element coordinates. Verifies via `scrollTop` comparison. |
| `suspend drag(startRefid: String, endRefid: String)` | Physical drag between start and end elements |

#### JS Mode (DOM Event Simulation)

| Method | Description |
|--------|-------------|
| `suspend jsClick(refid: String)` | DOM `.click()` |
| `suspend jsHover(refid: String)` | DOM `mouseover` / `mouseenter` / `mousemove` events |
| `suspend jsScroll(refid: String, deltaX: Int, deltaY: Int)` | DOM `.scrollBy(dx, dy)` |
| `suspend jsDrag(startRefid: String, endRefid: String)` | DOM drag event sequence |

### Interaction (Coordinates-based, CSS document pixels)

| Method | Description |
|--------|-------------|
| `suspend clickByCoordinates(x: Int, y: Int): OperationResult` | CDP `Input.dispatchMouseEvent`. Verifies target exists at coordinates after click. |
| `suspend hoverByCoordinates(x: Int, y: Int)` | CDP hover |
| `suspend scrollByCoordinates(x: Int, y: Int, deltaX: Int, deltaY: Int): OperationResult` | CDP wheel scroll. Verifies scroll position changed. |
| `suspend dragByCoordinates(startX: Int, startY: Int, endX: Int, endY: Int)` | CDP drag sequence |

### Keyboard

| Method | Description |
|--------|-------------|
| `suspend press(key: KeyboardKey)` | Presses and releases a single key |
| `suspend pressKeyCombination(modifier: KeyboardKey, key: KeyboardKey)` | Key combination, e.g. `Ctrl+A` |
| `suspend typeChar(char: Char)` | Types a single character |
| `suspend type(text: String)` | Types text character-by-character with 30~150ms random delay. Caller is responsible for focusing the target element first. |

### File Upload

| Method | Description |
|--------|-------------|
| `suspend uploadFile(refid: String, filePaths: List<String>)` | Sets files on `input[type=file]` via CDP `DOM.setFileInputFiles`. No dialog, no user gesture needed. **JVM only; throws `UnsupportedOperationException` on Android/iOS.** |
| `suspend uploadFileBySelector(selector: String, filePaths: List<String>)` | Same as above but uses CSS selector directly, not dependent on AX tree cache. **JVM only.** |

### Locator Factory

| Method | Description |
|--------|-------------|
| `locator("css=...")` or `locator("xpath=...")` | CSS/XPath locator, defaults to CSS |
| `getByRole(role, name?)` | Locates by ARIA role, optional name filter |
| `getByText(text, exact)` | Locates by text content |
| `getByLabel(label)` | Locates by associated label |
| `getByPlaceholder(text)` | Locates by placeholder attribute |
| `getByAltText(text)` | Locates by alt attribute |
| `getByTitle(title)` | Locates by title attribute |
| `getByTestId(testId)` | Locates by `data-testid` attribute |

### New Window & File Dialog

| Property | Description |
|----------|-------------|
| `var onNewPage: ((url: String) -> Unit)?` | Delegates to `webView.onNewWindowRequest`. Silently discarded if null. |
| `var onFileDialog: ((request: KBFileDialogRequest, callback: KBFileDialogCallback) -> Unit)?` | Delegates to `webView.onFileDialogRequest`. JVM: set to handle file selection; not set: silently cancelled. Android/iOS: native file dialog. |

---

## 4. KBLocator — Declarative Locator

`KBLocator` evaluates lazily, re-finding elements for every action. JVM platform prefers CDP (no JS injection, CSP-safe); Android/iOS falls back to JS.

### Coordinate Mode (Default)

| Method | Description |
|--------|-------------|
| `suspend click(): OperationResult` | Physical click on element center. Returns verification result. |
| `suspend hover()` | Physical hover |
| `suspend scroll(deltaX, deltaY): OperationResult` | Physical scroll. Returns verification result. |
| `suspend fill(value: String): OperationResult` | Click to focus → wait 100ms → set value via JS and fire events. Verifies `el.value` matches. |
| `suspend type(text: String): OperationResult` | Click to focus → Ctrl+A → Delete → character-by-character physical input. Verifies `el.value` matches. |
| `suspend focus()` | Click to focus |
| `suspend check()` | Click on checkbox/radio |
| `suspend selectOption(value: String)` | Click dropdown → click matching option |
| `suspend press(key: KeyboardKey)` | Click to focus → physical keystroke |
| `suspend pressKeyCombination(modifier, key)` | Click to focus → physical modifier+key |

### JS Mode

| Method | Description |
|--------|-------------|
| `suspend jsClick()` | DOM `.click()` |
| `suspend jsHover()` | DOM `mouseover`, `mouseenter`, `mousemove` events |
| `suspend jsScroll(deltaX, deltaY)` | DOM `.scrollBy(dx, dy)` |
| `suspend jsFill(value: String): OperationResult` | JS `.focus()` → wait 100ms → set value via JS. Verifies `el.value` matches. |
| `suspend jsType(text: String): OperationResult` | JS `.focus()` → Ctrl+A → Delete → physical keystrokes. Verifies `el.value` matches. |
| `suspend jsFocus()` | DOM `.focus()` |
| `suspend jsCheck()` | JS: set checked + fire change event |
| `suspend jsSelectOption(value: String)` | JS: set dropdown value + fire change event |
| `suspend jsPress(key: KeyboardKey)` | JS focus → physical keystroke |
| `suspend jsPressKeyCombination(modifier, key)` | JS focus → physical modifier+key |

### Query Methods

| Method | Description |
|--------|-------------|
| `suspend isVisible(): Boolean` | Whether the element is visible |
| `suspend getText(): String` | Gets text content |
| `suspend getAttribute(name: String): String?` | Gets attribute value |
| `suspend count(): Int` | Number of matching elements |
| `suspend boundingBox(): Rect?` | Bounding box in CSS document pixels |

### Chainable Filters

| Method | Description |
|--------|-------------|
| `filter(predicate: (LocateResult) -> Boolean): KBLocator` | Further filters matched results |
| `nth(index: Int): KBLocator` | Selects the n-th match (0-indexed, -1 = last) |
| `first(): KBLocator` | Selects the first match |
| `last(): KBLocator` | Selects the last match |

---

## 5. Operation Verification

Interaction methods (`click`, `fill`, `type`, `scroll`) return an `OperationResult` that programmatically verifies whether the operation succeeded. This allows callers (especially AI agents) to detect failures without re-fetching a full page snapshot.

> **Anti-bot safety**: All verification uses read-only JS APIs (`elementFromPoint`, `el.value`, `scrollTop`). Zero detection risk.

### OperationResult

```kotlin
sealed class OperationResult {
    abstract val success: Boolean

    data class Success(
        val action: String,       // "click", "fill", "type", "scroll"
        val verified: Boolean,    // true = programmatically verified; false = event dispatched but identity not confirmed
        val detail: String = ""   // e.g. "hit #submitBtn", "value='hello'", "scrollTop: 0 → 150"
    ) : OperationResult()

    data class Failure(
        val action: String,
        val reason: String,       // e.g. "hit #overlay, expected #submitBtn (occluded?)"
        val recoverable: Boolean = true
    ) : OperationResult()

    data object Acknowledged : OperationResult()  // dispatched but not verifiable (e.g. hover)
}
```

### Verification Strategies

| Operation | Strategy | Detail |
|-----------|----------|--------|
| **click** (with refid) | `document.elementFromPoint()` matches target `id`/`tagName`, walking up DOM tree | Detects occlusion by overlays/modals |
| **clickByCoordinates** | `elementFromPoint()` confirms an element exists at the click point | No identity check without target info |
| **fill / type** | Reads back `el.value` (or `el.innerText` for contenteditable) and compares with expected value | Detects silent input failures |
| **scroll** | Compares `scrollTop` (or `window.scrollY`) before and after | Detects no-op scrolls |

### Usage

```kotlin
// Check result
val result = page.click("r12")
if (!result.success) {
    println("Click failed: $result")
    // AI can decide: close overlay and retry, or switch to jsClick
}

// Ignore result (also valid)
page.click("r12")
locator.fill("hello")

// Occlusion detection
val result = page.click("r15")
if (result is OperationResult.Failure) {
    println(result.reason)  // "hit #cookie-banner, expected #submitBtn (occluded?)"
    // Close the banner, re-snapshot, retry
}

// Fill verification
val fillResult = locator.fill("user@email.com")
if (fillResult is OperationResult.Success && fillResult.verified) {
    println("Confirmed: ${fillResult.detail}")  // "value='user@email.com'"
}
```

### Methods Returning OperationResult

| Class | Methods |
|-------|--------|
| `KBPage` | `click(refid)`, `scroll(refid, dx, dy)`, `clickByCoordinates(x, y)`, `scrollByCoordinates(x, y, dx, dy)` |
| `KBLocator` | `click()`, `scroll(dx, dy)`, `fill(value)`, `type(text)`, `jsFill(value)`, `jsType(text)` |

Methods that cannot be verified (`hover`, `drag`, keyboard events) retain their original `Unit` return type.

---

## 6. Data Structures

### AxNode

All coordinates are in **CSS document pixels**.

```kotlin
data class AxNode(
    val refid: String,          // Unique node ID for interactions
    val tagName: String,        // HTML tag name
    val role: String,           // ARIA role
    val id: String,             // DOM id attribute
    val className: String,      // CSS class
    val text: String,           // Node text content
    val isVisible: Boolean,     // Whether visible in viewport
    val x: Int,                 // Top-left X (CSS document pixels)
    val y: Int,                 // Top-left Y (CSS document pixels)
    val width: Int,             // Width
    val height: Int,            // Height
    val centerX: Int,           // Center X (CSS document pixels)
    val centerY: Int,           // Center Y (CSS document pixels)
    val childCount: Int,        // Number of children
    val attributes: Map<String, String>,
    val iframeSrc: String?,
    val selector: String,       // Dynamic unique CSS selector, regenerated per getRawAxTree()
    val occludedBy: String?     // refid of covering element, or null
)
```

### AxTreeData

```kotlin
data class AxTreeData(
    val url: String,
    val innerWidth: Int,
    val innerHeight: Int,
    val scrollX: Int,
    val scrollY: Int,
    val documentWidth: Int,
    val documentHeight: Int,
    val devicePixelRatio: Double,
    val totalElements: Int,
    val visibleElements: Int,
    val hiddenElements: Int,
    val iframeCount: Int,
    val nodes: List<AxNode>
)
```

### SnapshotMode Enum

```kotlin
enum class SnapshotMode {
    VIEWPORT,  // Viewport-only compact serialization, suitable for AI consumption
    CLEAN      // Full-page compact serialization, noise removed but all nodes preserved (including off-viewport)
}
```

### SnapshotResult

```kotlin
data class SnapshotResult(
    val yaml: String,       // KBrowser YAML Snapshot string
    val rawTree: AxTreeData // Raw data from the same fetch
)
```

### KeyboardKey Enum

```kotlin
enum class KeyboardKey {
    ENTER, TAB, ESCAPE, BACKSPACE, DELETE,
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
    SHIFT, CONTROL, ALT, META,
    SPACE, HOME, END, PAGE_UP, PAGE_DOWN, INSERT,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
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

```kotlin
sealed interface LoadingState {
    data object Initializing : LoadingState
    data object Loading : LoadingState
    data object Finished : LoadingState
    data class Error(
        val errorCode: Int,
        val description: String,
        val failingUrl: String
    ) : LoadingState
}
```

### KBProfile

```kotlin
data class KBProfile(val profileId: String, val storageDir: String)
```

Used with `KBWebView` for isolated browser data per instance. `KBrowser.newPage()` manages its own profile internally.

---

## 7. Callbacks

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
```

---

## 8. Debug Utilities

### showScreenshotPreview

```kotlin
fun showScreenshotPreview(bytes: ByteArray)
```

On JVM, opens a standalone Swing preview window displaying the screenshot. Mouse movement shows real-time CSS document pixel coordinates in the title bar. **JVM only; no-op on Android/iOS.**

### JcefChecker

```kotlin
object JcefChecker {
    val isJcefAvailable: Boolean
}
```

Checks whether the current JDK is JetBrains Runtime with JCEF support. The `KBWebView` Composable checks this internally and shows an error message when `false`.
