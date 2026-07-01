package xyz.kbrowser.webviewdemo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.kbrowser.makeImageBitmap
import xyz.kbrowser.webview.*

@Composable
fun DemoScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF16161A),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp)
                ) {
                    Text("←", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F0F12)),
            content = content
        )
    }
}

@Composable
private fun DemoSection(
    title: String,
    color: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A1E))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
        content()
    }
}

@Composable
private fun DemoButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textColor: Color = Color.White,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            disabledContainerColor = Color(0xFF2E2E36)
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) textColor else Color(0xFF666666))
    }
}

@Composable
private fun StateRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF888894))
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF81C784),
            maxLines = 1
        )
    }
}

@Composable
private fun LogArea(
    logs: List<String>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F0F12))
            .border(1.dp, Color(0xFF2E2E36), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "系统日志",
                color = Color(0xFF888894),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            TextButton(
                onClick = onClear,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("清空", color = Color(0xFF64B5F6), fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
            items(logs.reversed()) { entry ->
                val logColor = when {
                    entry.contains("错误") || entry.contains("失败") || entry.contains("Error") || entry.contains("拒绝") -> Color(0xFFEF5350)
                    entry.contains("→") || entry.contains("收到") || entry.contains("成功") || entry.contains("完成") || entry.contains("授权") -> Color(0xFF81C784)
                    else -> Color(0xFFE0E0E0)
                }
                Text(
                    text = entry,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = logColor,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun BasicBrowsingDemo(onBack: () -> Unit) {
    val webView = rememberKBWebView(initialUrl = "https://www.bing.com")
    val currentUrl by webView.currentUrl.collectAsState()
    val currentTitle by webView.currentTitle.collectAsState()
    val loadingState by webView.loadingState.collectAsState()
    val progress by webView.progress.collectAsState()
    val canGoBack by webView.canGoBack.collectAsState()
    val canGoForward by webView.canGoForward.collectAsState()

    var urlInput by remember { mutableStateOf("https://www.bing.com") }
    var logs by remember { mutableStateOf(listOf<String>()) }

    fun log(msg: String) { logs = logs + msg }

    DemoScaffold(title = "基础浏览", onBack = onBack) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1E))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("输入 URL...", fontSize = 10.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF64B5F6),
                    unfocusedBorderColor = Color(0xFF2E2E36)
                ),
                textStyle = TextStyle(fontSize = 11.sp, color = Color.White)
            )
            Spacer(modifier = Modifier.width(6.dp))
            DemoButton("加载", Color(0xFF1565C0)) {
                webView.loadUrl(urlInput)
                log("loadUrl(\"$urlInput\")")
            }
        }

        if (loadingState is LoadingState.Loading) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Color(0xFF64B5F6),
                trackColor = Color.Transparent
            )
        } else {
            Spacer(modifier = Modifier.height(2.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF0F0F12))
        ) {
            KBWebView(webView = webView, modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF16161A))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DemoSection(title = "导航控制", color = Color(0xFF64B5F6)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DemoButton("← goBack", if (canGoBack) Color(0xFF1565C0) else Color(0xFF2E2E36), Modifier.weight(1f), canGoBack) {
                        webView.goBack()
                        log("goBack()")
                    }
                    DemoButton("goForward →", if (canGoForward) Color(0xFF1565C0) else Color(0xFF2E2E36), Modifier.weight(1f), canGoForward) {
                        webView.goForward()
                        log("goForward()")
                    }
                    DemoButton("reload", Color(0xFF4A4A55), Modifier.weight(1f)) {
                        webView.reload()
                        log("reload()")
                    }
                    DemoButton("stop", Color(0xFF4A4A55), Modifier.weight(1f)) {
                        webView.stopLoading()
                        log("stopLoading()")
                    }
                }
            }

            DemoSection(title = "实时状态 (StateFlow)", color = Color(0xFF81C784)) {
                StateRow("currentUrl", currentUrl ?: "null")
                StateRow("currentTitle", currentTitle ?: "null")
                StateRow("loadingState", loadingState::class.simpleName ?: "—")
                StateRow("progress", "${(progress * 100).toInt()}%")
                StateRow("canGoBack", canGoBack.toString())
                StateRow("canGoForward", canGoForward.toString())
            }

            LogArea(logs = logs, onClear = { logs = emptyList() })
        }
    }
}

