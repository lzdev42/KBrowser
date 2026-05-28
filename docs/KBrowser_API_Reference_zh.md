# KBrowser API 参考文档

> [← 返回自述文件](../README_zh.md)

[English](KBrowser_API_Reference.md) | 简体中文

> **坐标系统**：全文所有坐标均为 **CSS 文档像素** (CSS document pixels)，截图与交互坐标完全一致。

---

## 1. 快速开始

JVM 平台必须在 `application {}` 之前完成初始化：

```kotlin
// main.kt
fun main() {
    // 1. 配置存储路径
    KBrowser.configure(BrowserConfig(storageDir = "/path/to/cache"))
    // 2. 初始化 JCEF 引擎（必须在任何 UI 初始化之前调用）
    initializeKBrowser()

    // 3. 启动 Compose 应用
    application {
        Window(onCloseRequest = ::exitApplication) {
            App()
        }
    }
}
```

---

## 2. KBWebView — UI 组件层

`KBWebView` 是平台无关的接口，代表一个网页渲染实例。通过 `rememberKBWebView` 创建，通过 `@Composable KBWebView` 挂载展示。

### 状态流 (StateFlow)

| 属性 | 类型 | 说明 |
|------|------|------|
| `currentUrl` | `StateFlow<String?>` | 当前页面 URL |
| `currentTitle` | `StateFlow<String?>` | 当前页面标题 |
| `loadingState` | `StateFlow<LoadingState>` | 加载状态：`IDLE` / `LOADING` / `FINISHED` / `ERROR` |
| `progress` | `StateFlow<Float>` | 加载进度 0.0f ~ 1.0f |
| `canGoBack` | `StateFlow<Boolean>` | 是否可后退 |
| `canGoForward` | `StateFlow<Boolean>` | 是否可前进 |

### 导航方法

| 方法 | 说明 |
|------|------|
| `loadUrl(url: String)` | 加载网络地址 |
| `loadHtml(html: String)` | 加载 HTML 字符串 |
| `reload()` | 刷新当前页面 |
| `stopLoading()` | 停止加载 |
| `goBack()` | 后退 |
| `goForward()` | 前进 |

### JS 交互

| 方法 | 说明 |
|------|------|
| `evaluateJavascript(script, callback?)` | 执行 JS，结果通过 callback 返回 JSON 字符串 |
| `registerJsCallback(name, callback)` | 注册 Native 回调，JS 侧通过 `window.<name>(data)` 调用 |
| `unregisterJsCallback(name)` | 注销 JS 回调 |

### 生命周期与其他

| 方法 | 说明 |
|------|------|
| `clearCacheAndCookies()` | 清除当前 Profile 的缓存与 Cookie |
| `destroy()` | 销毁 WebView，释放资源 |
| `setWebViewClient(client?)` | 设置页面加载回调 |
| `setWebChromeClient(client?)` | 设置 JS 对话框 / 权限回调 |
| `suspend takeScreenshot(): ByteArray?` | CDP 截图，输出 CSS 像素大小的 PNG，无黑屏问题 |
| `var onNewWindowRequest: ((url: String) -> Unit)?` | 新标签页/新窗口请求回调；不设置时静默丢弃 |

### Composable 用法示例

```kotlin
@Composable
fun BrowserScreen() {
    val webView = rememberKBWebView(initialUrl = "https://example.com")

    LaunchedEffect(webView) {
        webView.setWebViewClient(object : KBWebViewClient {
            override fun onPageStarted(url: String) { println("开始加载: $url") }
            override fun onPageFinished(url: String) { println("加载完成: $url") }
            override fun onReceivedError(error: Diagnostics) { println("加载失败: ${error.description}") }
        })
        // 拦截新窗口请求
        webView.onNewWindowRequest = { url -> println("新窗口: $url") }
    }

    Column(Modifier.fillMaxSize()) {
        val url by webView.currentUrl.collectAsState()
        Text("当前页面: $url")

        KBWebView(webView = webView, modifier = Modifier.weight(1f))

        Row {
            Button(onClick = { webView.goBack() }) { Text("后退") }
            Button(onClick = { webView.goForward() }) { Text("前进") }
            Button(onClick = { webView.reload() }) { Text("刷新") }
        }
    }
}
```

---

## 3. KBPage — 自动化控制层

`KBPage` 是对 `KBWebView` 的协程封装，所有方法均为 `suspend`，可在任意协程上下文中安全调用。通过 `KBrowser.newPage()` 创建。

