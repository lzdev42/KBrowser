# KBrowser Architecture Design

> [← Back to README](../README.md)

English | [简体中文](KBrowser_Architecture_Design_zh.md)

---

## 1. Framework Positioning

KBrowser is a Compose Multiplatform (KMP) library providing cross-platform WebView components and programmatic browser automation. All public classes and interfaces use the `KB` prefix.

The framework is structured in two layers:
- **UI Component Layer** (`KBWebView` interface + `@Composable KBWebView`): A pure WebView component responsible for web rendering and display.
- **Automation Layer** (`object KBrowser` singleton + `KBPage`): A coroutine-based programmatic control wrapper around `KBWebView`. It abstracts thread details and can be safely called from any coroutine context.

On JVM/Desktop, the underlying engine is JetBrains CEF (JCEF) running in Remote mode. All interactions are conducted via the Chrome DevTools Protocol (CDP), without relying on AWT mouse events.

## 2. Core Class Diagram

```mermaid
classDiagram
    direction TB

    class KBrowser {
        <<object Singleton>>
        +initializeConfig(storageDir: String?, useOsr: Boolean)
        +newPage(profile: KBProfile?) KBPage
        +newHeadlessTab(profile: KBProfile?, viewportWidth: Int, viewportHeight: Int) KBPage
        +pages: StateFlow~List~KBPage~~
        +getPages() List~KBPage~
        +shutdown()
    }

    class KBPage {
        +KBWebView webView
        +String uuid
        +StateFlow~String?~ currentUrl
        +StateFlow~String?~ title
        +StateFlow~LoadingState~ loadingState
        +StateFlow~Float~ progress
        +suspend loadUrl(url: String)
        +suspend evaluateJavascript(script: String) String
        +suspend clearCacheAndCookies()
        +suspend setCookieViaJs(cookieString: String)
        +suspend snapshot(mode: SnapshotMode) SnapshotResult
        +suspend click(refid: String) OperationResult
        +suspend hover(refid: String)
        +suspend scroll(refid: String, deltaX: Int, deltaY: Int) OperationResult
        +suspend drag(startRefid: String, endRefid: String)
        +suspend jsClick(refid: String)
        +suspend jsHover(refid: String)
        +suspend jsScroll(refid: String, deltaX: Int, deltaY: Int)
        +suspend jsDrag(startRefid: String, endRefid: String)
        +suspend clickByCoordinates(x: Int, y: Int) OperationResult
        +suspend hoverByCoordinates(x: Int, y: Int)
        +suspend scrollByCoordinates(x: Int, y: Int, deltaX: Int, deltaY: Int) OperationResult
        +suspend dragByCoordinates(startX: Int, startY: Int, endX: Int, endY: Int)
        +suspend press(key: KeyboardKey)
        +suspend pressKeyCombination(modifier: KeyboardKey, key: KeyboardKey)
        +suspend typeChar(char: Char)
        +suspend type(text: String)
        +suspend uploadFile(refid: String, filePaths: List~String~)
        +suspend uploadFileBySelector(selector: String, filePaths: List~String~)
        +suspend screenshot() ByteArray?
        +locator(selector: String) KBLocator
        +getByRole(role, name?) KBLocator
        +getByText(text, exact) KBLocator
        +getByLabel(label) KBLocator
        +getByPlaceholder(text) KBLocator
        +getByAltText(text) KBLocator
        +getByTitle(title) KBLocator
        +getByTestId(testId) KBLocator
        +setInteractionLocked(locked: Boolean)
        +updateMouseTrail(viewportX: Int, viewportY: Int)
        +var onNewPage: ((url: String) -> Unit)?
        +var onFileDialog: ((request, callback) -> Unit)?
        +close()
        -suspend getRawAxTree() AxTreeData
        -nodeCacheWriteLock: Mutex
        -nodeCache: Map~String, AxNode~
    }

    class KBWebView {
        <<interface>>
        +StateFlow~String?~ currentUrl
        +StateFlow~String?~ currentTitle
        +StateFlow~LoadingState~ loadingState
        +StateFlow~Float~ progress
        +StateFlow~Boolean~ canGoBack
        +StateFlow~Boolean~ canGoForward
        +loadUrl(url: String)
        +loadHtml(html: String)
        +reload()
        +stopLoading()
        +goBack()
        +goForward()
        +evaluateJavascript(script: String, callback: ((String) -> Unit)?)
        +registerJsCallback(name, callback)
        +unregisterJsCallback(name)
        +registerJsHandler(name, handler)
        +unregisterJsHandler(name)
        +clearCacheAndCookies()
        +setWebViewClient(client?)
        +setWebChromeClient(client?)
        +suspend takeScreenshot() ByteArray?
        +setInteractionLocked(locked: Boolean)
        +updateMouseTrail(viewportX: Int, viewportY: Int)
        +var onNewWindowRequest: ((url: String) -> Unit)?
        +var onFileDialogRequest: ((request, callback) -> Unit)?
        +destroy()
    }

    class KBLocator {
        +KBPage page
        +String selector
        +KBSelectorType selectorType
        +String? name
        +Boolean exact
        +suspend click() OperationResult
        +suspend hover()
        +suspend scroll(deltaX: Int, deltaY: Int) OperationResult
        +suspend fill(value: String) OperationResult
        +suspend type(text: String) OperationResult
        +suspend focus()
        +suspend check()
        +suspend selectOption(value: String)
        +suspend press(key: KeyboardKey)
        +suspend pressKeyCombination(modifier: KeyboardKey, key: KeyboardKey)
        +suspend jsClick()
        +suspend jsHover()
        +suspend jsScroll(deltaX: Int, deltaY: Int)
        +suspend jsFill(value: String) OperationResult
        +suspend jsType(text: String) OperationResult
        +suspend jsFocus()
        +suspend jsCheck()
        +suspend jsSelectOption(value: String)
        +suspend jsPress(key: KeyboardKey)
        +suspend jsPressKeyCombination(modifier: KeyboardKey, key: KeyboardKey)
        +suspend isVisible() Boolean
        +suspend getText() String
        +suspend getAttribute(name: String) String?
        +suspend count() Int
        +suspend boundingBox() Rect?
        +filter(predicate) KBLocator
        +nth(index: Int) KBLocator
        +first() KBLocator
        +last() KBLocator
    }

    KBrowser "1" *-- "many" KBPage
    KBPage "1" *-- "1" KBWebView
    KBLocator ..> KBPage
```