private fun markdownToHtml(md: String): String {
    var html = md
        .replace(Regex("""^### (.+)$""", RegexOption.MULTILINE), "<h3>$1</h3>")
        .replace(Regex("""^## (.+)$""", RegexOption.MULTILINE), "<h2>$1</h2>")
        .replace(Regex("""^# (.+)$""", RegexOption.MULTILINE), "<h1>$1</h1>")
        .replace(Regex("""\*\*(.+?)\*\*"""), "<strong>$1</strong>")
        .replace(Regex("""\*(.+?)\*"""), "<em>$1</em>")
        .replace(Regex("""`(.+?)`"""), "<code>$1</code>")
        .replace(Regex("""\[(.+?)]\((.+?)\)"""), """<a href="$2">$1</a>""")
        .replace(Regex("""^- (.+)$""", RegexOption.MULTILINE), "<li>$1</li>")
    html = html.replace(Regex("""(<li>(\s|\S)*?</li>)+""")) { match ->
        "<ul>${match.value}</ul>"
    }
    html = html.replace(Regex("""\n{2,}"""), "</p><p>")
    html = html.replace("\n", "<br>")
    return "<p>$html</p>"
}

private val DEFAULT_MARKDOWN = """# KBWebView

**KBWebView** 是 Compose Multiplatform 生态的 *WebView 组件*。

## 功能

- loadUrl / loadHtml 加载内容
- evaluateJavascript 双向通信
- registerJsCallback / registerJsHandler
- `onNewWindowRequest` 拦截新窗口
- [了解更多](https://github.com/kbrowser)

### 代码示例

`webView.loadHtml(html)` 可直接渲染 HTML 字符串。"""

