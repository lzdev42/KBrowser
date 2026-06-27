# KBrowser API 参考文档

> [← 返回自述文件](../README_zh.md)

[English](KBrowser_API_Reference.md) | 简体中文

> **坐标系统**：全文所有坐标均为 **CSS 文档像素**，截图与交互坐标完全一致。

---

## 1. 快速开始

JVM 平台必须在 `application {}` 之前完成初始化：

```kotlin
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import androidx.compose.ui.window.application

fun main() {
    // 1. 配置存储路径与渲染模式
    KBrowser.initializeConfig(
        storageDir = "/path/to/cache",
        useOsr = false  // 仅在需要在 JCEF 上叠加 Compose UI 时设为 true
    )

    // 2. 初始化 JCEF 引擎（必须在任何 UI 初始化之前调用）
    kotlinx.coroutines.runBlocking {
        initializeKBrowser()
    }

    // 3. 启动 Compose 应用
    application {
        Window(onCloseRequest = ::exitApplication) {
            App()
        }
    }
}
```

### 必需的 JVM 参数

必须在 `compose.desktop` 配置中添加以下 JVM 参数。不加这些参数，OSR 模式下无法输入中文：

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

### KBrowser 对象

| 方法 / 属性 | 说明 |
|------------|------|
| `KBrowser.initializeConfig(storageDir: String?, useOsr: Boolean = true)` | 配置存储目录与渲染模式。必须在 `initializeKBrowser()` 和 `newPage()` 之前调用。`useOsr` 决定渲染模式（见 README 渲染模式章节），初始化后不可更改。 |
| `KBrowser.newPage(): KBPage` | 创建 **UI 模式** page，用于通过 `KBWebView` Composable 在 Compose 窗口中显示。渲染尺寸由 Compose 的 `modifier` 决定。导航用 `page.loadUrl(url)`（suspend，返回时加载完成）。 |
| `KBrowser.newHeadlessTab(viewportWidth: Int = 1280, viewportHeight: Int = 720): KBPage` | 创建 **无头模式** page，用于后台自动化（截图、CDP、AX Tree）。渲染尺寸由透明 `JFrame`（opacity = 0）决定。默认 viewport 1280×720，与 Playwright 一致。**禁止将无头 page 挂载到 `KBWebView` Composable。** 导航用 `page.loadUrl(url)`。 |
| `KBrowser.pages: StateFlow<List<KBPage>>` | 当前所有打开页面的响应式流 |
| `KBrowser.getPages(): List<KBPage>` | 同步获取当前所有打开页面的快照 |
| `KBrowser.shutdown()` | 关闭所有页面并执行全局资源清理 |
| `KBrowser.registerPage(page: KBPage)` | @Deprecated。手动注册页面。`newPage()`/`newHeadlessTab()` 自动注册。 |
| `KBrowser.unregisterPage(page: KBPage)` | @Deprecated。从列表移除页面。请用 `page.close()`。 |

---

## 2. KBWebView — UI 组件层

`KBWebView` 是平台无关的纯净 WebView 接口。通过 `rememberKBWebView(initialUrl, profile?)` 创建，通过 `@Composable KBWebView` 挂载。`profile` 参数可选，传入 `KBProfile` 可为该实例隔离 Cookie/缓存。

### 状态流

| 属性 | 类型 | 说明 |
|------|------|------|
| `currentUrl` | `StateFlow<String?>` | 当前页面 URL |
| `currentTitle` | `StateFlow<String?>` | 当前页面标题 |
| `loadingState` | `StateFlow<LoadingState>` | 加载状态：`Initializing` / `Loading` / `Finished` / `Error(errorCode, description, failingUrl)` |
| `progress` | `StateFlow<Float>` | 加载进度 0.0f ~ 1.0f |
| `canGoBack` | `StateFlow<Boolean>` | 是否可后退 |
| `canGoForward` | `StateFlow<Boolean>` | 是否可前进 |

