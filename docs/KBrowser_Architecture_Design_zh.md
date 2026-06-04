# KBrowser 架构设计

> [← 返回自述文件](../README_zh.md)

[English](KBrowser_Architecture_Design.md) | 简体中文

---

## 1. 框架定位

KBrowser 是一个 Compose Multiplatform (KMP) 库，提供跨平台的 WebView 组件与编程式浏览器自动化能力。所有公开类与接口统一使用 `KB` 前缀。

框架分为两层：**UI 组件层**（`KBWebView` 接口 + `@Composable KBWebView`）负责网页渲染与展示；**自动化控制层**（`object KBrowser` 单例 + `KBPage`）负责协程化的编程式控制，屏蔽底层线程细节，可在任意后台协程中安全调用。JVM/Desktop 平台底层使用 JetBrains CEF (JCEF) Remote 模式，所有交互通过 Chrome DevTools Protocol (CDP) 完成，不依赖 AWT 鼠标事件。

## 2. 核心架构图

```mermaid
classDiagram
    direction TB

    class KBrowser {
        <<object Singleton>>
        +BrowserConfig config
        +StateFlow~List~KBPage~~ pages
        +configure(config: BrowserConfig)
        +newPage(url: String?, profile: KBProfile?) KBPage
        +getPages() List~KBPage~
        +registerPage(page: KBPage)
        +unregisterPage(page: KBPage)
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
        +suspend getRawAxTree() AxTreeData
        +suspend click(refid: String)
        +suspend hover(refid: String)
        +suspend scroll(refid: String, deltaX: Int, deltaY: Int)
        +suspend drag(startRefid: String, endRefid: String)
        +suspend jsClick(refid: String)
        +suspend jsHover(refid: String)
        +suspend jsScroll(refid: String, deltaX: Int, deltaY: Int)
        +suspend jsDrag(startRefid: String, endRefid: String)
        +suspend clickByCoordinates(x: Int, y: Int)
        +suspend hoverByCoordinates(x: Int, y: Int)
        +suspend scrollByCoordinates(x: Int, y: Int, deltaX: Int, deltaY: Int)
        +suspend dragByCoordinates(startX: Int, startY: Int, endX: Int, endY: Int)
        +suspend screenshot() ByteArray?
        +var onNewPage: ((url: String) -> Unit)?
        +close()
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
        +evaluateJavascript(script: String, callback: ((String) -> Unit)?)
        +suspend takeScreenshot() ByteArray?
        +var onNewWindowRequest: ((url: String) -> Unit)?
        +destroy()
    }

    class KBLocator {
        +KBPage page
        +String selector
        +KBSelectorType selectorType
        +suspend click()
        +suspend hover()
        +suspend scroll(deltaX: Int, deltaY: Int)
        +suspend fill(value: String)
        +suspend type(text: String)
        +suspend focus()
        +suspend check()
        +suspend selectOption(value: String)
        +suspend press(key: KeyboardKey)
        +suspend pressKeyCombination(modifier: KeyboardKey, key: KeyboardKey)
        +suspend jsClick()
        +suspend jsHover()
        +suspend jsScroll(deltaX: Int, deltaY: Int)
        +suspend jsFill(value: String)
        +suspend jsType(text: String)
        +suspend jsFocus()
        +suspend jsCheck()
        +suspend jsSelectOption(value: String)
        +suspend jsPress(key: KeyboardKey)
        +suspend jsPressKeyCombination(modifier: KeyboardKey, key: KeyboardKey)
        +suspend isVisible() Boolean
        +filter(predicate) KBLocator
        +nth(index: Int) KBLocator
    }

    KBrowser "1" *-- "many" KBPage
    KBPage "1" *-- "1" KBWebView
    KBLocator ..> KBPage
```

## 3. 坐标系统说明

**全局统一使用 CSS 文档像素（CSS document pixels）。**

| 场景 | 实现 | 坐标说明 |
|------|------|----------|
| 点击 / 悬停 | CDP `Input.dispatchMouseEvent` | 传入视口坐标：`viewportX = docX - scrollX`，`viewportY = docY - scrollY` |
| 截图 | CDP `Page.captureScreenshot` → 按 DPR 缩小 | 输出图像尺寸 = CSS 像素尺寸，与坐标 1:1 对齐，无黑屏问题 |
| AXTree 节点坐标 | CDP `Accessibility.getFullAXTree` + `DOM.getBoxModel` | `x/y/centerX/centerY` 均为 CSS 文档像素 |
| Locator 定位 | JVM: CDP `DOM.querySelectorAll` / `DOM.performSearch` / `Accessibility.getFullAXTree`（无 JS 注入，CSP 安全）；Android/iOS: JS fallback | 返回坐标同为 CSS 文档像素 |

