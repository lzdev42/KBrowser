package xyz.kbrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.kbrowser.webview.KBPage
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.LoadingState

// MVI State
data class WebViewExampleViewState(
    val page: KBPage? = null,
    val logs: List<String> = listOf("API 调试实验室已就绪"),
    
    // 模式 0: Markdown 渲染, 1: 网页 API 调试
    val selectedMode: Int = 0,
    
    // 输入与控制面板状态
    val markdownInput: String = INITIAL_MARKDOWN,
    val navigateUrlInput: String = "https://www.bing.com/",
    val jsInput: String = "document.title",
    
    // 捕获坐标
    val capturedX: Int = 0,
    val capturedY: Int = 0,
    val isCaptureCallbackRegistered: Boolean = true,
    
    // 手势模拟输入
    val coordXInput: String = "0",
    val coordYInput: String = "0",
    
    // 收集自底层 KBWebView 的实时响应式状态
    val currentUrl: String = "",
    val currentTitle: String = "",
    val loadingState: LoadingState = LoadingState.Initializing,
    val progress: Float = 0f,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
)

// MVI Intent
sealed interface WebViewExampleIntent {
    data class ChangeMode(val mode: Int) : WebViewExampleIntent
    data class ChangeMarkdown(val text: String) : WebViewExampleIntent
    data class ChangeUrlInput(val url: String) : WebViewExampleIntent
    data class ChangeJsInput(val js: String) : WebViewExampleIntent
    data class ChangeCoordX(val x: String) : WebViewExampleIntent
    data class ChangeCoordY(val y: String) : WebViewExampleIntent
    object UseCapturedCoords : WebViewExampleIntent
    data class ToggleCaptureCallback(val enabled: Boolean) : WebViewExampleIntent
    
    // 基础导航
    object Navigate : WebViewExampleIntent
    object LoadLocalInteractiveTestPage : WebViewExampleIntent
    object Reload : WebViewExampleIntent
    object StopLoading : WebViewExampleIntent
    object GoBack : WebViewExampleIntent
    object GoForward : WebViewExampleIntent
    
    // 脚本与回调
    object ExecuteJs : WebViewExampleIntent
    object ClearLogs : WebViewExampleIntent
    object ClearCache : WebViewExampleIntent
    
    // 坐标手势模拟
    object SimulateClick : WebViewExampleIntent
    object SimulateHover : WebViewExampleIntent
    object SimulateScroll : WebViewExampleIntent
    object SimulateDrag : WebViewExampleIntent
}

class WebViewExampleViewModel : ViewModel() {
    private val _state = MutableStateFlow(WebViewExampleViewState())
    val state: StateFlow<WebViewExampleViewState> = _state.asStateFlow()

    init {
        initWebView()
    }

    private fun initWebView() {
        viewModelScope.launch {
            try {
                log("正在初始化示例 Web 页面...")
                // 创建一个用于示例的独立 session 标签页
                val newPage = KBrowser.newPage(
                    url = null // 稍后手动加载
                )
                _state.update { it.copy(page = newPage) }
                
                // 监听底层的 StateFlow 并更新 UI 状态
                launch {
                    newPage.webView.currentUrl.collect { url ->
                        _state.update { it.copy(currentUrl = url ?: "") }
                    }
                }
                launch {
                    newPage.webView.currentTitle.collect { title ->
                        _state.update { it.copy(currentTitle = title ?: "") }
                    }
                }
                launch {
                    newPage.webView.loadingState.collect { lState ->
                        _state.update { it.copy(loadingState = lState) }
                        if (lState == LoadingState.Loading) {
                            log("网页加载中...")
                        } else if (lState == LoadingState.Finished) {
                            log("网页加载完成")
                        }
                    }
                }
                launch {
                    newPage.webView.progress.collect { prog ->
                        _state.update { it.copy(progress = prog) }
                    }
                }
                launch {
                    newPage.webView.canGoBack.collect { back ->
                        _state.update { it.copy(canGoBack = back) }
                    }
                }
                launch {
                    newPage.webView.canGoForward.collect { forward ->
                        _state.update { it.copy(canGoForward = forward) }
                    }
                }

                // 注册 JS 回调
                registerCallbacks(newPage)
                
                // 默认在 Markdown 模式下，直接渲染初始 Markdown
                log("正在加载初始 Markdown 手册...")
                val html = markdownToHtml(_state.value.markdownInput)
                newPage.webView.loadHtml(html)
                log("初始 Markdown 加载完毕！")

            } catch (e: Exception) {
                log("初始化失败: ${e.message}")
            }
        }
    }