### 导航方法

| 方法 | 说明 |
|------|------|
| `loadUrl(url: String)` | 加载 URL |
| `loadHtml(html: String)` | 加载 HTML 字符串 |
| `reload()` | 刷新 |
| `stopLoading()` | 停止加载 |
| `goBack()` | 后退 |
| `goForward()` | 前进 |

### JS 交互（Native <-> Web）

| 方法 | 说明 |
|------|------|
| `evaluateJavascript(script, callback?)` | 从 Kotlin 执行 JS，结果可选通过 callback 返回 |
| `registerJsCallback(name, callback)` | **单向通知**：注册后 JS 通过 `window.<name>(data)` 调用，无返回值 |
| `unregisterJsCallback(name)` | 注销 JS 单向回调 |
| `registerJsHandler(name, handler)` | **双向请求**：注册有返回值的 Handler。JS 可 `await window.<name>(data)` 获取结果 |
| `unregisterJsHandler(name)` | 注销 JS 双向请求 Handler |

> **注意**：Handler 在后台线程执行，不要在其中直接操作 UI。

### 生命周期与其他

| 方法 | 说明 |
|------|------|
| `clearCacheAndCookies()` | 清除当前 Profile 的缓存与 Cookie |
| `destroy()` | 销毁 WebView，释放资源 |
| `setWebViewClient(client?)` | 设置页面加载回调 |
| `setWebChromeClient(client?)` | 设置 JS 对话框 / 权限回调 |
| `suspend takeScreenshot(): ByteArray?` | CDP 截图，输出 CSS 像素大小的 PNG |
| `var onNewWindowRequest: ((url: String) -> Unit)?` | 新标签页/新窗口请求回调；不设置时静默丢弃 |
| `setInteractionLocked(locked: Boolean)` | 锁定/解锁用户交互。`true` 时覆盖 AWT 拦截层，阻止用户输入；自动化操作不受影响。渲染鼠标轨迹和点击动画。**仅 JVM 有效。** |
| `updateMouseTrail(viewportX: Int, viewportY: Int)` | 更新鼠标轨迹位置。坐标自动化方法自动调用。**仅 JVM 有效。** |
| `var onFileDialogRequest: ((request: KBFileDialogRequest, callback: KBFileDialogCallback) -> Unit)?` | 文件对话框回调。设置后由调用方处理文件选择；不设置时静默取消。 |

### Composable 用法示例

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

## 3. KBPage — 自动化控制层

`KBPage` 是基于协程的 `KBWebView` 自动化封装。所有 `suspend` 方法内部通过 `withContext(Dispatchers.Main)` 切换到主线程，可在任意协程上下文中安全调用。通过 `KBrowser.newPage()` 创建。

### 属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `uuid` | `String` | 创建时自动生成的唯一 ID |
| `webView` | `KBWebView` | 底层 WebView 实例 |

### 状态流

与 `KBWebView` 相同，直接代理：`currentUrl`、`title`、`loadingState`、`progress`。

### 导航

| 方法 | 说明 |
|------|------|
| `suspend loadUrl(url: String)` | 加载 URL，挂起直到 `onPageFinished`；取消时自动 `stopLoading()` |
| `suspend evaluateJavascript(script: String): String` | 执行 JS，返回结果字符串 |
| `suspend clearCacheAndCookies()` | 清除缓存与 Cookie |
| `suspend setCookieViaJs(cookieString: String)` | 通过 `document.cookie` 注入 Cookie |
| `suspend screenshot(): ByteArray?` | CDP 截图，返回 CSS 像素大小的 PNG |
| `close()` | 销毁底层 WebView |

### 交互锁定与视觉反馈

| 方法 | 说明 |
|------|------|
| `setInteractionLocked(locked: Boolean)` | 锁定/解锁用户交互。仅 JVM 有效。 |
| `updateMouseTrail(viewportX: Int, viewportY: Int)` | 更新鼠标轨迹位置。坐标方法自动调用。 |

