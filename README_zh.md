# KBrowser

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF)](https://kotlinlang.org/docs/multiplatform.html)

[English](README.md) | 简体中文

> **开发中** — API 可能随时变更，不保证向后兼容。iOS 和 Android 平台尚未测试。

**KBrowser** 是一个 Kotlin Multiplatform 库，提供：

1. **`KBWebView`** — 跨平台 WebView UI 组件，支持 Android、iOS、Desktop (JVM) 和 WasmJs (浏览器)。API 风格对齐 `WKWebView` 与 Android `WebView`。
2. **`KBPage`** — 面向 Desktop (JVM) 的 Playwright 风格浏览器自动化封装，基于 Chrome DevTools Protocol (CDP)：AXTree 语义树提取、CSP 安全元素定位、防检测物理点击、截图捕获、协程级线程安全。

---

## 快速集成

### 0. 先选对 API

**显示网页用 `KBWebView`，自动化操作用 `KBPage`。**

| 你的需求 | 用什么 |
|----------|--------|
| 在 Compose UI 里**显示**网页（浏览器界面、内嵌页面） | **`KBWebView`** Composable + `rememberKBWebView()` |
| **操控**网页——自动化、数据抓取、截图、AI Agent | **`KBPage`**（`KBrowser.newPage()`） |

- `KBWebView` 只负责渲染和用户交互，不能替你点击、填值、快照、截图——这些全在 `KBPage` 上。（两者都有 `loadUrl`，但语义不同，见第 6 节。）
- 同一块视图既要显示又要自动化？创建一个不传 viewport 的 `KBPage`，把它的 `webView` 挂载到 `KBWebView` Composable 即可——Demo 的浏览器模式就是这么做的。

### 1. 添加依赖

在 `gradle/libs.versions.toml` 中：

```toml
[versions]
kbrowser = "0.1.0-alpha46"

[libraries]
kbrowser = { module = "io.github.lzdev42:kbrowser", version.ref = "kbrowser" }
```

在模块的 `build.gradle.kts` 中：

```kotlin
implementation(libs.kbrowser)
```

### 2. Desktop (JVM)：配置 JBR

**必须使用包含 JCEF 的 [JetBrains Runtime (JBR)](https://github.com/JetBrains/JetBrainsRuntime)，标准 JDK 无法运行。** JCEF 类随 JBR 运行时自带，不在 KBrowser 库或任何 Maven 依赖中。

`compose.desktop` 配置中添加必需的 JVM 参数（不加则 OSR 模式下**无法输入中文及任何 CJK 文字**，英文不受影响，极易误判为"输入法坏了"）：

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

**⚠️ Gradle daemon 的 JVM 也必须是 JBR**，否则运行时提示"未安装 JCEF"（`JcefChecker.isJcefAvailable == false`）——`:desktopApp:run` 和所有 `JavaExec` 任务默认运行在 daemon 的 JVM 上，而 daemon 默认用标准 JDK（Zulu/Corretto/Temurin 等），进程里没有 JCEF 类，即使系统装了 JBR 也一样报错。

修复（二选一，改完 `./gradlew --stop` 重启 daemon）：

- **IDE 设置（推荐）**：IDEA → `Settings` → `Build, Execution, Deployment` → `Build Tools` → `Gradle` → **Gradle JVM** 选择 JBR+JCEF。
- **用户级 `~/.gradle/gradle.properties`**（路径机器特定，不要提交进仓库）：

```properties
org.gradle.java.home=/Users/yourname/Library/Java/JavaVirtualMachines/jbrsdk_jcef-25.0.3/Contents/Home
```

> `compose.desktop.application.javaHome` 只影响 `:run` 单个任务，覆盖不到 daemon 和其他 `JavaExec` 任务，不推荐。`nativeDistributions`（DMG/MSI/DEB）打包时自带 JBR，分发应用自包含，无需上述配置。
>
> **验证**：`ps -o comm= -p $(jps | grep GradleDaemon | awk '{print $1}')` 显示 JBR 路径即生效。

### 3. 初始化引擎（OSR 用法）

引擎初始化必须在创建任何 `KBWebView` / `KBPage` **之前**完成。`useOsr` 是渲染模式，**启动后不可更改**。下面两种写法在本仓库都有使用（方式 B 是 Demo 的做法），任选一种。

**方式 A — `main()` 里同步初始化**（最简单，适合"开机即浏览器"应用）：

```kotlin
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import xyz.kbrowser.getDefaultStorageDir
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    KBrowser.initializeConfig(
        storageDir = getDefaultStorageDir(),  // 平台默认缓存目录；也可传自定义路径
        useOsr = true                          // 默认；详见渲染模式章节
    )
    kotlinx.coroutines.runBlocking { initializeKBrowser() }  // 挂起，等待 JCEF 就绪

    application {
        Window(onCloseRequest = ::exitApplication) { App() }
    }
}
```

**方式 B — Compose 内异步初始化 + 加载指示 + 模式选择**（Demo 实际做法，适合需要先让用户选模式的场景）：

```kotlin
var isInitialized by remember { mutableStateOf(false) }
val scope = rememberCoroutineScope()

if (!isInitialized) {
    // 先显示模式选择 / 加载界面
    ModeSelectionScreen(onModeSelected = { useOsr ->
        scope.launch {
            KBrowser.initializeConfig(getDefaultStorageDir(), useOsr = useOsr)
            initializeKBrowser()   // 挂起
            isInitialized = true   // 完成后才切到含 WebView 的界面
        }
    })
} else {
    MainScreen()
}
```

关键约束：
- `initializeConfig()` 只应调用一次，且早于任何其他 KBrowser API。它只是一个普通 setter，没有防重入保护——引擎启动后再调用不会重新配置任何东西。
- `initializeKBrowser()` 必须在创建任何 `KBWebView` / `KBPage` 之前完成（方式 B 用 `isInitialized` 状态门控 UI，保证初始化完成才创建 WebView）。

### 4. 显示网页 — `KBWebView`

最简用法：`rememberKBWebView` 创建实例，`KBWebView` Composable 挂载即可显示。导航/JS/回调等完整 API 见 [API 参考第 2 节](docs/KBrowser_API_Reference_zh.md#2-kbwebview--ui-组件层)。

```kotlin
@Composable
fun BrowserScreen() {
    val webView = rememberKBWebView(initialUrl = "https://example.com")

    KBWebView(webView = webView, modifier = Modifier.fillMaxSize())
}
```

### 5. 程序化操控 — `KBPage`

`KBrowser.newPage()` 创建 page（后台自动化可传 `viewportWidth`/`viewportHeight`）。自动化语义（`loadUrl`/`snapshot`/`click`/`screenshot` 等）全部封装在 `KBPage` 上。完整 API 见 [第 3 节](docs/KBrowser_API_Reference_zh.md#3-kbpage--自动化控制层)。

> `newPage`、`loadUrl`、`snapshot`、`screenshot` 以及 Locator 操作（`click`/`fill`/`type`）都是 `suspend`——必须在协程中调用（`main()` 里用 `runBlocking { }`，Compose 里用 `LaunchedEffect`）。只有 Locator 创建（`getByRole`/`getByLabel`）和 `close()` 是普通调用。

```kotlin
val page = KBrowser.newPage(viewportWidth = 1280, viewportHeight = 720)  // suspend

page.loadUrl("https://example.com")               // 挂起，返回时加载完成

val result = page.snapshot()                      // AXTree + YAML（给 AI）
val png = page.screenshot()                       // 截图

page.getByLabel("用户名").fill("admin")           // 定位 + 填值（带验证）
page.getByRole("button", name = "登录").click()   // 定位 + 物理点击

page.close()                                      // 非 suspend
```

交互方法返回 `OperationResult`：程序化验证操作是否成功（遮挡检测、值回读、滚动位置对比），AI Agent 无需重新快照即可感知失败。详见 [操作验证](docs/KBrowser_API_Reference_zh.md#5-操作验证)。

### 6. 加载内容：网址 / 本地文件 / HTML

`KBWebView` 的导航（`webView.loadUrl(...)` / `webView.loadHtml(...)`）是即发即忘的——立即返回，不等待内容加载完成。`KBPage.loadUrl(...)` 是 `suspend` 的，页面加载完成后才返回。`KBPage` 加载 HTML 字符串走 `page.webView.loadHtml(...)`。

```kotlin
// ── 1. 网络 URL ──
webView.loadUrl("https://example.com")                 // KBWebView
page.loadUrl("https://example.com")                    // KBPage（suspend）

// ── 2. 本地文件（file:// 协议）──
val htmlFile = File("path/to/page.html")
webView.loadUrl(htmlFile.toURI().toString())           // → file:///path/to/page.html
page.loadUrl(htmlFile.toURI().toString())

// ── 3. HTML 字符串（无需文件/服务器）──
webView.loadHtml("<html><body><h1>Hello</h1></body></html>")
page.webView.loadHtml("<html><body><h1>Hello</h1></body></html>")
```

> 也可以在创建时就指定起始页：`rememberKBWebView(initialUrl = "https://example.com")`。在 Desktop (JVM) 上，HTML 字符串渲染（`loadHtml`）通过内置的 `kbhtml://` scheme handler 加载——无需文件或本地服务器，两种渲染模式下都可用。

### 7. WasmJs（浏览器）

入口就是标准的 `ComposeViewport`，无需任何字体处理——Compose Multiplatform 1.12+ 的自动字体回落会按需下载缺失字形（CJK 变体按浏览器语言自动选择），日文、阿拉伯文、emoji 等开箱即用：

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
fun main() = ComposeViewport {
    App()
}
```

### API 参考

完整 API 说明见 [docs/KBrowser_API_Reference_zh.md](docs/KBrowser_API_Reference_zh.md)：

- [`KBrowser` 对象](docs/KBrowser_API_Reference_zh.md#kbrowser-对象) — 初始化、`newPage()`、`shutdown()`
- [`KBWebView` UI 组件](docs/KBrowser_API_Reference_zh.md#2-kbwebview--ui-组件层) — 状态流、导航、JS 双向交互、回调
- [`KBPage` 自动化](docs/KBrowser_API_Reference_zh.md#3-kbpage--自动化控制层) — snapshot、坐标/JS 交互、文件上传、Locator
- [`KBLocator` 定位器](docs/KBrowser_API_Reference_zh.md#4-kblocator--声明式定位器) — 坐标/JS 双模式、查询、链式过滤
- [操作验证](docs/KBrowser_API_Reference_zh.md#5-操作验证) — `OperationResult` 验证策略
- [数据结构](docs/KBrowser_API_Reference_zh.md#6-数据结构) — `AxNode`、`AxTreeData`、`SnapshotResult` 等
- [调试 API](docs/KBrowser_API_Reference_zh.md#9-调试-apikbdebug) — `KBDebug` 查询式 CDP 诊断

---

## 平台状态

| 平台 | KBWebView UI | KBPage 自动化 | 测试状态 |
|------|-------------|--------------|---------|
| **Desktop (JVM)** | ✅ | ✅ 主要目标 | ✅ 持续测试中 |
| **WasmJs (浏览器)** | ✅ | ❌ | ⚠️ 实验性 |
| Android | ✅ | ⚠️ 部分（JS 降级） | ❌ 未测试 |
| iOS | ✅ | ⚠️ 部分（JS 降级） | ❌ 未测试 |

> 自动化功能仅限 Desktop。Android/iOS 上 `KBLocator` 降级为 JS 注入。WasmJs 的 `KBWebView` 通过叠加 HTML `<iframe>` 实现，自动化 API 尚未实现。

| 其他平台 | 最低版本 |
|----------|---------|
| Android | API 34 (Android 14) |
| iOS | iOS 17.0+ |

---

## 渲染模式（JVM Desktop）

模式在 `KBrowser.initializeConfig(useOsr = ...)` 初始化时确定，**启动后不可更改**。

| 模式 | `useOsr` | 叠加 Compose UI | 事件响应 | 性能 | 中文输入 |
|------|----------|-----------------|---------|------|---------|
| **OSR（离屏渲染）** — 默认 | `true` | ✅ | ⚠️ 浏览器上方的 Compose 覆盖层需要正确的混排层级才能接收事件（见下方说明）；网页内部交互、JS↔Native 回调、CDP 自动化均正常 | 较低（像素往返） | ⚠️ 需 JVM 参数 + 焦点同步（KBrowser 内部已处理） |
| **非 OSR（原生窗口）** | `false` | ❌ | ✅ 正常 | ✅ 最佳 | ✅ 原生支持 |

- **建议**：默认 OSR（唯一支持 Compose 叠加的模式）；仅在需要极限性能且绝不叠加 Compose UI 时用非 OSR。两种模式 API 完全一致。
- ⚠️ 浏览器上方的 Compose 覆盖层：直接放在 WebView 挂载容器内部的覆盖层，鼠标/键盘事件会穿透到下层 JCEF 视图；把它上移一层（作为浏览器容器的同级）即可正常接收事件——参见 Demo 浏览器界面的悬浮卡片示例（`compose.interop.blending=true` 由 `initializeKBrowser()` 自动设置）。网页内的用户交互、`registerJsCallback`/`registerJsHandler` JS↔Native 双向通信、以及基于 CDP 的全部自动化 API（`KBPage` 的所有方法在两种模式下均正常工作）不受影响。
- **macOS live-resize 限制（非 OSR）**：拖拽窗口/分隔条时浏览器内容松手后才刷新，是 CEF + Core Animation 的架构限制，无法从 Java/AWT 侧绕过。详见 [jcef-resize-fix-plan.md](docs/jcef-resize-fix-plan.md)。

---

## 后台自动化 Page（JVM Desktop）

JCEF 默认以 OSR 离屏渲染（共享内存零拷贝），不依赖任何窗口，因此 KBrowser 只有一个 page 创建 API，不区分"有头/无头"：

- **不传 viewport**：page 挂载到 `KBWebView` Composable 显示，尺寸由 Compose `modifier` 决定。
- **传 viewport**（如 1280×720）：page 不挂任何 UI，固定尺寸离屏渲染，用于后台自动化。

```kotlin
val page = KBrowser.newPage(viewportWidth = 1280, viewportHeight = 720)  // 后台 page
page.loadUrl("https://example.com")        // 导航（suspend）
val png = page.screenshot()                // 已就绪
```

**限制**：依赖 OSR 渲染（默认即 OSR）；Linux 无显示环境的服务器需要虚拟显示器（如 `Xvfb`）。

---

## Demo 应用

- **桌面端**：启动时选择渲染模式（OSR / Non-OSR），随后进入多标签页浏览器 + 自动化调试面板（AXTree、CDP 交互、截图），以及 7 个 `KBWebView` 组件演示页（基础浏览、HTML 渲染、JS 双向通信、新窗口与文件、生命周期、缓存管理、网页截图）。
- **移动端**：无渲染模式选择，直接进入功能列表；部分自动化功能标注不可用。
- **WasmJs**：`KBWebView` 以 `<iframe>` 叠加实现，仅支持 WebView 演示页。

---

## 开发文档

- [架构设计](docs/KBrowser_Architecture_Design_zh.md) — 坐标系统、平台内部实现、线程模型
- [API 参考](docs/KBrowser_API_Reference_zh.md) — 所有 API 的详细说明与使用示例（[章节导航](#api-参考)见上方）
- [选择器使用指南](docs/KBrowser_Selector_Guide.md) — CSS 选择器生成策略与使用方式

---

## 开源协议

Apache License 2.0 — 详见 [LICENSE](LICENSE)。

JVM/Desktop 平台的部分实现衍生自 [IntelliJ IDEA](https://github.com/JetBrains/intellij-community) (JetBrains s.r.o.)，采用 Apache 2.0 协议授权。被修改的文件保留了原始版权声明。
