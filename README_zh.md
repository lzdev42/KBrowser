# KBrowser

> ⚠️ **开发中 (Work in Progress)** — API 快速演进，不保证向后兼容。Android 和 iOS 平台尚未经过完整测试，当前自动化能力主要针对 Desktop (JVM) 平台。

[English](README.md) | 简体中文

---

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF)](https://kotlinlang.org/docs/multiplatform.html)

---

**KBrowser** 是一个 Kotlin Multiplatform 库，提供：

1. **`KBWebView`** — 跨平台 WebView UI 组件，支持 Android、iOS 和 Desktop (JVM)，API 风格对齐 `WKWebView` 与 Android `WebView`。
2. **`KBPage` + `KBBrowser`** — 面向 Desktop (JVM) 的 Playwright 风格浏览器自动化 API，基于 Chrome DevTools Protocol (CDP)，支持无头模式、AXTree 语义树提取、CSP 安全元素定位、防检测物理点击和截图。

---

## 关键字

`kotlin multiplatform` `compose multiplatform` `webview` `browser automation` `playwright` `CDP` `chrome devtools protocol` `JCEF` `JetBrains CEF` `headless browser` `web scraping` `AXTree` `accessibility tree` `KMP` `CMP` `desktop automation` `cross-platform webview` `kotlin browser` `kotlin scraper`

---

## 平台状态 (Platform Status)

| 平台 | KBWebView UI | 自动化 (KBPage) | 测试状态 |
|------|-------------|---------------------|-------------|
| **Desktop (JVM)** | ✅ | ✅ 主要目标 | ✅ 持续测试中 |
| Android | ✅ | ⚠️ 部分支持 | ❌ 未测试 |
| iOS | ✅ | ⚠️ 部分支持 | ❌ 未测试 |

> ⚠️ **注意**：自动化功能（如 AXTree、基于 CDP 的物理点击、截图）目前仅限 **Desktop** 平台。在 Android 和 iOS 上，`KBLocator` 将降级（fallback）为 JS 注入方式。

---

## 环境要求 (Requirements)

### Desktop (JVM) — 必须

**必须使用带有 JCEF 的 [JetBrains Runtime 25 (JBR)](https://github.com/JetBrains/JetBrainsRuntime)。** 标准 JDK 将无法运行。

```
Distribution: JetBrains Runtime 25
Package: JDK + JCEF
```

本库不内置 JCEF。请配置您的 IDE 或构建工具，将带有 JCEF 的 JBR 25 设置为项目运行时的 JDK。

### 其他平台

| 平台 | 最低版本要求 |
|------|---------|
| Android | API 34 (Android 14) |
| iOS | iOS 17.0+ |

---

## 开发文档 (Documentation)

- [架构设计 (Architecture Design)](docs/KBrowser_Architecture_Design_zh.md) — 坐标系统、平台内部实现、线程模型
- [API 参考 (API Reference)](docs/KBrowser_API_Reference_zh.md) — 所有 API 的详细说明与使用示例

---

## 快速开始 (Quick Start)

### 1. JVM 初始化 (Desktop)

必须在调用 `application {}` **之前**进行初始化：

```kotlin
fun main() {
    KBrowser.setConfigPath("/path/to/cache")
    initializeKBrowser()  // 必须在任何 UI 启动前调用
 
    application {
        Window(onCloseRequest = ::exitApplication) { App() }
    }
}
```

### 2. UI 组件 (KBWebView)

```kotlin
@Composable
fun BrowserScreen() {
    val webView = rememberKBWebView(initialUrl = "https://example.com")

    LaunchedEffect(webView) {
        // 处理新窗口请求（例如 window.open() 或 target="_blank"）
        webView.onNewWindowRequest = { url ->
            webView.loadUrl(url)  // 在当前 webview 中加载，或打开新的 KBPage
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

### 3. 浏览器自动化 (Desktop)

```kotlin
// 无头模式运行，无需 UI 界面
val page = KBrowser.newPage(url = "https://example.com")

// 拦截新窗口请求
page.onNewPage = { url -> println("新窗口请求: $url") }

// 页面导航与交互
page.loadUrl("https://example.com/login")

// 模式 1：物理坐标模拟交互（支持物理防检测输入与物理点击）
page.getByLabel("Username").fill("admin")      // 物理坐标点击聚焦 -> 快速填充
page.getByLabel("Password").type("secret")     // 物理坐标点击聚焦 -> 逐字符物理输入（防检测）
page.getByRole("button", name = "Login").click()

// 模式 2：JS 模拟交互（无视遮挡与视口限制，稳定性极高）
page.getByLabel("Username").jsFill("admin")    // JS 聚焦 -> 直接修改 value 并派发事件
page.getByLabel("Password").jsType("secret")    // JS 聚焦 -> 逐字符物理输入（精准且安全）
page.getByRole("button", name = "Login").jsClick() // 直接派发 DOM 点击事件

// 提取 AXTree 语义树
val tree = page.getRawAxTree().getCleanedAxTree()
println("可见节点数: ${tree.visibleElements}")

// 页面截图 — 输出 CSS 像素大小 of PNG，与 AXTree 坐标完全对齐
val png = page.screenshot()

page.close()
```

---

## 开源协议 (License)

Apache License 2.0 — 详见 [LICENSE](LICENSE)。

JVM/Desktop 平台的部分实现衍生自 [IntelliJ IDEA](https://github.com/JetBrains/intellij-community) (JetBrains s.r.o.)，采用 Apache 2.0 协议授权。被修改的文件保留了原始的版权声明。