### 状态流

与 `KBWebView` 相同，直接代理：`currentUrl`、`title`、`loadingState`、`progress`。

### 导航

| 方法 | 说明 |
|------|------|
| `suspend loadUrl(url: String)` | 加载 URL，挂起直到 `onPageFinished`；协程取消时自动 `stopLoading()` |
| `suspend evaluateJavascript(script: String): String` | 执行 JS，返回 JSON 字符串结果 |
| `suspend clearCacheAndCookies()` | 清除缓存与 Cookie |
| `suspend setCookieViaJs(cookieString: String)` | 通过 `document.cookie` 注入 Cookie 字符串 |
| `suspend screenshot(): ByteArray?` | CDP 截图，返回 CSS 像素大小的 PNG 字节数组 |
| `close()` | 销毁底层 WebView |

### AXTree 语义树

| 方法 | 说明 |
|------|------|
| `suspend getRawAxTree(): AxTreeData` | 获取完整语义树，同时刷新内部坐标缓存 `nodeCache` |
| `AxTreeData.getCleanedAxTree(): AxTreeData` | 扩展函数，过滤噪音节点（不可见、纯布局容器等） |
| `AxTreeData.getViewportAxTree(): AxTreeData` | 扩展函数，裁剪到当前视口范围内的节点 |

两个扩展函数是纯 Kotlin 计算，不切线程，在调用方协程上下文执行。

### 交互（基于 refid）

调用前需先执行 `getRawAxTree()` 刷新缓存。若 refid 不存在则抛出 `ElementNotFoundException`。

`AxNode` 中的同一个 `refid` 支持两种点击策略，开发者按需选择：

| 方法 | 说明 |
|------|------|
| `suspend click(refid: String)` | **坐标点击**：从缓存取 `centerX`/`centerY`，通过 CDP 发送物理点击。若元素被遮挡可能失败。 |
| `suspend clickDom(refid: String)` | **DOM 直接点击**：直接操作 DOM 节点，完全绕过坐标 hit-test，不受遮挡影响。JVM：CDP `DOM.resolveNode` + `Runtime.callFunctionOn`（无 JS 注入，CSP 安全）；Android/iOS：通过特权 JS 上下文调用 `__kb_element_map.get(refid).click()`。DOM 操作失败时自动回退到坐标点击。 |
| `suspend hover(refid: String)` | 从缓存取坐标，CDP 发送悬停事件 |
| `suspend scroll(refid: String, deltaX: Int, deltaY: Int)` | 从缓存取坐标，CDP 发送滚轮事件 |
| `suspend fill(refid: String, text: String)` | `clickDom(refid)` → 等待 100ms → `type(text)`。通过 DOM 直接点击聚焦，再用原生键盘事件输入文字。 |

### 交互（基于坐标，CSS 文档像素）

| 方法 | 说明 |
|------|------|
| `suspend clickByCoordinates(x: Int, y: Int)` | CDP `Input.dispatchMouseEvent`，自动转换为视口坐标 |
| `suspend hoverByCoordinates(x: Int, y: Int)` | CDP 悬停 |
| `suspend scrollByCoordinates(x: Int, y: Int, deltaX: Int, deltaY: Int)` | CDP 滚轮 |
| `suspend dragByCoordinates(startX: Int, startY: Int, endX: Int, endY: Int)` | CDP 模拟鼠标拖拽 |

### 键盘

| 方法 | 说明 |
|------|------|
| `suspend press(key: KeyboardKey)` | 按下并释放单个按键 |
| `suspend pressKeyCombination(modifier: KeyboardKey, key: KeyboardKey)` | 组合键，如 `Ctrl+A` |
| `suspend typeChar(char: Char)` | 键入单个字符 |
| `suspend type(text: String)` | 逐字符键入，每字符间随机延迟 30~150ms 模拟真人输入。不管理焦点，调用方需自行确保目标元素已聚焦。 |

### Locator 工厂

| 方法 | 说明 |
|------|------|
| `locator("css=...")` 或 `locator("xpath=...")` | CSS/XPath 选择器，默认 CSS |
| `getByRole(role, name?)` | 按 ARIA role 定位，可附加 name 过滤 |
| `getByText(text, exact)` | 按文本内容定位，`exact=true` 精确匹配 |
| `getByLabel(label)` | 按关联 label 定位 |
| `getByPlaceholder(text)` | 按 placeholder 属性定位 |
| `getByAltText(text)` | 按 alt 属性定位 |
| `getByTitle(title)` | 按 title 属性定位 |
| `getByTestId(testId)` | 按 `data-testid` 属性定位 |

