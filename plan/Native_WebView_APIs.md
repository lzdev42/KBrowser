# 原生 WebView API 核心对比参考表

为了设计出符合各平台直觉的 `KBWebView` API，下面整理了 Android 官方的 `android.webkit.WebView` 与 iOS 官方的 `WKWebView` 在核心功能上的方法、委托（Delegate）和架构对比。

## 1. 核心操作方法 (Core Methods)

| 功能归类 | Android (`WebView`) | iOS (`WKWebView`) |
| :--- | :--- | :--- |
| **加载网页** | `loadUrl(String url)` | `load(URLRequest)` |
| **加载 HTML 源码** | `loadData(...)` / `loadDataWithBaseURL(...)` | `loadHTMLString(_:baseURL:)` |
| **重新加载** | `reload()` | `reload()` / `reloadFromOrigin()` |
| **停止加载** | `stopLoading()` | `stopLoading()` |
| **历史导航** | `goBack()`, `goForward()`, `canGoBack()`, `canGoForward()`, `goBackOrForward(steps)` | `goBack()`, `goForward()`, `canGoBack`, `canGoForward`, `go(to: WKBackForwardListItem)` |
| **执行 JS** | `evaluateJavascript(String script, ValueCallback)` | `evaluateJavaScript(_:completionHandler:)` |
| **注入 Native 接口** | `addJavascriptInterface(Object, String)` | 通过 `WKUserContentController.add(_:name:)` 注入 |
| **清理缓存** | `clearCache(boolean)` | `WKWebsiteDataStore.default().removeData(...)` |

---

## 2. 核心回调与委托机制 (Delegates / Callbacks)

Android 和 iOS 都不约而同地把回调拆分成了两部分：**导航/生命周期控制** 和 **UI/弹窗交互控制**。

### A. 导航与加载状态控制 (Navigation & Lifecycle)
> **Android**: `WebViewClient`
> **iOS**: `WKNavigationDelegate`

| 触发时机 / 作用 | Android (`WebViewClient`) | iOS (`WKNavigationDelegate`) |
| :--- | :--- | :--- |
| **即将跳转 (拦截请求)** | `shouldOverrideUrlLoading(...)` | `webView(_:decidePolicyFor:decisionHandler:)` |
| **页面开始加载** | `onPageStarted(...)` | `webView(_:didStartProvisionalNavigation:)` |
| **页面加载完成** | `onPageFinished(...)` | `webView(_:didFinishNavigation:)` |
| **页面加载失败** | `onReceivedError(...)` | `webView(_:didFailProvisionalNavigation:)` <br> `webView(_:didFailNavigation:)` |
| **资源请求拦截 (替换内容)** | `shouldInterceptRequest(...)` | iOS 无直接对应 API，需通过 `WKURLSchemeHandler` 拦截 |

### B. UI 与浏览器行为交互 (UI & Browser Interaction)
> **Android**: `WebChromeClient`
> **iOS**: `WKUIDelegate`

| 触发时机 / 作用 | Android (`WebChromeClient`) | iOS (`WKUIDelegate`) |
| :--- | :--- | :--- |
| **网页 JS `alert()` 弹窗** | `onJsAlert(...)` | `webView(_:runJavaScriptAlertPanelWithMessage:)` |
| **网页 JS `confirm()` 确认框** | `onJsConfirm(...)` | `webView(_:runJavaScriptConfirmPanelWithMessage:)` |
| **网页 JS `prompt()` 输入框** | `onJsPrompt(...)` | `webView(_:runJavaScriptTextInputPanelWithPrompt:)` |
| **标题变化** | `onReceivedTitle(...)` | 监听 `WKWebView.title` (KVO) |
| **加载进度变化** | `onProgressChanged(...)` | 监听 `WKWebView.estimatedProgress` (KVO) |
| **文件选择 `<input type="file">`** | `onShowFileChooser(...)` | iOS 底层自动处理弹出文件选择器 |
| **要求创建新窗口 (`window.open`)** | `onCreateWindow(...)` | `webView(_:createWebViewWithConfiguration:)` |
| **权限请求 (摄像头/麦克风等)** | `onPermissionRequest(...)` | iOS 15+ 使用 `WKUIDelegate.webView(_:requestMediaCapturePermissionFor:)` |

---

## 3. 配置与环境对象 (Configuration & Environment)

两端都提供了专门的对象来管理浏览器的全局偏好设置。

| 配置领域 | Android | iOS |
| :--- | :--- | :--- |
| **偏好设置** | `WebSettings` <br> *(如 `setJavaScriptEnabled(true)`, `setUserAgentString(...)`)* | `WKWebViewConfiguration` <br> *(含 `WKPreferences`, `WKWebpagePreferences`)* |
| **Cookie 管理** | `CookieManager.getInstance()` | `WKHTTPCookieStore` (通过 `configuration.websiteDataStore` 获取) |
| **独立会话/隔离环境** | 无原生支持 (缓存路径全局共享) | 可通过自定义 `WKWebsiteDataStore.nonPersistent()` 实现完全隔离的隐身模式。 |

## 总结启示

通过对比可以看出，两端在架构上高度一致，这为我们设计跨平台的 `KBWebView` API 提供了很好的思路：
1. **指令方法**（`load`, `evaluateJavascript`）是高度统一的。
2. **回调接口必须拆分**：就像两端都区分了 Navigation 和 UI，我们也应该在 `KBWebView` 中提供 `NavigationListener`（管拦截和加载进度）和 `UIListener`（管弹窗和文件选择）。
3. **配置解耦**：像 UserAgent、是否开启 JS 等偏好，最好放在一个 `WebSettings` 对象里，而不是全部写死在 WebView 对象上。