@Composable
fun HtmlContentDemo(onBack: () -> Unit) {
    val webView = rememberKBWebView(initialUrl = null)
    var markdownInput by remember { mutableStateOf(DEFAULT_MARKDOWN) }

    LaunchedEffect(markdownInput) {
        val html = markdownToHtml(markdownInput)
        val fullHtml = """
        <!DOCTYPE html>
        <html><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #121214; color: #e0e0e0; padding: 16px; margin: 0; }
          h1 { color: #64B5F6; } h2 { color: #81C784; } h3 { color: #FFB74D; }
          a { color: #80DEEA; }
          code { background: #1a1a1e; padding: 2px 6px; border-radius: 4px; font-size: 13px; color: #CE93D8; }
          ul { padding-left: 20px; } li { margin: 4px 0; }
        </style>
        </head><body>$html</body></html>
        """.trimIndent()
        webView.loadHtml(fullHtml)
    }

    DemoScaffold(title = "HTML 内容渲染", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .background(Color(0xFF1A1A1E))
                .border(1.dp, Color(0xFF2E2E36))
                .padding(12.dp)
        ) {
            Text(
                "Markdown 编辑器 (实时同步)",
                color = Color(0xFF888894),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            OutlinedTextField(
                value = markdownInput,
                onValueChange = { markdownInput = it },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF81C784),
                    unfocusedBorderColor = Color(0xFF2E2E36)
                ),
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp, color = Color.White)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .border(1.dp, Color(0xFF2E2E36))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                KBWebView(webView = webView, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private val JS_COMM_HTML = """
<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>JS 通信演示</title>
<style>
  body { font-family: -apple-system, sans-serif; background: #121214; color: #e0e0e0; padding: 16px; margin: 0; }
  h2 { color: #FFB74D; font-size: 16px; margin-bottom: 4px; }
  p { font-size: 12px; color: #9e9ea8; margin: 4px 0 8px; }
  button { background: #FFB74D; color: #121214; border: none; padding: 10px 16px; border-radius: 8px; font-weight: bold; font-size: 13px; cursor: pointer; width: 100%; margin-bottom: 8px; }
  .card { background: #1a1a1e; border: 1px solid #2e2e36; border-radius: 10px; padding: 12px; margin-bottom: 12px; }
  #result { background: #0f0f12; border: 1px solid #2e2e36; border-radius: 6px; padding: 8px; font-family: monospace; font-size: 12px; color: #81C784; min-height: 40px; word-break: break-all; }
</style>
</head><body>
  <div class="card">
    <h2>registerJsCallback 演示</h2>
    <p>点击按钮调用 window.onNativeMessage() 向 Native 发送消息</p>
    <button onclick="if(window.onNativeMessage){window.onNativeMessage('Hello from WebView! ' + new Date().toLocaleTimeString());}else{document.getElementById('result').textContent='回调未注册';}">触发 onNativeMessage</button>
  </div>
  <div class="card">
    <h2>registerJsHandler 演示</h2>
    <p>点击按钮 await window.getConfig() 获取 Native 返回的配置</p>
    <button onclick="(async()=>{if(window.getConfig){const r=await window.getConfig('{\"key\":\"theme\"}');document.getElementById('result').textContent='Handler 返回: '+r;}else{document.getElementById('result').textContent='Handler 未注册';}})()">触发 getConfig</button>
  </div>
  <div class="card">
    <h2>结果</h2>
    <div id="result">等待操作...</div>
  </div>
</body></html>
""".trimIndent()

@Composable
fun JsCommunicationDemo(onBack: () -> Unit) {
    val webView = rememberKBWebView(initialUrl = null)
    var callbackEnabled by remember { mutableStateOf(false) }
    var callbackMessage by remember { mutableStateOf("（尚未收到回调）") }
    var handlerEnabled by remember { mutableStateOf(false) }
    var handlerLog by remember { mutableStateOf("（尚未收到请求）") }
    var jsInput by remember { mutableStateOf("document.title") }
    var jsResult by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(listOf<String>()) }

    fun log(msg: String) { logs = logs + msg }

    LaunchedEffect(Unit) {
        webView.loadHtml(JS_COMM_HTML)
        log("已加载 JS 通信演示页")
    }

    LaunchedEffect(callbackEnabled) {
        if (callbackEnabled) {
            webView.registerJsCallback("onNativeMessage") { data ->
                callbackMessage = data
                log("收到 JS 回调: $data")
            }
            log("已注册 registerJsCallback('onNativeMessage')")
        } else {
            webView.unregisterJsCallback("onNativeMessage")
            log("已注销 registerJsCallback('onNativeMessage')")
        }
    }

    LaunchedEffect(handlerEnabled) {
        if (handlerEnabled) {
            webView.registerJsHandler("getConfig") { request ->
                handlerLog = "请求: $request → 返回: {\"theme\":\"dark\",\"version\":\"1.0\"}"
                log("Handler 收到请求: $request")
                """{"theme":"dark","version":"1.0"}"""
            }
            log("已注册 registerJsHandler('getConfig')")
        } else {
            webView.unregisterJsHandler("getConfig")
            log("已注销 registerJsHandler('getConfig')")
        }
    }

    DemoScaffold(title = "JS 双向通信", onBack = onBack) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .background(Color(0xFF0F0F12))
        ) {
            KBWebView(webView = webView, modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .background(Color(0xFF16161A))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DemoSection(title = "registerJsCallback", color = Color(0xFFCE93D8)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = callbackEnabled,
                            onCheckedChange = { callbackEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFCE93D8))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (callbackEnabled) "已注册 'onNativeMessage'" else "未注册",
                            fontSize = 11.sp,
                            color = if (callbackEnabled) Color(0xFFCE93D8) else Color(0xFF888894)
                        )
                    }
                }
                if (callbackEnabled) {
                    Text(
                        text = "最新消息: $callbackMessage",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFCE93D8),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E24))
                            .padding(8.dp)
                    )
                }
            }

            DemoSection(title = "registerJsHandler", color = Color(0xFFFFB74D)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = handlerEnabled,
                            onCheckedChange = { handlerEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFFB74D))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (handlerEnabled) "已注册 'getConfig'" else "未注册",
                            fontSize = 11.sp,
                            color = if (handlerEnabled) Color(0xFFFFB74D) else Color(0xFF888894)
                        )
                    }
                }
                if (handlerEnabled) {
                    Text(
                        text = handlerLog,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFFB74D),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E24))
                            .padding(8.dp)
                    )
                }
            }

            DemoSection(title = "evaluateJavascript", color = Color(0xFF64B5F6)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = jsInput,
                        onValueChange = { jsInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("JS 表达式...", fontSize = 10.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF64B5F6),
                            unfocusedBorderColor = Color(0xFF2E2E36)
                        ),
                        textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    DemoButton("执行", Color(0xFF1565C0)) {
                        log("evaluateJavascript(\"$jsInput\")")
                        webView.evaluateJavascript(jsInput) { result ->
                            jsResult = result
                            log("  → 返回值: $result")
                        }
                    }
                }
                if (jsResult.isNotBlank()) {
                    Text(
                        text = "返回值: $jsResult",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF64B5F6),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E24))
                            .padding(8.dp)
                    )
                }
            }

            LogArea(logs = logs, onClear = { logs = emptyList() })
        }
    }
}