> **注意**：不存在 DPR 缩放歧义。截图坐标与交互坐标完全一致，可直接用截图像素坐标驱动点击。

## 4. 交互模式与执行路径

KBrowser 提供两套并行的交互模式，在 `KBPage` 和 `KBLocator` 中均以不同的命名清晰区分：

### 坐标模式（物理事件）
* **API 示例**：`page.click(refid)`，`locator.click()`，`page.clickByCoordinates(x, y)`
* **机制**：
  1. 通过缓存 (`refid`) 或选择器查询，获取目标节点中心点的 **CSS 文档坐标 (x, y)**。
  2. 转换坐标为视口像素，通过 CDP 或平台 Touch 事件，向浏览器内核分发真实的物理指针事件 (Mouse/Touch Event)。
* **优势**：
  * 最真实的物理指针模拟，容易通过反爬虫检测。
  * 适用于不需要/不支持 DOM 访问的纯像素交互（例如 Canvas/Flash 内部元素，基于截图坐标的交互）。
* **劣势**：
  * 容易受页面元素遮挡（如弹出框、侧边广告栏）或元素移出视口的影响。

### JS 模式（DOM 事件模拟）
* **API 示例**：`page.jsClick(refid)`，`locator.jsClick()`，`locator.jsFill("val")`
* **机制**：
  1. 根据缓存在 `nodeCache` 中的 `selector` 或 Locator 链式解析出的 `selector`，直接向页面注入执行 Javascript 代码。
  2. JVM 端（JCEF）使用 CDP `Runtime.callFunctionOn` / `Runtime.evaluate` 触发事件，Android/iOS 平台通过 `evaluateJavascript` 触发。
* **优势**：
  * **高稳定性**：完全无视遮挡、视口外问题，直达 DOM 目标。
  * **精准填充**：在 `jsFill`、`jsCheck`、`jsSelectOption` 场景下，绕过坐标点击聚焦过程，直接修改 DOM 属性并分发 change 事件。
  * **混合能力**：`jsType` 和 `jsPress` 采用 "JS 精准聚焦 + 物理键盘输入" 的策略，兼顾定位稳定性和输入真实性。
* **劣势**：
  * 某些极严格的反爬虫系统可能会通过检测 `.isTrusted` 标志来拦截 JS 派发的事件。

---

## 4. 平台要求

| 平台 | 最低版本 | WebView 实现 | 备注 |
|------|----------|-------------|------|
| JVM/Desktop | JBR 25 with JCEF | `JvmWebView` 包装 `JBCefBrowser` | 必须使用 JetBrains Runtime 25，框架不内置 JCEF 下载器 |
| Android | API 34 (Android 14) | `AndroidWebView` 包装系统 `WebView` | 使用 androidx.webkit Multi-Profile API 实现沙盒隔离 |
| iOS | iOS 17.0+ | `IosWebView` 包装 `WKWebView` | 使用 `WKWebsiteDataStore(forIdentifier:)` 实现持久化隔离 |

**JVM 无头模式**：`JvmWebView` 在无头场景下自动创建透明 `JFrame`（1280×800）并将 JCEF 组件挂载其上，无需在 Compose 树中手动挂载。Linux 服务器需配置虚拟显示器（如 `Xvfb`）。

**初始化顺序（JVM 必须严格遵守）**：
```kotlin
KBrowser.configure(BrowserConfig(storageDir = "/path/to/cache"))
initializeKBrowser()   // 必须在 application{} 之前调用
application { /* Compose UI */ }
```

## 5. 线程模型

- `KBPage` 的所有 `suspend` 方法内部通过 `withContext(Dispatchers.Main)` 切回主线程执行，调用方可在任意协程上下文中使用。
- 异步回调（JS 执行结果、页面加载完成）通过 `suspendCancellableCoroutine` 转为挂起，不阻塞线程。
- CPU 密集型操作（AXTree 清洗 `getCleanedAxTree`、视口裁剪 `getViewportAxTree`）是纯 Kotlin 扩展函数，在调用方协程上下文执行，不占用主线程。
- `KBBrowser.pages` 使用 `MutableStateFlow` + `update {}` 原子更新，多协程并发安全。
- `loadUrl` 协程取消时自动调用 `webView.stopLoading()`，防止残余加载干扰后续操作。
