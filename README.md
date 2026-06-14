# KBrowser

> ⚠️ **Work in Progress** — APIs are evolving rapidly; backward compatibility is not guaranteed. Android and iOS platforms are not yet fully tested. Current automation capabilities primarily target the Desktop (JVM) platform.

English | [简体中文](README_zh.md)

---

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF)](https://kotlinlang.org/docs/multiplatform.html)

---

**KBrowser** is a Kotlin Multiplatform library that provides:

1. **`KBWebView`** — A cross-platform WebView UI component for Android, iOS, and Desktop (JVM), with a unified API similar to `WKWebView` / Android `WebView`.
2. **`KBPage` + `KBBrowser`** — A Playwright-inspired browser automation API for Desktop (JVM), built on Chrome DevTools Protocol (CDP). Supports headless mode, AXTree extraction, CSP-safe element location, anti-detection physical clicks, and screenshot capture.

---

## Keywords

`kotlin multiplatform` `compose multiplatform` `webview` `browser automation` `playwright` `CDP` `chrome devtools protocol` `JCEF` `JetBrains CEF` `headless browser` `web scraping` `AXTree` `accessibility tree` `KMP` `CMP` `desktop automation` `cross-platform webview` `kotlin browser` `kotlin scraper`

---

## Platform Status

| Platform | KBWebView UI | Automation (KBPage) | Test Status |
|----------|-------------|---------------------|-------------|
| **Desktop (JVM)** | ✅ | ✅ Primary target | ✅ Actively tested |
| Android | ✅ | ⚠️ Partial | ❌ Not tested |
| iOS | ✅ | ⚠️ Partial | ❌ Not tested |

> ⚠️ **Note**: Automation features (such as AXTree, CDP-based physical clicks, and screenshots) are **Desktop-only**. On Android and iOS, `KBLocator` falls back to JS injection.

---

## Requirements

### Desktop (JVM) — Required

**Must use [JetBrains Runtime 25 (JBR) with JCEF](https://github.com/JetBrains/JetBrainsRuntime).** Standard JDK will not work.

```
Distribution: JetBrains Runtime 25
Package: JDK + JCEF
```

The library does not bundle JCEF. Configure your IDE or build tool to use JBR 25 with JCEF as the project runtime.

### Other Platforms

| Platform | Minimum Version |
|----------|---------|
| Android | API 34 (Android 14) |
| iOS | iOS 17.0+ |

---

## Documentation

- [Architecture Design](docs/KBrowser_Architecture_Design.md) — Coordinate system, platform internals, threading model
- [API Reference](docs/KBrowser_API_Reference.md) — All APIs with descriptions and usage examples

---

## Quick Start

### 1. JVM Initialization (Desktop)

Must be called **before** `application {}`. 
> ⚠️ **Performance Note**: `KBrowser` runs in OSR (Off-Screen Rendering) mode by default, which supports Compose matrix transformations but consumes more CPU/GPU resources. **For high-performance scenarios (e.g., rendering complex WebGL, 60fps animations), you MUST set `useOsr = false`**. This mode selection is final and can only be set once during application startup.

```kotlin
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 1. Configure cache directory and rendering mode (Must be set exactly once at startup)
    KBrowser.initializeConfig(
        storageDir = "/path/to/cache", 
        useOsr = false // Set to false for High-Performance (Native Window) mode
    )
    
    // 2. Initialize the engine asynchronously
    initializeKBrowser()  // Suspend function, must come before any UI initialization
 
    // 3. Start your Compose application
    application {
        Window(onCloseRequest = ::exitApplication) { App() }
    }
}
```

### 2. UI Component (KBWebView)

```kotlin
@Composable
fun BrowserScreen() {
    val webView = rememberKBWebView(initialUrl = "https://example.com")

    LaunchedEffect(webView) {
        // Handle new window requests (e.g. window.open() or target="_blank")
        webView.onNewWindowRequest = { url ->
            webView.loadUrl(url)  // Load in current webview, or open a new KBPage
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

### 3. Browser Automation (Desktop)

```kotlin
// Runs in headless mode, no UI needed
val page = KBrowser.newPage(url = "https://example.com")

// Intercept new page requests
page.onNewPage = { url -> println("New page request: $url") }

// Navigate and interact
page.loadUrl("https://example.com/login")

// Mode 1: Physical Coordinates Interaction (for anti-detection physical interactions)
page.getByLabel("Username").fill("admin")      // Clicks coordinates to focus -> fills values
page.getByLabel("Password").type("secret")     // Clicks coordinates to focus -> types key-by-key (anti-detection)
page.getByRole("button", name = "Login").click()

// Mode 2: JS Simulation Interaction (bypasses overlays/viewports, high stability)
page.getByLabel("Username").jsFill("admin")    // Focuses via JS -> modifies value and dispatches events
page.getByLabel("Password").jsType("secret")    // Focuses via JS -> types key-by-key (safe & stable)
page.getByRole("button", name = "Login").jsClick() // Dispatches DOM click event directly

// Extract accessibility tree (AXTree)
val tree = page.getRawAxTree().getCleanedAxTree()
println("Visible nodes: ${tree.visibleElements}")

// Screenshot — CSS pixel output, aligned with AXTree coordinates
val png = page.screenshot()

page.close()
```

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Portions of the JVM/Desktop implementation are derived from [IntelliJ IDEA](https://github.com/JetBrains/intellij-community) (JetBrains s.r.o.), licensed under Apache 2.0. Modified files retain original copyright notices.