### 新窗口

| 属性 | 说明 |
|------|------|
| `var onNewPage: ((url: String) -> Unit)?` | 代理到 `webView.onNewWindowRequest`；`target="_blank"` 和 `window.open()` 均触发；不设置时静默丢弃 |

---

## 4. KBLocator — 声明式定位器

`KBLocator` 延迟解析，每次操作时重新查找元素。JVM 平台优先使用 CDP（无 JS 注入，CSP 安全），Android/iOS fallback 到 JS。

### 交互方法

JVM 平台：当 CDP 定位到元素时会携带 `backendNodeId`，所有点击类方法自动优先走 DOM 直接点击（绕过遮挡），Android/iOS 回退到坐标点击。

| 方法 | 说明 |
|------|------|
| `suspend click()` | 定位元素并点击。JVM 优先 DOM 直接点击（绕过遮挡）；Android/iOS 坐标点击。 |
| `suspend hover()` | 定位元素，发送悬停事件 |
| `suspend scroll(deltaX, deltaY)` | 定位元素，发送滚轮事件 |
| `suspend fill(value: String)` | 点击聚焦后通过 JS 设置输入框值（快速填充） |
| `suspend type(text: String)` | 点击聚焦 → Ctrl+A → Delete → 逐字符物理键入（高防检测） |
| `suspend focus()` | 点击聚焦元素 |
| `suspend check()` | 点击复选框/单选框 |
| `suspend selectOption(value: String)` | 点击下拉框，再点击匹配的 option |
| `suspend press(key: KeyboardKey)` | 聚焦后发送单键事件 |
| `suspend pressKeyCombination(modifier, key)` | 聚焦后发送组合键 |

### 查询方法

| 方法 | 说明 |
|------|------|
| `suspend isVisible(): Boolean` | 元素是否可见 |
| `suspend getText(): String` | 获取元素文本 |
| `suspend getAttribute(name: String): String?` | 获取指定属性值 |
| `suspend count(): Int` | 匹配元素数量 |
| `suspend boundingBox(): Rect?` | 获取元素包围盒（CSS 文档像素） |

### 链式过滤

| 方法 | 说明 |
|------|------|
| `filter(predicate: (LocateResult) -> Boolean): KBLocator` | 在匹配结果中进一步过滤 |
| `nth(index: Int): KBLocator` | 取第 n 个匹配项（0-indexed，-1 表示最后一个） |
| `first(): KBLocator` | 取第一个 |
| `last(): KBLocator` | 取最后一个 |

### 链式示例

```kotlin
// 找到所有可见的"提交"按钮中的第二个，点击
page.getByRole("button", name = "提交")
    .filter { it.isVisible }
    .nth(1)
    .click()

// XPath 定位 + 填充
page.locator("xpath=//input[@name='email']").fill("user@example.com")
```

---

## 5. 数据结构

### AxNode

所有坐标均为 **CSS 文档像素**。

```kotlin
data class AxNode(
    val refid: String,          // 节点唯一 ID，用于 click(refid) 等交互
    val tagName: String,        // HTML 标签名，如 "button"、"input"
    val role: String,           // ARIA role，如 "button"、"textbox"
    val id: String,             // DOM id 属性
    val className: String,      // CSS class
    val text: String,           // 节点文本内容
    val isVisible: Boolean,     // 是否在视口中可见
    val x: Int,                 // 节点左上角 X（CSS 文档像素）
    val y: Int,                 // 节点左上角 Y（CSS 文档像素）
    val width: Int,             // 节点宽度
    val height: Int,            // 节点高度
    val centerX: Int,           // 中心点 X（CSS 文档像素）
    val centerY: Int,           // 中心点 Y（CSS 文档像素）
    val childCount: Int,        // 子节点数量
    val attributes: Map<String, String>, // 节点属性字典
    val iframeSrc: String?      // 若为 iframe，则为其 src URL
)
```

### AxTreeData