    private fun registerCallbacks(page: KBPage) {
        // 注册坐标捕获回调
        page.webView.registerJsCallback("onMouseMove") { data ->
            if (_state.value.isCaptureCallbackRegistered) {
                val parts = data.split(",")
                if (parts.size == 2) {
                    val x = parts[0].toIntOrNull() ?: 0
                    val y = parts[1].toIntOrNull() ?: 0
                    _state.update { it.copy(capturedX = x, capturedY = y) }
                }
            }
        }
        // 注册自定义动作回调
        page.webView.registerJsCallback("onCustomAction") { msg ->
            log("收到网页 JS 回调事件: \"$msg\"")
        }
    }

    fun dispatch(intent: WebViewExampleIntent) {
        val page = _state.value.page ?: return
        when (intent) {
            is WebViewExampleIntent.ChangeMode -> {
                val prevMode = _state.value.selectedMode
                _state.update { it.copy(selectedMode = intent.mode) }
                if (intent.mode == 0 && prevMode != 0) {
                    // 切换回 Markdown，重新渲染当前 Markdown 源码
                    val html = markdownToHtml(_state.value.markdownInput)
                    page.webView.loadHtml(html)
                    log("已切换至 Markdown 渲染模式")
                } else if (intent.mode == 1 && prevMode != 1) {
                    // 切换到 网页 API 模式，加载本地交互网页作为默认页
                    loadLocalTestPage(page)
                    log("已切换至 网页 API 调试模式，已自动加载本地交互测试页")
                }
            }
            is WebViewExampleIntent.ChangeMarkdown -> {
                _state.update { it.copy(markdownInput = intent.text) }
                // 实时渲染
                val html = markdownToHtml(intent.text)
                page.webView.loadHtml(html)
            }
            is WebViewExampleIntent.ChangeUrlInput -> {
                _state.update { it.copy(navigateUrlInput = intent.url) }
            }
            is WebViewExampleIntent.ChangeJsInput -> {
                _state.update { it.copy(jsInput = intent.js) }
            }
            is WebViewExampleIntent.ChangeCoordX -> {
                _state.update { it.copy(coordXInput = intent.x) }
            }
            is WebViewExampleIntent.ChangeCoordY -> {
                _state.update { it.copy(coordYInput = intent.y) }
            }
            is WebViewExampleIntent.UseCapturedCoords -> {
                _state.update {
                    it.copy(
                        coordXInput = it.capturedX.toString(),
                        coordYInput = it.capturedY.toString()
                    )
                }
                log("已填充捕获的坐标: (${_state.value.capturedX}, ${_state.value.capturedY})")
            }
            is WebViewExampleIntent.ToggleCaptureCallback -> {
                _state.update { it.copy(isCaptureCallbackRegistered = intent.enabled) }
                if (intent.enabled) {
                    registerCallbacks(page)
                    log("已绑定 'onMouseMove' 鼠标坐标捕获回调")
                } else {
                    page.webView.unregisterJsCallback("onMouseMove")
                    log("已注销 'onMouseMove' 鼠标坐标捕获回调")
                }
            }
            is WebViewExampleIntent.Navigate -> {
                val url = _state.value.navigateUrlInput
                if (url.isNotBlank()) {
                    viewModelScope.launch {
                        try {
                            log("开始导航至: $url")
                            page.loadUrl(url)
                        } catch (e: Exception) {
                            log("导航失败: ${e.message}")
                        }
                    }
                }
            }
            is WebViewExampleIntent.LoadLocalInteractiveTestPage -> {
                loadLocalTestPage(page)
            }
            is WebViewExampleIntent.Reload -> {
                log("执行页面刷新 (reload)")
                page.webView.reload()
            }
            is WebViewExampleIntent.StopLoading -> {
                log("中止页面加载 (stopLoading)")
                page.webView.stopLoading()
            }
            is WebViewExampleIntent.GoBack -> {
                log("历史导航: 后退 (goBack)")
                page.webView.goBack()
            }
            is WebViewExampleIntent.GoForward -> {
                log("历史导航: 前进 (goForward)")
                page.webView.goForward()
            }
            is WebViewExampleIntent.ExecuteJs -> {
                val js = _state.value.jsInput
                if (js.isNotBlank()) {
                    log("执行 JS 脚本: \"$js\"")
                    viewModelScope.launch {
                        try {
                            val result = page.evaluateJavascript(js)
                            log("JS 执行结果: $result")
                        } catch (e: Exception) {
                            log("JS 执行失败: ${e.message}")
                        }
                    }
                }
            }
            is WebViewExampleIntent.ClearLogs -> {
                _state.update { it.copy(logs = emptyList()) }
            }
            is WebViewExampleIntent.ClearCache -> {
                viewModelScope.launch {
                    page.clearCacheAndCookies()
                    log("无痕清理：Cookie 与缓存已成功清除")
                }
            }
            is WebViewExampleIntent.SimulateClick -> {
                val x = _state.value.coordXInput.toIntOrNull() ?: 0
                val y = _state.value.coordYInput.toIntOrNull() ?: 0
                log("模拟原生坐标物理点击: ($x, $y)")
                viewModelScope.launch {
                    try {
                        page.clickByCoordinates(x, y)
                        log("坐标点击事件发送完毕")
                    } catch (e: Exception) {
                        log("坐标点击失败: ${e.message}")
                    }
                }
            }
            is WebViewExampleIntent.SimulateHover -> {
                val x = _state.value.coordXInput.toIntOrNull() ?: 0
                val y = _state.value.coordYInput.toIntOrNull() ?: 0
                log("模拟原生坐标物理悬停: ($x, $y)")
                viewModelScope.launch {
                    try {
                        page.hoverByCoordinates(x, y)
                        log("坐标悬停事件发送完毕")
                    } catch (e: Exception) {
                        log("坐标悬停失败: ${e.message}")
                    }
                }
            }
            is WebViewExampleIntent.SimulateScroll -> {
                val x = _state.value.coordXInput.toIntOrNull() ?: 0
                val y = _state.value.coordYInput.toIntOrNull() ?: 0
                log("模拟原生坐标物理滚动: 从 ($x, $y) 向下滚动 150 像素")
                viewModelScope.launch {
                    try {
                        page.scrollByCoordinates(x, y, 0, 150)
                        log("坐标滚动指令发送完毕")
                    } catch (e: Exception) {
                        log("坐标滚动失败: ${e.message}")
                    }
                }
            }
            is WebViewExampleIntent.SimulateDrag -> {
                val x = _state.value.coordXInput.toIntOrNull() ?: 0
                val y = _state.value.coordYInput.toIntOrNull() ?: 0
                val destX = x + 100
                val destY = y
                log("模拟原生坐标物理拖拽: 从 ($x, $y) 到 ($destX, $destY)")
                viewModelScope.launch {
                    try {
                        page.dragByCoordinates(x, y, destX, destY)
                        log("坐标拖拽指令发送完毕")
                    } catch (e: Exception) {
                        log("坐标拖拽失败: ${e.message}")
                    }
                }
            }
        }
    }