## 3. Coordinate System

**Globally unified under CSS document pixels.**

| Scenario | Implementation | Description |
|----------|---------------|-------------|
| Click / Hover | CDP `Input.dispatchMouseEvent` | Dispatches viewport coordinates: `viewportX = docX - scrollX`, `viewportY = docY - scrollY` |
| Screenshot | CDP `Page.captureScreenshot` → scaled down by DPR | Output image dimensions = CSS pixel dimensions, 1:1 aligned with coordinates |
| AXTree Node Coordinates | CDP `Accessibility.getFullAXTree` + `DOM.getBoxModel` | `x/y/centerX/centerY` are all in CSS document pixels |
| Locator Positioning | JVM: CDP `DOM.querySelectorAll` / `DOM.performSearch` / `Accessibility.getFullAXTree` (no JS injection, CSP-safe); Android/iOS: JS fallback | Returned coordinates are also in CSS document pixels |

> There is no DPR scaling ambiguity. Screenshot coordinates match interaction coordinates exactly.

## 4. Rendering Modes (JVM Desktop)

On JVM, JCEF supports two rendering modes determined at initialization. **OSR (`useOsr = true`) is the default** — it is the only mode that supports overlaying Compose UI on top of the browser. Non-OSR is an escape hatch for performance-critical scenarios where you accept that no Compose UI may be drawn over the browser.

### OSR Mode (Off-Screen Rendering, `useOsr = true`) — default

JCEF renders into an off-screen buffer, and the result is painted as a lightweight component. This allows Compose UI to be layered on top of the JCEF view. However, mouse and keyboard events are dispatched to the underlying JCEF native view, not to overlay Compose components. Interactive Compose components placed over the JCEF area will not respond to user input. This is a known issue with low priority.

Per frame, OSR requires a GPU → CPU → GPU pixel round-trip, so it has higher CPU/GPU overhead than non-OSR.

### Non-OSR Mode (Native Window, `useOsr = false`)

JCEF creates a native heavyweight window component. The browser renders directly through the native window system, providing the best performance. However, the heavyweight component renders on top of all lightweight Swing/Compose components, making it impossible to overlay Compose UI on top of the JCEF view.