### AXTree 语义树

| 方法 | 说明 |
|------|------|
| `suspend snapshot(mode: SnapshotMode = SnapshotMode.VIEWPORT): SnapshotResult` | 返回 `SnapshotResult`，包含 YAML 字符串和原始 `AxTreeData`，两者来自同一次 fetch，refid 保证一致。请使用此方法 —— `getRawAxTree()` 已私有，不应直接调用。 |
| `AxTreeData.getCleanedAxTree(): AxTreeData` | 扩展：过滤不可见元素、script/style 标签、调试 overlay |
| `AxTreeData.getViewportAxTree(): AxTreeData` | 扩展：裁剪到当前视口范围内的节点 |
| `AxTreeData.toYamlSnapshot(mode: SnapshotMode = SnapshotMode.VIEWPORT): String` | 转换为 KBrowser YAML Snapshot 格式。详见 [Snapshot 格式说明](KBrowser_Snapshot_Format.md)。 |

扩展函数是纯 Kotlin 计算，在调用方协程上下文执行，不切换线程。

#### SnapshotMode 枚举

```kotlin
enum class SnapshotMode {
    VIEWPORT,  // 视口内紧凑序列化，适合 AI 消费
    CLEAN      // 全页面紧凑序列化，去除噪音但保留所有节点（含视口外）
}
```

| 模式 | 视口外节点 | StaticText / 伪元素 | 空容器 | 适用场景 |
|------|----------|-------------------|--------|---------|
| `VIEWPORT` | 过滤 | 过滤 | 过滤 | AI 消费，token 最少 |
| `CLEAN` | 保留 | 过滤 | 过滤 | 需要完整页面结构但去除冗余 |

#### SnapshotResult 数据类

```kotlin
data class SnapshotResult(
    val yaml: String,       // KBrowser YAML Snapshot 字符串，给 AI 使用
    val rawTree: AxTreeData // 同一次 fetch 的完整原始数据，refid 与 yaml 一致
)
```

> **重要**：`snapshot()` 返回的 `yaml` 和 `rawTree` 来自同一次 `getRawAxTree()` 调用，refid 保证一致。不应分别调用 `snapshot()` 和 `getRawAxTree()`，因为两次调用之间 refid 可能已变化。

#### 使用示例

```kotlin
// 获取 AI 消费用的视口内 YAML，同时保留原始数据
val result = page.snapshot(SnapshotMode.VIEWPORT)
val yaml = result.yaml          // 给 AI
val rawTree = result.rawTree    // 原始数据，用于后续处理

// 获取完整页面 YAML（包含视口外节点）
val fullResult = page.snapshot(SnapshotMode.CLEAN)
```

### 交互（基于 refid）

调用前需先执行 `getRawAxTree()` 刷新缓存。若 refid 不存在则抛出 `ElementNotFoundException`。

#### 坐标模式（物理事件）

| 方法 | 说明 |
|------|------|
| `suspend click(refid: String): OperationResult` | 物理点击元素坐标。操作后通过 `elementFromPoint` 验证是否命中目标。 |
| `suspend hover(refid: String)` | 物理悬停元素坐标 |
| `suspend scroll(refid: String, deltaX: Int, deltaY: Int): OperationResult` | 物理滚动元素坐标。通过 `scrollTop` 前后对比验证。 |
| `suspend drag(startRefid: String, endRefid: String)` | 物理拖拽 |

#### JS 模式（DOM 事件模拟）

| 方法 | 说明 |
|------|------|
| `suspend jsClick(refid: String)` | DOM `.click()` |
| `suspend jsHover(refid: String)` | DOM `mouseover` / `mouseenter` / `mousemove` 事件 |
| `suspend jsScroll(refid: String, deltaX: Int, deltaY: Int)` | DOM `.scrollBy(dx, dy)` |
| `suspend jsDrag(startRefid: String, endRefid: String)` | DOM 拖拽事件序列 |

