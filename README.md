# KBrowser

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF)](https://kotlinlang.org/docs/multiplatform.html)

English | [简体中文](README_zh.md)

> **Work in Progress** — APIs are subject to change without notice. iOS and Android platforms have not been tested.

**KBrowser** is a Kotlin Multiplatform library that provides:

1. **`KBWebView`** — A cross-platform WebView UI component for Android, iOS, Desktop (JVM), and WasmJs (Browser). Pure WebView abstraction with a unified API similar to `WKWebView` / Android `WebView`.
2. **`KBPage`** — A Playwright-inspired browser automation wrapper around `KBWebView` for Desktop (JVM). Built on Chrome DevTools Protocol (CDP): AXTree extraction, CSP-safe element location, anti-detection physical clicks, screenshot capture, coroutine-based thread safety.

---

## Quick Start

### 0. Pick the right API

**Show a page with `KBWebView`. Operate on a page with `KBPage`.**

| Your goal | Use this |
|-----------|----------|
| **Show** a web page in your Compose UI (browser view, embedded page) | **`KBWebView`** Composable + `rememberKBWebView()` |
| **Operate** on a page — automation, scraping, screenshots, AI agents | **`KBPage`** (`KBrowser.newPage()`) |

- `KBWebView` only renders and handles user interaction; it cannot click, fill, snapshot, or screenshot for you — those live on `KBPage`. (Both have a `loadUrl`, but with different semantics — see § 6.)
- Want display **and** automation on the same view? Create a viewport-less `KBPage` and mount its `webView` in the `KBWebView` Composable — that's what the Demo's browser mode does.

### 1. Add Dependency

In `gradle/libs.versions.toml`:

```toml
[versions]
kbrowser = "0.1.0-alpha46"

[libraries]
kbrowser = { module = "io.github.lzdev42:kbrowser", version.ref = "kbrowser" }
```

In your module's `build.gradle.kts`:

```kotlin
implementation(libs.kbrowser)
```

### 2. Desktop (JVM): Configure JBR

**Must use [JetBrains Runtime (JBR) with JCEF](https://github.com/JetBrains/JetBrainsRuntime) — standard JDK will not work.** JCEF classes ship with the JBR runtime itself; they are not part of the KBrowser library nor of any Maven artifact.

Add the required JVM arguments to `compose.desktop` (without them, OSR mode **cannot input Chinese/CJK text** — English is unaffected, which makes this easy to misdiagnose as a "broken IME"):

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

**⚠️ The Gradle daemon's JVM must also be JBR**, otherwise you get "JCEF not installed" at runtime (`JcefChecker.isJcefAvailable == false`) — `:desktopApp:run` and every `JavaExec` task run on the daemon's JVM by default, and the daemon defaults to a standard JDK (Zulu/Corretto/Temurin etc.) which contains no JCEF classes, even when the JBR is installed on your system.

Fix (either of, then `./gradlew --stop` to restart daemons):

- **IDE setting (recommended)**: IDEA → `Settings` → `Build, Execution, Deployment` → `Build Tools` → `Gradle` → **Gradle JVM** → select your JBR+JCEF.
- **User-level `~/.gradle/gradle.properties`** (machine-specific path, do not commit it to the repo):

```properties
org.gradle.java.home=/Users/yourname/Library/Java/JavaVirtualMachines/jbrsdk_jcef-25.0.3/Contents/Home
```

> `compose.desktop.application.javaHome` only affects the `:run` task — it does not cover the daemon itself nor other `JavaExec` tasks, so it is not recommended. `nativeDistributions` (DMG/MSI/DEB) bundles the JBR, so distributed apps are self-contained and require none of the above.
>
> **Verify**: `ps -o comm= -p $(jps | grep GradleDaemon | awk '{print $1}')` shows the JBR path when configured correctly.

### 3. Initialize the engine (OSR usage)

Engine initialization must complete **before** creating any `KBWebView` / `KBPage`. `useOsr` is the rendering mode and **cannot be changed after startup**. Both patterns below are used in this repo (Pattern B is the Demo's approach) — pick one.

**Pattern A — synchronous init in `main()`** (simplest, for "browser-on-launch" apps):

```kotlin
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import xyz.kbrowser.getDefaultStorageDir
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    KBrowser.initializeConfig(
        storageDir = getDefaultStorageDir(),  // platform default cache dir; or a custom path
        useOsr = true                          // default; see Rendering Modes below
    )
    kotlinx.coroutines.runBlocking { initializeKBrowser() }  // suspend, waits for JCEF to be ready

    application {
        Window(onCloseRequest = ::exitApplication) { App() }
    }
}
```

**Pattern B — async init inside Compose with loading indicator + mode selection** (the Demo's approach, for apps that let the user choose a mode first):

```kotlin
var isInitialized by remember { mutableStateOf(false) }
val scope = rememberCoroutineScope()

if (!isInitialized) {
    // Show mode selection / loading screen first
    ModeSelectionScreen(onModeSelected = { useOsr ->
        scope.launch {
            KBrowser.initializeConfig(getDefaultStorageDir(), useOsr = useOsr)
            initializeKBrowser()   // suspend
            isInitialized = true   // only now switch to the WebView-containing screen
        }
    })
} else {
    MainScreen()
}
```

Key constraints:
- Call `initializeConfig()` exactly once, before any other KBrowser API. It is a plain setter with no guard — calling it again after the engine has started will not reconfigure anything.
- `initializeKBrowser()` must complete before any `KBWebView` / `KBPage` is created (Pattern B gates the UI on `isInitialized` to guarantee this).

### 4. Display a web page — `KBWebView`

Minimal usage: create the instance with `rememberKBWebView`, then mount it with the `KBWebView` Composable. Full API (navigation, JS bridge, callbacks) in [API Reference § 2](docs/KBrowser_API_Reference.md#2-kbwebview--ui-component-layer).

```kotlin
@Composable
fun BrowserScreen() {
    val webView = rememberKBWebView(initialUrl = "https://example.com")

    KBWebView(webView = webView, modifier = Modifier.fillMaxSize())
}
```

### 5. Programmatic control — `KBPage`

`KBrowser.newPage()` creates a page (pass `viewportWidth`/`viewportHeight` for background automation). All automation semantics (`loadUrl`/`snapshot`/`click`/`screenshot`) live on `KBPage`. Full API in [§ 3](docs/KBrowser_API_Reference.md#3-kbpage--automation-layer).

> `newPage`, `loadUrl`, `snapshot`, `screenshot`, and locator actions (`click`/`fill`/`type`) are `suspend` — call them from a coroutine (`runBlocking { }` in `main()`, `LaunchedEffect` in Compose). Only locator creation (`getByRole`/`getByLabel`) and `close()` are plain calls.

```kotlin
val page = KBrowser.newPage(viewportWidth = 1280, viewportHeight = 720)  // suspend

page.loadUrl("https://example.com")               // suspend, returns when loaded

val result = page.snapshot()                      // AXTree + YAML (for AI)
val png = page.screenshot()                       // screenshot

page.getByLabel("Username").fill("admin")         // locate + fill (with verification)
page.getByRole("button", name = "Login").click()  // locate + physical click

page.close()                                      // not suspend
```

Interaction methods return `OperationResult` for programmatic verification (occlusion detection, value read-back, scroll comparison) — AI agents detect failures without re-snapshotting. See [Operation Verification](docs/KBrowser_API_Reference.md#5-operation-verification).

### 6. Loading content: URL / local file / HTML

`KBWebView` navigation (`webView.loadUrl(...)` / `webView.loadHtml(...)`) is fire-and-forget — it returns immediately without waiting for the content to load. `KBPage.loadUrl(...)` is `suspend` and returns only when the page has finished loading. For HTML strings on `KBPage`, use `page.webView.loadHtml(...)`.

```kotlin
// ── 1. Remote URL ──
webView.loadUrl("https://example.com")                 // KBWebView
page.loadUrl("https://example.com")                    // KBPage (suspend)

// ── 2. Local file (file:// protocol) ──
val htmlFile = File("path/to/page.html")
webView.loadUrl(htmlFile.toURI().toString())           // → file:///path/to/page.html
page.loadUrl(htmlFile.toURI().toString())

// ── 3. HTML string (no file/server needed) ──
webView.loadHtml("<html><body><h1>Hello</h1></body></html>")
page.webView.loadHtml("<html><body><h1>Hello</h1></body></html>")
```

> You can also set the initial page at creation: `rememberKBWebView(initialUrl = "https://example.com")`. On Desktop (JVM), HTML-string rendering (`loadHtml`) is served through the built-in `kbhtml://` scheme handler — no file or local server needed, in both rendering modes.

### 7. WasmJs (Browser)

The entry point is a plain `ComposeViewport` with no font handling needed — Compose Multiplatform 1.12+ downloads missing glyphs on demand via automatic font fallback (CJK variant selected by browser language), so Japanese, Arabic, emoji, etc. work out of the box:

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
fun main() = ComposeViewport {
    App()
}
```

### API Reference

Full API documentation: [docs/KBrowser_API_Reference.md](docs/KBrowser_API_Reference.md)

- [`KBrowser` object](docs/KBrowser_API_Reference.md#kbrowser-object) — initialization, `newPage()`, `shutdown()`
- [`KBWebView` UI component](docs/KBrowser_API_Reference.md#2-kbwebview--ui-component-layer) — state flows, navigation, JS bridge, callbacks
- [`KBPage` automation](docs/KBrowser_API_Reference.md#3-kbpage--automation-layer) — snapshot, coordinate/JS interactions, file upload, locators
- [`KBLocator`](docs/KBrowser_API_Reference.md#4-kblocator--declarative-locator) — coordinate/JS modes, queries, chaining
- [Operation verification](docs/KBrowser_API_Reference.md#5-operation-verification) — `OperationResult` strategies
- [Data structures](docs/KBrowser_API_Reference.md#6-data-structures) — `AxNode`, `AxTreeData`, `SnapshotResult`, etc.
- [Debug API](docs/KBrowser_API_Reference.md#9-debug-api-kbdebug) — `KBDebug` query-style CDP diagnostics

### AI Agent Tool Layer

Using KBrowser with AI agents? Don't expose dozens of APIs to the model — [`BrowserTools`](docs/AI_Tools.md) collapses the automation surface into **7 tools** (navigate / snapshot / act / observe / tabs / eval) with refid handles, automatic occlusion fallback, and errors-as-data. Embed it in your host (`listSpecs()` + `call()`) — see [AI_Tools.md](docs/AI_Tools.md). The demo also ships an MCP stdio mode, but it is a **debug backdoor** for developing KBrowser itself with an AI assistant, not a feature of the library.

---

## Platform Status

| Platform | KBWebView UI | KBPage Automation | Test Status |
|----------|-------------|-------------------|-------------|
| **Desktop (JVM)** | ✅ | ✅ Primary target | ✅ Actively tested |
| **WasmJs (Browser)** | ✅ | ❌ | ⚠️ Experimental |
| Android | ✅ | ⚠️ Partial (JS fallback) | ❌ Not tested |
| iOS | ✅ | ⚠️ Partial (JS fallback) | ❌ Not tested |

> Automation features are Desktop-only. On Android/iOS, `KBLocator` falls back to JS injection. On WasmJs, `KBWebView` renders via an HTML `<iframe>` overlay; automation APIs are not yet implemented.

| Other Platforms | Minimum Version |
|----------|-----------------|
| Android | API 34 (Android 14) |
| iOS | iOS 17.0+ |

---

## Rendering Modes (JVM Desktop)

The mode is fixed at startup via `KBrowser.initializeConfig(useOsr = ...)` and **cannot be changed afterwards**.

| Mode | `useOsr` | Overlay Compose UI | Event Handling | Performance | Chinese Input |
|------|----------|-------------------|----------------|-------------|---------------|
| **OSR (Off-Screen Rendering)** — default | `true` | ✅ | ⚠️ Compose overlays above the browser need correct interop layering to receive events (see note below); in-page interaction, JS↔Native callbacks, and CDP automation all work normally | Lower (pixel round-trip) | ⚠️ Requires JVM args + focus sync (handled internally by KBrowser) |
| **Non-OSR (Native Window)** | `false` | ❌ | ✅ Normal | ✅ Best | ✅ Native support |

- **Recommendation**: use OSR by default (the only mode supporting Compose overlay); use non-OSR only for maximum performance with a guarantee of never drawing Compose UI over the browser. The API is identical for both modes.
- ⚠️ Compose overlays above the browser: an overlay placed inside the browser view's mount container has its mouse/keyboard events pass through to the underlying JCEF view. Move it one level up (a sibling of the browser container) and it receives events normally — see the Demo's floating-card example in `BrowserExampleScreen` (`compose.interop.blending=true` is set by `initializeKBrowser()`). In-page interaction, `registerJsCallback`/`registerJsHandler` JS↔Native communication, and all CDP-based automation APIs are unaffected and work in both modes.
- **macOS live-resize caveat (non-OSR)**: browser content refreshes only after dragging window/splitter edges is released — a CEF + Core Animation architecture limitation that cannot be worked around from Java/AWT. See [jcef-resize-fix-plan.md](docs/jcef-resize-fix-plan.md).

---

## Background Automation Pages (JVM Desktop)

JCEF runs in OSR mode by default (zero-copy via shared memory) and does not depend on any window, so KBrowser has a single page-creation API with no "headed/headless" distinction:

- **Without viewport args**: the page mounts in the `KBWebView` Composable; size is determined by the Compose `modifier`.
- **With viewport args** (e.g. 1280×720): the page is not attached to any UI and renders off-screen at a fixed size, for background automation.

```kotlin
val page = KBrowser.newPage(viewportWidth = 1280, viewportHeight = 720)  // background page
page.loadUrl("https://example.com")        // navigate (suspend)
val png = page.screenshot()                // ready
```

**Limitations**: relies on OSR rendering (the default); on headless Linux servers, a virtual display (e.g. `Xvfb`) is required.

---

## Demo Application

- **Desktop**: on launch, choose a rendering mode (OSR / Non-OSR), then enter a full multi-tab browser + automation debug panel (AXTree, CDP interactions, screenshots), plus 7 `KBWebView` component demo pages (basic browsing, HTML rendering, JS bidirectional communication, new window & file handling, lifecycle callbacks, cache management, screenshot).
- **Mobile**: no rendering mode selection, goes straight to the feature list; some automation features are marked unavailable.
- **WasmJs**: `KBWebView` is implemented as an `<iframe>` overlay; only WebView demo pages are available.

---

## Documentation

- [Architecture Design](docs/KBrowser_Architecture_Design.md) — Coordinate system, platform internals, threading model
- [API Reference](docs/KBrowser_API_Reference.md) — All APIs with descriptions and usage examples ([section navigation](#api-reference) above)
- [Selector Guide](docs/KBrowser_Selector_Guide.md) — CSS selector generation strategy and usage

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Portions of the JVM/Desktop implementation are derived from [IntelliJ IDEA](https://github.com/JetBrains/intellij-community) (JetBrains s.r.o.), licensed under Apache 2.0. Modified files retain original copyright notices.