private val NEW_WINDOW_FILE_HTML = """
<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>新窗口与文件处理</title>
<style>
  body { font-family: -apple-system, sans-serif; background: #121214; color: #e0e0e0; padding: 16px; margin: 0; }
  h2 { color: #80DEEA; font-size: 16px; margin-bottom: 4px; }
  p { font-size: 12px; color: #9e9ea8; margin: 4px 0 8px; }
  .card { background: #1a1a1e; border: 1px solid #2e2e36; border-radius: 10px; padding: 12px; margin-bottom: 12px; }
  a { color: #80DEEA; font-size: 14px; }
  input[type="file"] { margin-top: 8px; color: #e0e0e0; }
</style>
</head><body>
  <div class="card">
    <h2>onNewWindowRequest 演示</h2>
    <p>点击下方链接，触发 target="_blank" 新窗口请求</p>
    <a href="https://github.com" target="_blank">打开外部链接（target="_blank"）</a>
  </div>
  <div class="card">
    <h2>onFileDialogRequest 演示</h2>
    <p>点击下方文件选择框，触发文件对话框请求</p>
    <input type="file" accept=".jpg,.png,.gif" style="width:100%;">
  </div>
</body></html>
""".trimIndent()

@Composable
fun NewWindowAndFileDemo(onBack: () -> Unit) {
    val webView = rememberKBWebView(initialUrl = null)
    var newWindowUrl by remember { mutableStateOf("") }
    var fileDialogInfo by remember { mutableStateOf("") }
    var pendingFileCallback by remember { mutableStateOf<KBFileDialogCallback?>(null) }
    var pendingFileRequest by remember { mutableStateOf<KBFileDialogRequest?>(null) }
    var simulatedFilePath by remember { mutableStateOf("/path/to/photo.jpg") }
    var logs by remember { mutableStateOf(listOf<String>()) }

    fun log(msg: String) { logs = logs + msg }

    LaunchedEffect(Unit) {
        webView.loadHtml(NEW_WINDOW_FILE_HTML)
        log("已加载新窗口与文件处理演示页")
    }

    DisposableEffect(webView) {
        webView.onNewWindowRequest = { url ->
            newWindowUrl = url
            log("拦截到新窗口请求: $url")
        }
        webView.onFileDialogRequest = { request, callback ->
            pendingFileRequest = request
            pendingFileCallback = callback
            fileDialogInfo = "mode=${request.mode}, filters=${request.acceptFilters}, title=${request.title}"
            log("文件对话框请求: mode=${request.mode}, filters=${request.acceptFilters}")
        }
        onDispose {
            webView.onNewWindowRequest = null
            webView.onFileDialogRequest = null
        }
    }

    DemoScaffold(title = "新窗口与文件处理", onBack = onBack) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .background(Color(0xFF0F0F12))
        ) {
            KBWebView(webView = webView, modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .background(Color(0xFF16161A))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DemoSection(title = "onNewWindowRequest", color = Color(0xFF80DEEA)) {
                Text(
                    text = if (newWindowUrl.isNotBlank()) "拦截到: $newWindowUrl" else "尚未拦截到新窗口请求",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (newWindowUrl.isNotBlank()) Color(0xFF80DEEA) else Color(0xFF888894),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1E1E24))
                        .padding(8.dp)
                )
                DemoButton("触发 window.open 测试", Color(0xFF00838F), Modifier.fillMaxWidth()) {
                    webView.evaluateJavascript("window.open('https://github.com/kbrowser', '_blank')", null)
                    log("触发 window.open() 测试")
                }
            }

            DemoSection(title = "onFileDialogRequest", color = Color(0xFF80DEEA)) {
                Text(
                    text = if (fileDialogInfo.isNotBlank()) fileDialogInfo else "尚未收到文件对话框请求",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (fileDialogInfo.isNotBlank()) Color(0xFF80DEEA) else Color(0xFF888894),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1E1E24))
                        .padding(8.dp)
                )
                if (pendingFileCallback != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = simulatedFilePath,
                            onValueChange = { simulatedFilePath = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("模拟文件路径", fontSize = 10.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF80DEEA),
                                unfocusedBorderColor = Color(0xFF2E2E36)
                            ),
                            textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DemoButton("选择文件", Color(0xFF00838F), Modifier.weight(1f)) {
                            pendingFileCallback?.selectFiles(listOf(simulatedFilePath))
                            log("已选择文件: $simulatedFilePath")
                            pendingFileCallback = null
                            pendingFileRequest = null
                        }
                        DemoButton("取消", Color(0xFF4A4A55), Modifier.weight(1f)) {
                            pendingFileCallback?.cancel()
                            log("已取消文件选择")
                            pendingFileCallback = null
                            pendingFileRequest = null
                        }
                    }
                }
            }

            LogArea(logs = logs, onClear = { logs = emptyList() })
        }
    }
}