### 交互（基于坐标，CSS 文档像素）

| 方法 | 说明 |
|------|------|
| `suspend clickByCoordinates(x: Int, y: Int): OperationResult` | CDP `Input.dispatchMouseEvent`。操作后验证坐标处是否存在元素。 |
| `suspend hoverByCoordinates(x: Int, y: Int)` | CDP 悬停 |
| `suspend scrollByCoordinates(x: Int, y: Int, deltaX: Int, deltaY: Int): OperationResult` | CDP 滚轮。验证滚动位置是否变化。 |
| `suspend dragByCoordinates(startX: Int, startY: Int, endX: Int, endY: Int)` | CDP 拖拽 |

### 键盘

| 方法 | 说明 |
|------|------|
| `suspend press(key: KeyboardKey)` | 按下并释放单个按键 |
| `suspend pressKeyCombination(modifier: KeyboardKey, key: KeyboardKey)` | 组合键 |
| `suspend typeChar(char: Char)` | 键入单个字符 |
| `suspend type(text: String)` | 逐字符键入，30~150ms 随机延迟。不管理焦点，调用方需确保目标已聚焦。 |

### 文件上传

| 方法 | 说明 |
|------|------|
| `suspend uploadFile(refid: String, filePaths: List<String>)` | 通过 CDP `DOM.setFileInputFiles` 设置文件。不弹对话框，不需要用户手势。**仅 JVM；Android/iOS 抛 `UnsupportedOperationException`。** |
| `suspend uploadFileBySelector(selector: String, filePaths: List<String>)` | 同上，使用 CSS 选择器，不依赖 AX tree 缓存。**仅 JVM。** |

### Locator 工厂

| 方法 | 说明 |
|------|------|
| `locator("css=...")` 或 `locator("xpath=...")` | CSS/XPath 选择器，默认 CSS |
| `getByRole(role, name?)` | 按 ARIA role 定位 |
| `getByText(text, exact)` | 按文本内容定位 |
| `getByLabel(label)` | 按关联 label 定位 |
| `getByPlaceholder(text)` | 按 placeholder 定位 |
| `getByAltText(text)` | 按 alt 属性定位 |
| `getByTitle(title)` | 按 title 属性定位 |
| `getByTestId(testId)` | 按 `data-testid` 定位 |

### 新窗口与文件对话框

| 属性 | 说明 |
|------|------|
| `var onNewPage: ((url: String) -> Unit)?` | 代理到 `webView.onNewWindowRequest`。不设置时静默丢弃。 |
| `var onFileDialog: ((request: KBFileDialogRequest, callback: KBFileDialogCallback) -> Unit)?` | 代理到 `webView.onFileDialogRequest`。JVM：设置后由调用方处理；不设置时静默取消。Android/iOS：原生文件对话框。 |

---

## 4. KBLocator — 声明式定位器

`KBLocator` 延迟解析，每次操作时重新查找元素。JVM 优先使用 CDP（无 JS 注入，CSP 安全）；Android/iOS 降级为 JS。

### 坐标模式（默认）

| 方法 | 说明 |
|------|------|
| `suspend click(): OperationResult` | 物理点击元素中心。返回验证结果。 |
| `suspend hover()` | 物理悬停 |
| `suspend scroll(deltaX, deltaY): OperationResult` | 物理滚动。返回验证结果。 |
| `suspend fill(value: String): OperationResult` | 点击聚焦 → 等待 100ms → JS 设值并触发事件。验证 `el.value` 是否匹配。 |
| `suspend type(text: String): OperationResult` | 点击聚焦 → Ctrl+A → Delete → 逐字符物理输入。验证 `el.value` 是否匹配。 |
| `suspend focus()` | 点击聚焦 |
| `suspend check()` | 点击复选框/单选框 |
| `suspend selectOption(value: String)` | 点击下拉框 → 点击匹配项 |
| `suspend press(key: KeyboardKey)` | 点击聚焦 → 物理按键 |
| `suspend pressKeyCombination(modifier, key)` | 点击聚焦 → 物理组合键 |