```kotlin
data class AxTreeData(
    val url: String,            // 当前页面 URL
    val innerWidth: Int,        // 视口宽度（CSS 像素）
    val innerHeight: Int,       // 视口高度（CSS 像素）
    val scrollX: Int,           // 横向滚动偏移（CSS 像素）
    val scrollY: Int,           // 纵向滚动偏移（CSS 像素）
    val documentWidth: Int,     // 文档总宽度（CSS 像素）
    val documentHeight: Int,    // 文档总高度（CSS 像素）
    val devicePixelRatio: Double, // DPR，截图时内部使用，调用方无需关心
    val totalElements: Int,
    val visibleElements: Int,
    val hiddenElements: Int,
    val iframeCount: Int,
    val nodes: List<AxNode>
)
```

### KeyboardKey 枚举

```kotlin
enum class KeyboardKey {
    // 特殊键
    ENTER, TAB, ESCAPE, BACKSPACE, DELETE,
    // 方向键
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
    // 修饰键
    SHIFT, CONTROL, ALT, META,  // META = Mac Command
    // 空格与导航
    SPACE, HOME, END, PAGE_UP, PAGE_DOWN, INSERT,
    // 功能键
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    // 常用组合键字母
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

data class BrowserConfig(val storageDir: String)

data class KBProfile(val profileId: String, val storageDir: String)
```

---

## 6. 回调接口

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
```

---

## 7. 完整使用示例

### 示例 1：UI 浏览器（KBWebView Composable）

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
            // 在当前页面打开，而不是弹出新窗口
            webView.loadUrl(newUrl)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            TextField(
                value = inputUrl,
                onValueChange = { inputUrl = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入网址") }
            )
            Button(onClick = { webView.loadUrl(inputUrl) }) { Text("跳转") }
        }

        if (loading == LoadingState.LOADING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        KBWebView(webView = webView, modifier = Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = { webView.goBack() }) { Text("←") }
            Button(onClick = { webView.goForward() }) { Text("→") }
            Button(onClick = { webView.reload() }) { Text("刷新") }
        }
    }
}
```

### 示例 2：无头自动化（KBPage 协程流水线）

```kotlin
suspend fun runAutomation() {
    // 初始化（main.kt 中已完成，此处仅示意）
    // KBrowser.configure(BrowserConfig(storageDir = "/tmp/kbrowser"))
    // initializeKBrowser()

    val page = KBrowser.newPage(
        url = "https://example.com/login",
        profile = KBProfile("session_001", "/tmp/kbrowser/session_001")
    )

    try {
        // 拦截新窗口请求
        page.onNewPage = { url ->
            println("新窗口请求: $url")
            // 如需处理：val newPage = KBrowser.newPage(url)
        }

        // 等待页面加载完成
        page.loadUrl("https://example.com/login")

        // 方式一：使用 Locator（推荐，CSP 安全）
        page.getByLabel("用户名").fill("admin")
        page.getByLabel("密码").type("secret123")  // 物理键入，高防检测
        page.getByRole("button", name = "登录").click()

        // 等待跳转后的页面加载
        page.loadUrl("https://example.com/dashboard")

        // 方式二：使用 AXTree 语义树
        val rawTree = page.getRawAxTree()
        val cleanTree = rawTree.getCleanedAxTree()
        val viewportTree = cleanTree.getViewportAxTree()

        println("视口内可见元素数: ${viewportTree.visibleElements}")

        // 找到第一个链接并点击
        val firstLink = viewportTree.nodes.firstOrNull { it.role == "link" }
        if (firstLink != null) {
            // 方式 A：坐标点击（若元素被遮挡可能失败）
            page.click(firstLink.refid)

            // 方式 B：DOM 直接点击（绕过遮挡，推荐）
            // page.clickDom(firstLink.refid)
        }

        // 找到输入框，一步完成聚焦+输入（DOM 直接点击 + 原生键盘事件）
        val searchInput = viewportTree.nodes.firstOrNull { it.role == "textbox" }
        if (searchInput != null) {
            page.fill(searchInput.refid, "搜索词")
        }

        // 截图（CSS 像素，与坐标系一致）
        val png = page.screenshot()
        if (png != null) {
            File("/tmp/screenshot.png").writeBytes(png)
            println("截图已保存，尺寸与坐标系 1:1 对齐")
        }

        // 键盘操作示例
        page.locator("css=.search-input").click()
        page.press(KeyboardKey.CONTROL)  // 单独按修饰键
        page.pressKeyCombination(KeyboardKey.CONTROL, KeyboardKey.A)  // Ctrl+A 全选
        page.type("新的搜索词")

    } catch (e: ElementNotFoundException) {
        println("元素未找到: ${e.message}")
    } finally {
        page.close()
    }
}
```