private val LIFECYCLE_HTML = """
<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>生命周期回调</title>
<style>
  body { font-family: -apple-system, sans-serif; background: #121214; color: #e0e0e0; padding: 16px; margin: 0; }
  h2 { color: #CE93D8; font-size: 16px; margin-bottom: 4px; }
  p { font-size: 12px; color: #9e9ea8; margin: 4px 0 8px; }
  button { background: #CE93D8; color: #121214; border: none; padding: 10px 16px; border-radius: 8px; font-weight: bold; font-size: 13px; cursor: pointer; width: 100%; margin-bottom: 8px; }
  .card { background: #1a1a1e; border: 1px solid #2e2e36; border-radius: 10px; padding: 12px; margin-bottom: 12px; }
</style>
</head><body>
  <div class="card">
    <h2>alert() 演示</h2>
    <p>触发 onJsAlert 回调</p>
    <button onclick="document.getElementById('alertStatus').textContent='调用前→';alert('这是一条 Alert 消息!');document.getElementById('alertStatus').textContent+='调用后';">触发 alert()</button>
    <div id="alertStatus" style="font-size:12px;color:#FFB74D;margin-top:4px;"></div>
  </div>
  <div class="card">
    <h2>confirm() 演示</h2>
    <p>触发 onJsConfirm 回调</p>
    <button onclick="document.getElementById('confirmStatus').textContent='调用前→';var r=confirm('确认执行此操作?');document.getElementById('confirmStatus').textContent+='调用后';document.getElementById('confirmResult').textContent='用户选择: '+(r?'确认':'取消');">触发 confirm()</button>
    <div id="confirmResult" style="font-size:12px;color:#81C784;margin-top:4px;"></div>
    <div id="confirmStatus" style="font-size:12px;color:#FFB74D;margin-top:2px;"></div>
  </div>
  <div class="card">
    <h2>prompt() 演示</h2>
    <p>触发 onJsPrompt 回调</p>
    <button onclick="document.getElementById('promptStatus').textContent='调用前→';var r=prompt('请输入你的名字:','默认值');document.getElementById('promptStatus').textContent+='调用后';if(r)document.getElementById('promptResult').textContent='输入: '+r;else document.getElementById('promptResult').textContent='返回 null';">触发 prompt()</button>
    <div id="promptResult" style="font-size:12px;color:#81C784;margin-top:4px;"></div>
    <div id="promptStatus" style="font-size:12px;color:#FFB74D;margin-top:2px;"></div>
  </div>
  <div class="card">
    <h2>权限请求演示</h2>
    <p>触发 onPermissionRequest 回调（getUserMedia）</p>
    <button onclick="navigator.mediaDevices.getUserMedia({video:true,audio:true}).then(s=>{document.getElementById('permResult').textContent='已授权: '+s.getTracks().map(t=>t.kind).join(',');s.getTracks().forEach(t=>t.stop());}).catch(e=>document.getElementById('permResult').textContent='被拒绝: '+(e.message||e));">请求摄像头+麦克风</button>
    <div id="permResult" style="font-size:12px;color:#80DEEA;margin-top:4px;"></div>
  </div>
  <div class="card">
    <h2>错误回调演示</h2>
    <p>触发 onReceivedError 回调（加载不存在的 URL）</p>
    <button onclick="location.href='https://this-domain-does-not-exist-12345.com';">加载错误 URL</button>
  </div>
</body></html>
""".trimIndent()