### JS 模式

| 方法 | 说明 |
|------|------|
| `suspend jsClick()` | DOM `.click()` |
| `suspend jsHover()` | DOM `mouseover`、`mouseenter`、`mousemove` 事件 |
| `suspend jsScroll(deltaX, deltaY)` | DOM `.scrollBy(dx, dy)` |
| `suspend jsFill(value: String): OperationResult` | JS `.focus()` → 等待 100ms → JS 设值。验证 `el.value` 是否匹配。 |
| `suspend jsType(text: String): OperationResult` | JS `.focus()` → Ctrl+A → Delete → 物理按键。验证 `el.value` 是否匹配。 |
| `suspend jsFocus()` | DOM `.focus()` |
| `suspend jsCheck()` | JS 设 checked + 触发 change 事件 |
| `suspend jsSelectOption(value: String)` | JS 设下拉框值 + 触发 change 事件 |
| `suspend jsPress(key: KeyboardKey)` | JS 聚焦 → 物理按键 |
| `suspend jsPressKeyCombination(modifier, key)` | JS 聚焦 → 物理组合键 |

### 查询方法

| 方法 | 说明 |
|------|------|
| `suspend isVisible(): Boolean` | 元素是否可见 |
| `suspend getText(): String` | 获取元素文本 |
| `suspend getAttribute(name: String): String?` | 获取属性值 |
| `suspend count(): Int` | 匹配元素数量 |
| `suspend boundingBox(): Rect?` | 包围盒（CSS 文档像素） |

### 链式过滤

| 方法 | 说明 |
|------|------|
| `filter(predicate: (LocateResult) -> Boolean): KBLocator` | 过滤匹配结果 |
| `nth(index: Int): KBLocator` | 取第 n 个（0-indexed，-1 = 最后一个） |
| `first(): KBLocator` | 取第一个 |
| `last(): KBLocator` | 取最后一个 |

---

## 5. 操作验证

交互方法（`click`、`fill`、`type`、`scroll`）返回 `OperationResult`，程序化验证操作是否成功。调用方（尤其是 AI Agent）无需重新获取完整页面快照即可检测失败。

> **反检测安全**：所有验证手段均为只读 JS API（`elementFromPoint`、`el.value`、`scrollTop`），零检测风险。

### OperationResult

```kotlin
sealed class OperationResult {
    abstract val success: Boolean

    data class Success(
        val action: String,       // "click", "fill", "type", "scroll"
        val verified: Boolean,    // true = 已程序化验证; false = 事件已发送但未确认身份
        val detail: String = ""   // 如 "hit #submitBtn", "value='hello'", "scrollTop: 0 → 150"
    ) : OperationResult()

    data class Failure(
        val action: String,
        val reason: String,       // 如 "hit #overlay, expected #submitBtn (occluded?)"
        val recoverable: Boolean = true
    ) : OperationResult()

    data object Acknowledged : OperationResult()  // 已发送但无法验证（如 hover）
}
```

### 验证策略

| 操作 | 策略 | 说明 |
|------|------|------|
| **click**（带 refid） | `document.elementFromPoint()` 匹配目标 `id`/`tagName`，向上遍历 DOM 树 | 可检测遮挡物（弹窗/banner） |
| **clickByCoordinates** | `elementFromPoint()` 确认坐标处存在元素 | 无目标信息时不做身份检查 |
| **fill / type** | 读回 `el.value`（或 contenteditable 的 `el.innerText`）与期望值对比 | 检测静默输入失败 |
| **scroll** | 对比前后 `scrollTop`（或 `window.scrollY`） | 检测无效滚动 |

### 用法示例