**macOS live-resize caveat**: On macOS, browser content does not update while dragging window or splitter edges — it refreshes once the drag is released. This is a CEF + Core Animation architecture limitation (the AWT event queue is blocked and Core Animation does not commit frames during live-resize) that cannot be worked around from Java/AWT. See [jcef-resize-fix-plan.md](jcef-resize-fix-plan.md). Non-OSR is nonetheless a fully supported mode for display and is used in production (e.g. when no Compose overlay is needed and maximum rendering performance is required).

### Chinese Input in OSR Mode

In OSR mode, CEF has no native window to detect focus. Chinese input requires two conditions to be satisfied simultaneously:

1. **JVM arguments**: `--add-opens=jcef/com.jetbrains.cef.remote.browser=ALL-UNNAMED` and `--add-opens=jcef/com.jetbrains.cef.remote=ALL-UNNAMED`, which open reflective access to JCEF internal classes (`ImeSetComposition` / `ImeCommitText` are invoked via reflection)
2. **Focus synchronization**: The embedder must explicitly call `CefBrowser.setFocus(true)` to sync focus state. Without focus sync, CEF internally considers the browser unfocused and silently drops all IME requests, while `sendKeyEvent` ignores focus so English works — this is the root cause of Chinese input failure

KBrowser internally implements full OSR Chinese input support via `KBCefInputMethodAdapter` (IME event forwarding) and `ensureCefFocus()` (focus synchronization). In non-OSR mode, JCEF uses a native window where IME is handled natively by the OS, so this issue does not apply.

### Automatic Fallback

If OSR initialization fails (e.g., missing native libraries), `OsrMode` automatically falls back to non-OSR mode for the entire application lifecycle. This is transparent to the caller.

## 5. Interaction Modes and Execution Paths

KBrowser provides two parallel interaction models, clearly separated by naming conventions:

### Coordinate Mode (Physical System Events)
* **API Examples**: `page.click(refid)`, `locator.click()`, `page.clickByCoordinates(x, y)`
* **Mechanism**: Resolves element coordinates from cache or locator, converts to viewport coordinates, and dispatches physical pointer events via CDP.
* **Pros**: Real physical pointer simulation, bypasses basic anti-bot detection.
* **Cons**: Susceptible to element occlusion or elements scrolled out of the viewport.

### JS Mode (DOM Event Simulation)
* **API Examples**: `page.jsClick(refid)`, `locator.jsClick()`, `locator.jsFill("val")`
* **Mechanism**: Resolves the element's CSS selector and directly executes JavaScript in the DOM.
* **Pros**: Immune to occlusion, overlap, or off-viewport issues. Precise value modification.
* **Cons**: Advanced anti-bot systems may detect synthetic events via `.isTrusted` flag.

## 6. Platform Requirements

| Platform | Minimum Version | WebView Implementation | Remarks |
|----------|----------------|----------------------|---------|
| JVM/Desktop | JBR with JCEF | `JvmWebView` wrapping JCEF | Must use JetBrains Runtime with JCEF; the library does not bundle JCEF |
| Android | API 34 (Android 14) | `AndroidWebView` wrapping system `WebView` | Uses androidx.webkit Multi-Profile API |
| iOS | iOS 17.0+ | `IosWebView` wrapping `WKWebView` | Uses `WKWebsiteDataStore(forIdentifier:)` for isolation |

**Initialization Order (JVM must strictly follow)**:
```kotlin
KBrowser.initializeConfig(storageDir = "/path/to/cache", useOsr = true) // default; false only for max performance with no overlay
runBlocking { initializeKBrowser() }
application { /* Compose UI */ }
```

## 7. Threading Model

- All `suspend` methods of `KBPage` internally switch to `Dispatchers.Main` via `withContext`, allowing them to be called from any coroutine context.
- Asynchronous callbacks (JS evaluation results, page load completion) are converted to suspended states using `suspendCancellableCoroutine` without blocking threads.
- CPU-intensive operations (`AxTreeData.getCleanedAxTree()`, `AxTreeData.toYamlSnapshot()`) are pure Kotlin extension functions that execute in the caller's coroutine context. `getCleanedAxTree()` actually filters nodes within the current viewport (same viewport-range logic as `toYamlSnapshot(VIEWPORT)`).
- `KBrowser.pages` uses `MutableStateFlow` + `update {}` for atomic updates, ensuring safety across coroutines.
- Cancelled `loadUrl` coroutines automatically call `webView.stopLoading()`.
- `KBPage` node cache: write operations are serialized via `Mutex`, read operations use `@Volatile` for visibility. Reads never deadlock with writes.