@Composable
fun LifecycleCallbacksDemo(onBack: () -> Unit) {
    val webView = rememberKBWebView(initialUrl = null)
    var logs by remember { mutableStateOf(listOf<String>()) }
    var pendingAlert by remember { mutableStateOf<Pair<String, JsResultCallback>?>(null) }
    var pendingConfirm by remember { mutableStateOf<Pair<String, JsResultCallback>?>(null) }
    var pendingPrompt by remember { mutableStateOf<Triple<String, String?, JsPromptResultCallback>?>(null) }
    var pendingPermission by remember { mutableStateOf<PermissionRequest?>(null) }
    var promptInput by remember { mutableStateOf("") }

    fun log(msg: String) { logs = logs + msg }

    LaunchedEffect(Unit) {
        webView.loadHtml(LIFECYCLE_HTML)
        log("已加载生命周期回调演示页")
    }

    DisposableEffect(webView) {
        webView.setWebViewClient(object : KBWebViewClient {
            override fun onPageStarted(url: String) {
                log("onPageStarted: $url")
            }
            override fun onPageFinished(url: String) {
                log("onPageFinished: $url")
            }
            override fun onReceivedError(error: Diagnostics) {
                log("onReceivedError: ${error.errorCode} - ${error.description}")
            }
        })
        webView.setWebChromeClient(object : KBWebChromeClient {
            override fun onJsAlert(url: String, message: String, callback: JsResultCallback) {
                log("onJsAlert: $message")
                pendingAlert = message to callback
            }
            override fun onJsConfirm(url: String, message: String, callback: JsResultCallback) {
                log("onJsConfirm: $message")
                pendingConfirm = message to callback
            }
            override fun onJsPrompt(url: String, message: String, defaultValue: String?, callback: JsPromptResultCallback) {
                log("onJsPrompt: $message (default: $defaultValue)")
                promptInput = defaultValue ?: ""
                pendingPrompt = Triple(message, defaultValue, callback)
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                log("onPermissionRequest: ${request.origin} → ${request.resources}")
                pendingPermission = request
            }
        })
        onDispose {
            webView.setWebViewClient(null)
            webView.setWebChromeClient(null)
        }
    }

    DemoScaffold(title = "生命周期回调", onBack = onBack) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
                .background(Color(0xFF0F0F12))
        ) {
            KBWebView(webView = webView, modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f)
                .background(Color(0xFF16161A))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            pendingAlert?.let { (message, callback) ->
                DemoSection(title = "onJsAlert", color = Color(0xFFEF5350)) {
                    Text(
                        text = "消息: $message",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E24))
                            .padding(8.dp)
                    )
                    DemoButton("确认", Color(0xFF2E7D32), Modifier.fillMaxWidth()) {
                        callback.confirm()
                        pendingAlert = null
                        log("alert → 确认")
                    }
                }
            }

            pendingConfirm?.let { (message, callback) ->
                DemoSection(title = "onJsConfirm", color = Color(0xFFCE93D8)) {
                    Text(
                        text = "消息: $message",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E24))
                            .padding(8.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DemoButton("确认", Color(0xFF2E7D32), Modifier.weight(1f)) {
                            callback.confirm()
                            pendingConfirm = null
                            log("confirm → 确认")
                        }
                        DemoButton("取消", Color(0xFFC62828), Modifier.weight(1f)) {
                            callback.cancel()
                            pendingConfirm = null
                            log("confirm → 取消")
                        }
                    }
                }
            }

            pendingPrompt?.let { (message, _, callback) ->
                DemoSection(title = "onJsPrompt", color = Color(0xFFFFB74D)) {
                    Text(
                        text = "消息: $message",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E24))
                            .padding(8.dp)
                    )
                    OutlinedTextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("输入响应值...", fontSize = 10.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFFB74D),
                            unfocusedBorderColor = Color(0xFF2E2E36)
                        ),
                        textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DemoButton("确认", Color(0xFF2E7D32), Modifier.weight(1f)) {
                            callback.confirm(promptInput)
                            pendingPrompt = null
                            log("prompt → 确认: $promptInput")
                        }
                        DemoButton("取消", Color(0xFFC62828), Modifier.weight(1f)) {
                            callback.cancel()
                            pendingPrompt = null
                            log("prompt → 取消")
                        }
                    }
                }
            }

            pendingPermission?.let { request ->
                DemoSection(title = "onPermissionRequest", color = Color(0xFF80DEEA)) {
                    Text(
                        text = "来源: ${request.origin}\n资源: ${request.resources.joinToString()}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E24))
                            .padding(8.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DemoButton("授权", Color(0xFF2E7D32), Modifier.weight(1f)) {
                            request.grant()
                            pendingPermission = null
                            log("permission → 授权")
                        }
                        DemoButton("拒绝", Color(0xFFC62828), Modifier.weight(1f)) {
                            request.deny()
                            pendingPermission = null
                            log("permission → 拒绝")
                        }
                    }
                }
            }

            DemoSection(title = "生命周期日志", color = Color(0xFF888894)) {
                LogArea(logs = logs, onClear = { logs = emptyList() })
            }
        }
    }
}