```kotlin
// 检查结果
val result = page.click("r12")
if (!result.success) {
    println("点击失败: $result")
    // AI 可决策：关闭遮挡物后重试，或改用 jsClick
}

// 忽略结果（同样有效）
page.click("r12")
locator.fill("hello")

// 遮挡检测
val result = page.click("r15")
if (result is OperationResult.Failure) {
    println(result.reason)  // "hit #cookie-banner, expected #submitBtn (occluded?)"
    // 关闭 banner，重新 snapshot，重试
}

// 填充验证
val fillResult = locator.fill("user@email.com")
if (fillResult is OperationResult.Success && fillResult.verified) {
    println("已确认: ${fillResult.detail}")  // "value='user@email.com'"
}
```

### 返回 OperationResult 的方法

| 类 | 方法 |
|----|------|
| `KBPage` | `click(refid)`, `scroll(refid, dx, dy)`, `clickByCoordinates(x, y)`, `scrollByCoordinates(x, y, dx, dy)` |
| `KBLocator` | `click()`, `scroll(dx, dy)`, `fill(value)`, `type(text)`, `jsFill(value)`, `jsType(text)` |

无法验证的方法（`hover`、`drag`、键盘事件）保持原有 `Unit` 返回类型。

---

## 6. 数据结构

### AxNode

所有坐标均为 **CSS 文档像素**。

```kotlin
data class AxNode(
    val refid: String,          // 节点唯一 ID
    val tagName: String,        // HTML 标签名
    val role: String,           // ARIA role
    val id: String,             // DOM id 属性
    val className: String,      // CSS class
    val text: String,           // 节点文本内容
    val isVisible: Boolean,     // 是否在视口中可见
    val x: Int,                 // 左上角 X（CSS 文档像素）
    val y: Int,                 // 左上角 Y（CSS 文档像素）
    val width: Int,             // 宽度
    val height: Int,            // 高度
    val centerX: Int,           // 中心点 X（CSS 文档像素）
    val centerY: Int,           // 中心点 Y（CSS 文档像素）
    val childCount: Int,        // 子节点数量
    val attributes: Map<String, String>,
    val iframeSrc: String?,
    val selector: String,       // 动态生成的唯一 CSS 选择器，每次 getRawAxTree() 重新生成
    val occludedBy: String?     // 遮挡该节点的元素 refid，无遮挡时为 null
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

### SnapshotMode 枚举

```kotlin
enum class SnapshotMode {
    VIEWPORT,  // 视口内紧凑序列化，适合 AI 消费
    CLEAN      // 全页面紧凑序列化，去除噪音但保留所有节点（含视口外）
}
```

### SnapshotResult

```kotlin
data class SnapshotResult(
    val yaml: String,       // KBrowser YAML Snapshot 字符串
    val rawTree: AxTreeData // 同一次 fetch 的完整原始数据
)
```

### KeyboardKey 枚举

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

### 其他数据类

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
    data object Initializing : LoadingState  // WebView 刚创建
    data object Loading : LoadingState       // 加载中
    data object Finished : LoadingState      // 加载完成
    data class Error(                        // 加载失败
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

用于 `KBWebView`，为每个实例隔离浏览器数据。`KBrowser.newPage()` 内部自动管理 Profile。

---

## 7. 回调接口

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

## 8. 调试工具

### showScreenshotPreview

```kotlin
fun showScreenshotPreview(bytes: ByteArray)
```

在 JVM 弹出独立 Swing 预览窗口展示截图，鼠标移动时标题栏实时显示 CSS 文档像素坐标。**仅 JVM 有效，Android/iOS 为空操作。**

### JcefChecker

```kotlin
object JcefChecker {
    val isJcefAvailable: Boolean
}
```

检测当前 JDK 是否为包含 JCEF 的 JetBrains Runtime。`KBWebView` Composable 内部检查此值，若为 `false` 则显示提示文字。