    private fun loadLocalTestPage(page: KBPage) {
        log("加载本地交互测试 HTML...")
        page.webView.loadHtml(LOCAL_HTML_TEMPLATE)
        log("本地交互测试 HTML 加载完成")
    }

    private fun log(message: String) {
        val totalSeconds = currentTimeMillis() / 1000
        val hours = (totalSeconds / 3600) % 24
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        val timeString = "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        _state.update {
            it.copy(logs = it.logs + "[$timeString] $message")
        }
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.page?.close()
    }
}

// 简易 Markdown 解析器
private fun markdownToHtml(markdown: String): String {
    val lines = markdown.split("\n")
    val htmlBuilder = StringBuilder()
    var inList = false
    var inCodeBlock = false
    
    htmlBuilder.append("""
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <style>
            body {
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                background-color: #121214;
                color: #e0e0e0;
                padding: 24px;
                line-height: 1.6;
                margin: 0;
            }
            h1, h2, h3, h4 {
                color: #64B5F6;
                font-weight: 600;
                margin-top: 24px;
                margin-bottom: 12px;
                border-bottom: 1px solid #2e2e36;
                padding-bottom: 6px;
            }
            h1 { font-size: 28px; }
            h2 { font-size: 22px; }
            h3 { font-size: 18px; }
            p { margin-top: 0; margin-bottom: 16px; color: #d0d0d5; }
            code {
                background-color: #1e1e24;
                padding: 3px 6px;
                border-radius: 4px;
                font-family: monospace;
                color: #ff79c6;
                font-size: 13.sp;
            }
            pre {
                background-color: #1e1e24;
                padding: 16px;
                border-radius: 8px;
                overflow-x: auto;
                border: 1px solid #2e2e36;
                margin-bottom: 16px;
            }
            pre code {
                background-color: transparent;
                padding: 0;
                color: #f8f8f2;
                font-size: 13px;
            }
            a { color: #81C784; text-decoration: none; font-weight: 500; }
            a:hover { text-decoration: underline; }
            ul { margin-top: 0; margin-bottom: 16px; padding-left: 20px; }
            li { margin-bottom: 6px; color: #d0d0d5; }
            blockquote {
                border-left: 4px solid #64B5F6;
                margin: 0 0 16px 0;
                padding-left: 16px;
                color: #888894;
                font-style: italic;
            }
            hr {
                border: 0;
                height: 1px;
                background: #2e2e36;
                margin: 24px 0;
            }
        </style>
        </head>
        <body>
    """.trimIndent())
    
    for (rawLine in lines) {
        val line = rawLine.trimEnd()
        
        // 处理代码块
        if (line.startsWith("```")) {
            if (inCodeBlock) {
                htmlBuilder.append("</code></pre>\n")
                inCodeBlock = false
            } else {
                htmlBuilder.append("<pre><code>")
                inCodeBlock = true
            }
            continue
        }
        
        if (inCodeBlock) {
            val escaped = line
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            htmlBuilder.append(escaped).append("\n")
            continue
        }
        
        // 处理列表
        if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ")) {
            if (!inList) {
                htmlBuilder.append("<ul>\n")
                inList = true
            }
            val content = line.substring(line.indexOf("- ") + 2)
            htmlBuilder.append("<li>").append(parseInlineMarkdown(content)).append("</li>\n")
            continue
        } else {
            if (inList) {
                htmlBuilder.append("</ul>\n")
                inList = false
            }
        }
        
        // 处理各种标题与块
        if (line.startsWith("# ")) {
            htmlBuilder.append("<h1>").append(parseInlineMarkdown(line.substring(2))).append("</h1>\n")
        } else if (line.startsWith("## ")) {
            htmlBuilder.append("<h2>").append(parseInlineMarkdown(line.substring(3))).append("</h2>\n")
        } else if (line.startsWith("### ")) {
            htmlBuilder.append("<h3>").append(parseInlineMarkdown(line.substring(4))).append("</h3>\n")
        } else if (line.startsWith("#### ")) {
            htmlBuilder.append("<h4>").append(parseInlineMarkdown(line.substring(5))).append("</h4>\n")
        } else if (line.startsWith("> ")) {
            htmlBuilder.append("<blockquote>").append(parseInlineMarkdown(line.substring(2))).append("</blockquote>\n")
        } else if (line.startsWith("---")) {
            htmlBuilder.append("<hr/>\n")
        } else {
            if (line.isBlank()) {
                htmlBuilder.append("<br/>\n")
            } else {
                htmlBuilder.append("<p>").append(parseInlineMarkdown(line)).append("</p>\n")
            }
        }
    }
    
    if (inList) {
        htmlBuilder.append("</ul>\n")
    }
    if (inCodeBlock) {
        htmlBuilder.append("</code></pre>\n")
    }
    
    htmlBuilder.append("</body>\n</html>")
    return htmlBuilder.toString()
}

