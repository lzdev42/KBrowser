# KBrowser

> **Work in Progress** — APIs are subject to change without notice. iOS and Android platforms have not been tested.

English | [简体中文](README_zh.md)

---

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF)](https://kotlinlang.org/docs/multiplatform.html)

---

**KBrowser** is a Kotlin Multiplatform library that provides:

1. **`KBWebView`** — A cross-platform WebView UI component for Android, iOS, and Desktop (JVM). It is a pure WebView abstraction with a unified API similar to `WKWebView` / Android `WebView`.
2. **`KBPage`** — A Playwright-inspired browser automation wrapper around `KBWebView` for Desktop (JVM). Built on Chrome DevTools Protocol (CDP), it provides AXTree extraction, CSP-safe element location, anti-detection physical clicks, screenshot capture, and coroutine-based thread safety.

---

## Platform Status

| Platform | KBWebView UI | KBPage Automation | Test Status |
|----------|-------------|-------------------|-------------|
| **Desktop (JVM)** | ✅ | ✅ Primary target | ✅ Actively tested |
| Android | ✅ | ⚠️ Partial (JS fallback) | ❌ Not tested |
| iOS | ✅ | ⚠️ Partial (JS fallback) | ❌ Not tested |

> Automation features (AXTree, CDP-based interactions, screenshots) are Desktop-only. On Android and iOS, `KBLocator` falls back to JS injection.

---

## Requirements

### Desktop (JVM)

**Must use [JetBrains Runtime (JBR) with JCEF](https://github.com/JetBrains/JetBrainsRuntime).** Standard JDK will not work. The library uses JCEF directly from JBR — JCEF is not bundled.

```
Distribution: JetBrains Runtime
Package: JDK + JCEF
```

### Other Platforms

| Platform | Minimum Version |
|----------|-----------------|
| Android | API 34 (Android 14) |
| iOS | iOS 17.0+ |

---

## Setup

### 1. Add Dependency

In `gradle/libs.versions.toml`:

```toml
[versions]
kbrowser = "0.1.0-alpha31"

[libraries]
kbrowser = { module = "io.github.lzdev42:kbrowser", version.ref = "kbrowser" }
```

In your module's `build.gradle.kts`:

```kotlin
implementation(libs.kbrowser)
```

### 2. Configure JBR with JCEF

Configure your IDE or build tool to use JBR with JCEF as the project runtime. In `compose.desktop` configuration, the following JVM arguments are required:

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

> **⚠️ Important**: Without these JVM arguments, OSR mode **will not support Chinese/CJK text input** (English input is unaffected). In OSR mode, JCEF renders off-screen with no native window handling IME. Chinese input relies on reflective calls to JCEF internal classes, and `--add-opens` grants access to those classes. Without them, IME events are silently dropped, but English works via key events — easy to misdiagnose as "IME broken" rather than "missing configuration". In non-OSR mode, JCEF uses a native window where IME is handled natively by the OS, so no special arguments are needed.

---

## Rendering Modes (JVM Desktop)

On JVM, JCEF supports two rendering modes. The mode is determined at initialization time via `KBrowser.initializeConfig(useOsr = ...)` and **cannot be changed after the application starts**.

| Mode | `useOsr` | Overlay Compose UI | Event Handling | Performance | Chinese Input |
|------|----------|-------------------|----------------|-------------|---------------|
| **Non-OSR (Native Window)** | `false` | ❌ Cannot overlay Compose UI on top of JCEF | ✅ Normal | ✅ Better | ✅ Native support |
| **OSR (Off-Screen Rendering)** | `true` | ✅ Can overlay Compose UI on top of JCEF | ⚠️ Events are dispatched to the underlying JCEF view, not to overlay Compose components | Lower (higher CPU/GPU usage) | ⚠️ Requires JVM args |

**Known Issue (OSR mode)**: In OSR mode, JCEF renders off-screen, allowing Compose UI to be layered on top. However, mouse and keyboard events are received by the underlying JCEF native view, not by the Compose overlay. This means interactive Compose components placed over the JCEF area will not respond to user input. This issue has not been investigated yet and is currently low priority.

**Chinese Input in OSR Mode**: Besides the JVM arguments above, OSR mode also requires focus synchronization for Chinese input — KBrowser handles this internally, no user action needed. For technical details, see the [Architecture Document](docs/KBrowser_Architecture_Design.md).

**Recommendation**: Use non-OSR mode (`useOsr = false`) unless you specifically need to overlay Compose UI on top of the browser. Non-OSR mode provides better rendering performance, correct event handling, and Chinese input without extra configuration.

---

## Demo Application

This project includes a full demo application showcasing all KBrowser features.

**Desktop**: On launch, you first choose a rendering mode (OSR / Non-OSR) — this is desktop-specific, since OSR mode has lower performance and requires extra JVM arguments for Chinese input. After selecting OSR, the main page offers:
- **Browser Mode**: A full multi-tab browser + automation debug panel (AXTree, CDP interactions, screenshots, etc.)
- **KBWebView Component Demo**: 6 separate pages demonstrating basic browsing, HTML content rendering, JS bidirectional communication, new window & file handling, lifecycle callbacks, and cache management

Selecting Non-OSR directly shows a WebGL scene (demonstrating the limitation that Compose UI cannot be overlaid in Non-OSR mode).

**Mobile**: No rendering mode selection (mobile WebView has no OSR concept), goes directly to the feature list. The 6 WebView component demo pages share code with the desktop. The browser automation page shows a warning that some features may not work on mobile.

---

## Quick Start

### 1. JVM Initialization (Desktop)

`KBrowser.initializeConfig()` and `initializeKBrowser()` must be called **before** `application {}`:

```kotlin
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import androidx.compose.ui.window.application

fun main() {
    // 1. Configure cache directory and rendering mode (must be called once at startup)
    KBrowser.initializeConfig(
        storageDir = "/path/to/cache",
        useOsr = false  // Set to true only if you need Compose UI overlay on top of JCEF
    )

    // 2. Initialize JCEF engine (suspend function, must be called before any UI)
    kotlinx.coroutines.runBlocking {
        initializeKBrowser()
    }

    // 3. Start Compose application
    application {
        Window(onCloseRequest = ::exitApplication) { App() }
    }
}
```

### 2. KBWebView — UI Component

`KBWebView` is a pure WebView component. Use it when you need to display web content in your Compose UI:

```kotlin
@Composable
fun BrowserScreen() {
    val webView = rememberKBWebView(initialUrl = "https://example.com")

    LaunchedEffect(webView) {
        webView.onNewWindowRequest = { url ->
            webView.loadUrl(url)
        }
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

### 3. KBPage — Browser Automation

`KBPage` is a coroutine-based automation wrapper around `KBWebView`. It provides:
- Suspend-based page navigation (`loadUrl` suspends until page finishes loading)
- AXTree extraction and element location
- Coordinate-based physical interactions (CDP)
- JS-based DOM interactions
- Thread-safe node caching with `Mutex` for writes and `@Volatile` for reads

```kotlin
val page = KBrowser.newPage()

page.onNewPage = { url -> println("New page request: $url") }

page.loadUrl("https://example.com")

// Coordinate mode (physical events, anti-detection)
page.getByLabel("Username").fill("admin")
page.getByLabel("Password").type("secret")
page.getByRole("button", name = "Login").click()

// JS mode (DOM event simulation, bypasses occlusion)
page.getByLabel("Username").jsFill("admin")
page.getByLabel("Password").jsType("secret")
page.getByRole("button", name = "Login").jsClick()

// AXTree extraction
val tree = page.snapshot().rawTree.getCleanedAxTree()
println("Visible nodes: ${tree.visibleElements}")

// Get page snapshot (YAML + raw data from the same fetch)
val result = page.snapshot(SnapshotMode.VIEWPORT)
val yaml = result.yaml          // For AI
val rawTree = result.rawTree    // Raw data, refids consistent with yaml

// Screenshot
val png = page.screenshot()

page.close()
```

### Threading Notes

- All `suspend` methods of `KBPage` internally switch to `Dispatchers.Main` via `withContext`, so they can be called from any coroutine context.
- The `KBPage` node cache uses `Mutex` for write serialization and `@Volatile` for read visibility. Read operations (e.g., `click`) will never deadlock with write operations (e.g., `getRawAxTree`).
- CPU-intensive operations (`AxTreeData.getCleanedAxTree()`, `AxTreeData.toYamlSnapshot()`) are pure Kotlin extension functions that execute in the caller's coroutine context without switching threads. `getCleanedAxTree()` actually filters nodes within the current viewport (same viewport-range logic as `toYamlSnapshot(VIEWPORT)`).

---

## Headless Mode (JVM Desktop)

KBrowser provides two distinct page-creation APIs, each with a clear single responsibility:

- `KBrowser.newPage(profile: KBProfile? = null)` — Creates a **UI page** for display in a Compose window via the `KBWebView` Composable. Render size is determined by the Compose `modifier`.
- `KBrowser.newHeadlessTab(profile: KBProfile? = null, viewportWidth = 1280, viewportHeight = 720)` — Creates a **headless page** for background automation (screenshots, CDP operations, AX Tree extraction). Render size is determined by a transparent `JFrame` (opacity = 0) that hosts the JCEF component. **Never mount a headless page onto the `KBWebView` Composable** — it will cause size anomalies.

Both APIs only create the page; navigation is done via `page.loadUrl(url)`, which is a `suspend` function that returns when loading completes:

```kotlin
val page = KBrowser.newHeadlessTab()       // create
page.loadUrl("https://example.com")        // navigate (suspend)
val png = page.screenshot()                // ready
```

**Limitations**:
- This is not a true headless browser. A UI framework window is still created (with zero opacity).
- Requires OSR mode (`useOsr = true`).
- On Linux servers, a virtual display (e.g., `Xvfb`) is required.

---

## Documentation

- [Architecture Design](docs/KBrowser_Architecture_Design.md) — Coordinate system, platform internals, threading model
- [API Reference](docs/KBrowser_API_Reference.md) — All APIs with descriptions and usage examples
- [Selector Guide](docs/KBrowser_Selector_Guide.md) — CSS selector generation strategy and usage
- [Snapshot Format](docs/KBrowser_Snapshot_Format.md) — KBrowser YAML Snapshot format for programmatic consumption

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Portions of the JVM/Desktop implementation are derived from [IntelliJ IDEA](https://github.com/JetBrains/intellij-community) (JetBrains s.r.o.), licensed under Apache 2.0. Modified files retain original copyright notices.