@Composable
fun CacheManagementDemo(onBack: () -> Unit) {
    val webView = rememberKBWebView(initialUrl = null)
    var urlInput by remember { mutableStateOf("https://www.bing.com") }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var cookieCheckResult by remember { mutableStateOf("") }

    val currentUrl by webView.currentUrl.collectAsState()
    val currentTitle by webView.currentTitle.collectAsState()
    val loadingState by webView.loadingState.collectAsState()

    fun log(msg: String) { logs = logs + msg }

    LaunchedEffect(Unit) {
        log("KBWebView 已就绪，可加载网站后测试缓存清理")
    }

    DemoScaffold(title = "缓存管理", onBack = onBack) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1E))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("输入 URL...", fontSize = 10.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFEF9A9A),
                    unfocusedBorderColor = Color(0xFF2E2E36)
                ),
                textStyle = TextStyle(fontSize = 11.sp, color = Color.White)
            )
            Spacer(modifier = Modifier.width(6.dp))
            DemoButton("加载", Color(0xFF1565C0)) {
                webView.loadUrl(urlInput)
                log("loadUrl(\"$urlInput\")")
            }
        }

        if (loadingState is LoadingState.Loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Color(0xFFEF9A9A),
                trackColor = Color.Transparent
            )
        } else {
            Spacer(modifier = Modifier.height(2.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .background(Color(0xFF0F0F12))
        ) {
            KBWebView(webView = webView, modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .background(Color(0xFF16161A))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DemoSection(title = "当前状态", color = Color(0xFF888894)) {
                StateRow("currentUrl", currentUrl ?: "null")
                StateRow("currentTitle", currentTitle ?: "null")
                StateRow("loadingState", loadingState::class.simpleName ?: "—")
            }

            DemoSection(title = "clearCacheAndCookies", color = Color(0xFFEF9A9A)) {
                Text(
                    text = "清除当前 WebView 的所有缓存与 Cookie，常用于退出登录或无痕模式切换。",
                    fontSize = 11.sp,
                    color = Color(0xFF9E9EA8),
                    lineHeight = 15.sp
                )
                DemoButton("clearCacheAndCookies()", Color(0xFFC62828), Modifier.fillMaxWidth()) {
                    webView.clearCacheAndCookies()
                    log("clearCacheAndCookies() — 缓存与 Cookie 已清除")
                }
            }

            DemoSection(title = "Cookie 验证", color = Color(0xFFEF9A9A)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DemoButton("检查 Cookie", Color(0xFF00838F), Modifier.weight(1f)) {
                        webView.evaluateJavascript("document.cookie") { result ->
                            cookieCheckResult = result
                            log("document.cookie → $result")
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    DemoButton("设置测试 Cookie", Color(0xFF2E7D32), Modifier.weight(1f)) {
                        webView.evaluateJavascript("document.cookie='kb_test=demo; path=/'; document.cookie") { result ->
                            cookieCheckResult = result
                            log("设置 kb_test Cookie → $result")
                        }
                    }
                }
                if (cookieCheckResult.isNotBlank()) {
                    Text(
                        text = "Cookie: $cookieCheckResult",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFEF9A9A),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E24))
                            .padding(8.dp)
                    )
                }
            }

            LogArea(logs = logs, onClear = { logs = emptyList() })
        }
    }
}