private fun parseInlineMarkdown(text: String): String {
    var result = text
    result = result.replace(Regex("\\*\\*(.*?)\\*\\*"), "<strong>$1</strong>")
    result = result.replace(Regex("\\*(.*?)\\*"), "<em>$1</em>")
    result = result.replace(Regex("`(.*?)`"), "<code>$1</code>")
    result = result.replace(Regex("\\[(.*?)\\]\\((.*?)\\)"), "<a href=\"$2\" target=\"_blank\">$1</a>")
    return result
}

// 预设 Markdown 手册内容
private val INITIAL_MARKDOWN = """
# 🚀 KBWebView API 演示与测试手册

这是使用 **KBWebView** 渲染的高画质 Markdown 页面。本项目是基于 Kotlin Multiplatform 和 Compose 的跨平台现代化浏览器引擎。

---

## 🛠️ 主要核心 API 功能

我们已在本实验室中完整暴露出以下底层核心 API：

- **页面加载**：`loadUrl(url)` 与 `loadHtml(html)` 
- **基础导航**：`goBack()`, `goForward()`, `reload()`, `stopLoading()`
- **脚本执行**：`evaluateJavascript(script)` 支持双向返回值
- **高阶回调**：`registerJsCallback(name, callback)` 允许 JS 直接向 Native 发送消息
- **无痕清理**：`clearCacheAndCookies()` 清除数据
- **原生手势模拟**：
  - `clickByCoordinates(x, y)` 
  - `hoverByCoordinates(x, y)` 
  - `scrollByCoordinates(x, y, dx, dy)` 
  - `dragByCoordinates(startX, startY, endX, endY)`

---

## 💻 编写代码体验

你可以随时在左侧的编辑框里修改这段 Markdown 文本。WebView 会在右侧**秒级实时同步**渲染你的改动：

```kotlin
// 我们是这样实现 Markdown 实时更新渲染的：
fun onMarkdownChanged(markdown: String) {
    val html = markdownToHtml(markdown)
    webView.loadHtml(html)
}
```

---

> [!NOTE]
> KBWebView 完美支持与 Native 的双向通信。你可以点击顶部 Tab 切换到 **“网页模式”**，里面预置了专门的 JS 交互测试网页，支持在页面里捕获鼠标实时坐标并同步到控制台！
""".trimIndent()

