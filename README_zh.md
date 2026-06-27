# KBrowser

> **开发中** — API 可能随时变更，不保证向后兼容。iOS 和 Android 平台尚未测试。

[English](README.md) | 简体中文

---

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF)](https://kotlinlang.org/docs/multiplatform.html)

---

**KBrowser** 是一个 Kotlin Multiplatform 库，提供：

1. **`KBWebView`** — 跨平台 WebView UI 组件，支持 Android、iOS 和 Desktop (JVM)。它是纯净的 WebView 抽象，API 风格对齐 `WKWebView` 与 Android `WebView`。
2. **`KBPage`** — 面向 Desktop (JVM) 的 Playwright 风格浏览器自动化封装，基于 Chrome DevTools Protocol (CDP)。提供 AXTree 语义树提取、CSP 安全元素定位、防检测物理点击、截图捕获以及基于协程的线程安全保障。

---

## 平台状态

| 平台 | KBWebView UI | KBPage 自动化 | 测试状态 |
|------|-------------|--------------|---------|
| **Desktop (JVM)** | ✅ | ✅ 主要目标 | ✅ 持续测试中 |
| Android | ✅ | ⚠️ 部分（JS 降级） | ❌ 未测试 |
| iOS | ✅ | ⚠️ 部分（JS 降级） | ❌ 未测试 |

> 自动化功能（AXTree、基于 CDP 的交互、截图）目前仅限 Desktop 平台。在 Android 和 iOS 上，`KBLocator` 降级为 JS 注入方式。

---

## 环境要求

### Desktop (JVM)

**必须使用包含 JCEF 的 [JetBrains Runtime (JBR)](https://github.com/JetBrains/JetBrainsRuntime)。** 标准 JDK 无法运行。本库直接使用 JBR 中的 JCEF，不内置 JCEF。

```
Distribution: JetBrains Runtime
Package: JDK + JCEF
```

### 其他平台

| 平台 | 最低版本 |
|------|---------|
| Android | API 34 (Android 14) |
| iOS | iOS 17.0+ |

---

## 配置

### 1. 添加依赖

在 `gradle/libs.versions.toml` 中：

```toml
[versions]
kbrowser = "0.1.0-alpha31"

[libraries]
kbrowser = { module = "io.github.lzdev42:kbrowser", version.ref = "kbrowser" }
```

在模块的 `build.gradle.kts` 中：

```kotlin
implementation(libs.kbrowser)
```

### 2. 配置包含 JCEF 的 JBR

配置 IDE 或构建工具，将包含 JCEF 的 JBR 设置为项目运行时 JDK。在 `compose.desktop` 配置中，必须添加以下 JVM 参数：

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

> **注意**：不加这些 JVM 参数，OSR 模式下无法输入中文。

---

## 渲染模式（JVM Desktop）

在 JVM 上，JCEF 支持两种渲染模式。模式在初始化时通过 `KBrowser.initializeConfig(useOsr = ...)` 决定，**应用启动后不可更改**。

| 模式 | `useOsr` | 叠加 Compose UI | 事件响应 | 性能 |
|------|----------|-----------------|---------|------|
| **非 OSR（原生窗口）** | `false` | ❌ 无法在 JCEF 上叠加 Compose UI | ✅ 正常 | ✅ 较优 |
| **OSR（离屏渲染）** | `true` | ✅ 可在 JCEF 上叠加 Compose UI | ⚠️ 事件由底层 JCEF 原生视图接收，叠加的 Compose 组件不响应 | 较低（CPU/GPU 占用更高） |

**已知问题（OSR 模式）**：在 OSR 模式下，JCEF 离屏渲染，允许 Compose UI 层叠在其上。但鼠标和键盘事件会被底层 JCEF 原生视图接收，而非上层 Compose 组件。这意味着放置在 JCEF 区域上方的交互式 Compose 组件不会响应用户输入。此问题尚未开始研究，目前优先级较低。

**建议**：除非明确需要在浏览器上方叠加 Compose UI，否则应使用非 OSR 模式（`useOsr = false`）。非 OSR 模式提供更好的渲染性能，且 JCEF 视图本身的事件处理正常。

---

## 快速开始

### 1. JVM 初始化（Desktop）

`KBrowser.initializeConfig()` 和 `initializeKBrowser()` 必须在 `application {}` **之前**调用：

```kotlin
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import androidx.compose.ui.window.application

fun main() {
    // 1. 配置缓存目录与渲染模式（必须在启动时确定，不可更改）
    KBrowser.initializeConfig(
        storageDir = "/path/to/cache",
        useOsr = false  // 仅在需要在 JCEF 上叠加 Compose UI 时设为 true
    )

    // 2. 初始化 JCEF 引擎（挂起函数，必须在任何 UI 初始化之前调用）
    kotlinx.coroutines.runBlocking {
        initializeKBrowser()
    }

    // 3. 启动 Compose 应用
    application {
        Window(onCloseRequest = ::exitApplication) { App() }
    }
}
```

### 2. KBWebView — UI 组件

`KBWebView` 是纯净的 WebView 组件，用于在 Compose UI 中展示网页内容：

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

### 3. KBPage — 浏览器自动化

`KBPage` 是基于协程的 `KBWebView` 自动化封装，提供：
- 基于挂起函数的页面导航（`loadUrl` 挂起直到页面加载完成）
- AXTree 语义树提取与元素定位
- 基于坐标的物理交互（CDP）
- 基于 JS 的 DOM 交互
- 线程安全的节点缓存（写入使用 `Mutex` 串行化，读取使用 `@Volatile` 保证可见性）

```kotlin
val page = KBrowser.newPage()

page.onNewPage = { url -> println("新窗口请求: $url") }

page.loadUrl("https://example.com")

// 坐标模式（物理事件，防检测）
page.getByLabel("用户名").fill("admin")
page.getByLabel("密码").type("secret")
page.getByRole("button", name = "登录").click()

// JS 模式（DOM 事件模拟，无视遮挡）
page.getByLabel("用户名").jsFill("admin")
page.getByLabel("密码").jsType("secret")
page.getByRole("button", name = "登录").jsClick()

// AXTree 语义树提取
val tree = page.snapshot().rawTree.getCleanedAxTree()
println("可见节点数: ${tree.visibleElements}")

// 获取页面 Snapshot（一次调用同时拿到 YAML 和原始数据）
val result = page.snapshot(SnapshotMode.VIEWPORT)
val yaml = result.yaml          // 给 AI
val rawTree = result.rawTree    // 原始数据，refid 与 yaml 一致

// 截图
val png = page.screenshot()

page.close()
```

### 线程注意事项

- `KBPage` 的所有 `suspend` 方法内部通过 `withContext(Dispatchers.Main)` 切换到主线程执行，可在任意协程上下文中安全调用。
- `KBPage` 的节点缓存写入使用 `Mutex` 串行化，读取使用 `@Volatile` 保证可见性。读操作（如 `click`）不会因写操作（如 `getRawAxTree`）持锁而死锁。
- CPU 密集型操作（`getCleanedAxTree`、`getViewportAxTree`）是纯 Kotlin 扩展函数，在调用方协程上下文执行，不切换线程。

---

## 无头模式（JVM Desktop）

KBrowser 提供两个职责清晰的 page 创建 API：

- `KBrowser.newPage()` — 创建 **UI 模式 page**，用于通过 `KBWebView` Composable 在 Compose 窗口中显示。渲染尺寸由 Compose 的 `modifier` 决定。
- `KBrowser.newHeadlessTab(viewportWidth = 1280, viewportHeight = 720)` — 创建 **无头模式 page**，用于后台自动化（截图、CDP 操作、AX Tree 提取）。渲染尺寸由承载 JCEF 组件的透明 `JFrame`（opacity = 0）决定。**禁止将无头 page 挂载到 `KBWebView` Composable** —— 会导致尺寸异常。

两个 API 都只创建 page；导航通过 `page.loadUrl(url)` 完成，它是 `suspend` 函数，返回时即加载完成：

```kotlin
val page = KBrowser.newHeadlessTab()       // 创建
page.loadUrl("https://example.com")        // 导航（suspend）
val png = page.screenshot()                // 已就绪
```

**限制**：
- 这不是真正的无头浏览器，UI 框架窗口仍然存在（只是完全透明）。
- 必须使用 OSR 模式（`useOsr = true`）。
- Linux 服务器需要虚拟显示器（如 `Xvfb`）。

---

## 开发文档

- [架构设计](docs/KBrowser_Architecture_Design_zh.md) — 坐标系统、平台内部实现、线程模型
- [API 参考](docs/KBrowser_API_Reference_zh.md) — 所有 API 的详细说明与使用示例
- [选择器使用指南](docs/KBrowser_Selector_Guide.md) — CSS 选择器生成策略与使用方式
- [Snapshot 格式说明](docs/KBrowser_Snapshot_Format.md) — KBrowser YAML Snapshot 格式

---

## 开源协议

Apache License 2.0 — 详见 [LICENSE](LICENSE)。

JVM/Desktop 平台的部分实现衍生自 [IntelliJ IDEA](https://github.com/JetBrains/intellij-community) (JetBrains s.r.o.)，采用 Apache 2.0 协议授权。被修改的文件保留了原始版权声明。