@Composable
fun ScreenshotDemo(onBack: () -> Unit) {
    val webView = rememberKBWebView(initialUrl = "https://www.bing.com")
    val scope = rememberCoroutineScope()

    val currentUrl by webView.currentUrl.collectAsState()
    val loadingState by webView.loadingState.collectAsState()

    var screenshot by remember { mutableStateOf<xyz.kbrowser.webview.KBScreenshot?>(null) }
    var imageBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var logs by remember { mutableStateOf(listOf<String>()) }

    fun log(msg: String) { logs = logs + msg }

    DemoScaffold(title = "网页截图", onBack = onBack) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1E))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DemoButton("takeScreenshot()", Color(0xFF80DEEA), Modifier.weight(1f)) {
                scope.launch {
                    val result = webView.takeScreenshot()
                    if (result != null) {
                        screenshot = result
                        imageBitmap = makeImageBitmap(result.imageData)
                        log("takeScreenshot() → ${result.width}x${result.height}, ${result.imageData.size} bytes")
                    } else {
                        log("takeScreenshot() → null (可能页面未加载完成)")
                    }
                }
            }
            DemoButton("reload", Color(0xFF4A4A55), Modifier.weight(1f)) {
                webView.reload()
                log("reload()")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .background(Color(0xFF0F0F12))
        ) {
            KBWebView(webView = webView, modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .background(Color(0xFF16161A))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DemoSection(title = "截图结果", color = Color(0xFF80DEEA)) {
                val bmp = imageBitmap
                val ss = screenshot
                if (bmp != null && ss != null) {
                    Text(
                        text = "${ss.width}x${ss.height} • ${ss.imageData.size} bytes (PNG)",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF81C784)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Image(
                        bitmap = bmp,
                        contentDescription = "screenshot",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF2E2E36), RoundedCornerShape(6.dp))
                    )
                } else {
                    Text(
                        text = "点击 takeScreenshot() 按钮，对当前页面截图",
                        fontSize = 11.sp,
                        color = Color(0xFF888894)
                    )
                }
            }

            DemoSection(title = "当前状态", color = Color(0xFF888894)) {
                StateRow("currentUrl", currentUrl ?: "null")
                StateRow("loadingState", loadingState::class.simpleName ?: "—")
            }

            LogArea(logs = logs, onClear = { logs = emptyList() })
        }
    }
}