// 预设本地交互测试 HTML 模板
private val LOCAL_HTML_TEMPLATE = """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>KBWebView API 交互测试</title>
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: linear-gradient(135deg, #1e1e24 0%, #121214 100%);
      color: #e0e0e0;
      padding: 24px;
      margin: 0;
      user-select: none;
    }
    .card {
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 12px;
      padding: 16px;
      margin-bottom: 16px;
      box-shadow: 0 4px 12px rgba(0,0,0,0.25);
    }
    h1 { color: #64B5F6; font-size: 20px; margin-top: 0; border-bottom: 1px solid rgba(255,255,255,0.1); padding-bottom: 8px; }
    h3 { margin-top: 0; color: #81C784; }
    p { font-size: 13px; color: #b0b0b8; }
    button {
      background: #64B5F6;
      color: #121214;
      border: none;
      padding: 10px 20px;
      border-radius: 6px;
      font-weight: bold;
      cursor: pointer;
      font-size: 13px;
      transition: all 0.2s;
    }
    button:hover { background: #90caf9; transform: translateY(-1px); }
    button:active { transform: translateY(1px); }
    .coord-box {
      font-family: monospace;
      color: #ffb74d;
      background: rgba(0,0,0,0.3);
      padding: 8px 12px;
      border-radius: 6px;
      display: inline-block;
      margin-top: 8px;
    }
  </style>
</head>
<body>
  <div class="card">
    <h1>KBWebView API 双向交互测试</h1>
    <p>这是一个内置的 HTML 测试页，用来演示 Native 与 Web 之间的 JS 回调和手势模拟交互。</p>
  </div>
  
  <div class="card">
    <h3>1. JS 回调函数 (registerJsCallback)</h3>
    <p>点击下方按钮，将调用 Native 注册的 <code>onCustomAction</code> 回调，并发送一个带有时间戳的消息：</p>
    <button onclick="if(window.onCustomAction) { window.onCustomAction('Hello from WebView JS! Time: ' + new Date().toLocaleTimeString()); } else { alert('Native 回调未绑定'); }">
      触发 Native 回调
    </button>
  </div>
  
  <div class="card">
    <h3>2. 鼠标实时坐标捕获</h3>
    <p>在该页面内移动鼠标，系统会通过 <code>onMouseMove</code> JS 回调实时将坐标发送给 Native 侧：</p>
    <div class="coord-box" id="coords">X: 0, Y: 0</div>
  </div>

  <script>
    document.addEventListener('mousemove', function(e) {
      var rect = document.body.getBoundingClientRect();
      var x = Math.round(e.clientX - rect.left);
      var y = Math.round(e.clientY - rect.top);
      document.getElementById('coords').innerText = 'X: ' + x + ', Y: ' + y;
      if (window.onMouseMove) {
        window.onMouseMove(x + ',' + y);
      }
    });
  </script>
</body>
</html>
""".trimIndent()
