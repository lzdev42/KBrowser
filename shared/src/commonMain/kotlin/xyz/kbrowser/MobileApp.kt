package xyz.kbrowser

import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.kbrowser.webview.KBWebView
import xyz.kbrowser.webview.LoadingState
import xyz.kbrowser.webview.rememberKBWebView

// ==================== 入口 ====================

/**
 * 移动端演示应用程序入口。
 * 针对 Android 和 iOS 进行了竖屏及小屏幕适配。
 */
@Composable
fun MobileApp() {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF64B5F6),
            secondary = Color(0xFF81C784),
            background = Color(0xFF0F0F12),
            surface = Color(0xFF1A1A1E)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1E1E24), Color(0xFF0C0C0E))
                    )
                )
        ) {
            when (currentScreen) {
                AppScreen.Home -> MobileHomeScreen(
                    onNavigateToBrowser = { currentScreen = AppScreen.BrowserExample },
                    onNavigateToWebView = { currentScreen = AppScreen.WebViewExample }
                )
                AppScreen.BrowserExample -> {
                    val viewModel = remember { BrowserViewModel() }
                    DisposableEffect(Unit) {
                        onDispose { viewModel.state.value.page?.close() }
                    }
                    MobileBrowserExampleScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = AppScreen.Home }
                    )
                }
                AppScreen.WebViewExample -> {
                    MobileWebViewExampleScreen(
                        onBack = { currentScreen = AppScreen.Home }
                    )
                }
            }
        }
    }
}

// ==================== 首页 ====================

@Composable
fun MobileHomeScreen(
    onNavigateToBrowser: () -> Unit,
    onNavigateToWebView: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "KBrowser 移动端演示",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Compose Multiplatform 生态缺失的 WebView 组件",
            fontSize = 13.sp,
            color = Color(0xFF888894),
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(32.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 卡片 1：KBWebView 组件演示（主角）
            MobileHomeTileCard(
                title = "KBWebView 组件演示",
                description = "KBrowser 的核心价值：Compose Multiplatform 生态里缺失的 WebView 组件。完整展示所有 API 的使用方式，可直接作为集成样板代码参考。",
                iconColor = Color(0xFF81C784),
                buttonText = "查看 WebView API 样板",
                onClick = onNavigateToWebView
            )
            // 卡片 2：浏览器自动化（次要，仅供参考）
            MobileHomeTileCard(
                title = "浏览器自动化（仅供参考）",
                description = "共享自桌面端的自动化控制代码。移动端部分功能可能不可用，仅作跨平台兼容性参考，不是移动端的使用重点。",
                iconColor = Color(0xFF64B5F6),
                buttonText = "查看自动化演示",
                onClick = onNavigateToBrowser
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "v1.2.0 • Android / iOS • WebView Ready",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF4E4E5A)
        )
    }
}

@Composable
fun MobileHomeTileCard(
    title: String,
    description: String,
    iconColor: Color,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2E2E36), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(iconColor))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = description, fontSize = 12.sp, color = Color(0xFF9E9EA8), lineHeight = 16.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = iconColor),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Text(text = buttonText, color = Color(0xFF121214), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

// ==================== 浏览器演示（次要，加警告） ====================

@Composable
fun MobileBrowserExampleScreen(
    viewModel: BrowserViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var isConsoleExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部导航条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF16161A))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text(text = "←", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "浏览器自动化演示", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        // ⚠️ 警告横幅：说明这是共享代码，移动端不保证可用
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF3E2A00))
                .border(1.dp, Color(0xFFFF8F00))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚠️", fontSize = 14.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "此页面共享自桌面端的自动化控制代码，移动端部分功能（截图、CDP、交互锁定等）可能不可用。这不是 KBrowser 移动端的使用重点。",
                fontSize = 11.sp,
                color = Color(0xFFFFCC80),
                lineHeight = 15.sp
            )
        }

        // 地址栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1E))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.navigateUrlInput,
                onValueChange = { viewModel.dispatch(BrowserIntent.ChangeNavigateUrl(it)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color(0xFF2E2E36)
                ),
                textStyle = TextStyle(fontSize = 12.sp, color = Color.White)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Button(
                onClick = { viewModel.dispatch(BrowserIntent.Navigate) },
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("加载", fontSize = 12.sp)
            }
        }

        // WebView 区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val activePage = state.page
            if (activePage != null) {
                KBWebView(webView = activePage.webView, modifier = Modifier.fillMaxSize())
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 底部折叠调试抽屉（保持原有功能，不是重点）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF16161A))
                .border(1.dp, Color(0xFF2E2E36))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isConsoleExpanded = !isConsoleExpanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("调试控制台（跨平台共享，功能不保证）", color = Color(0xFF888894), fontSize = 12.sp)
                Text(
                    text = if (isConsoleExpanded) "收起 ▼" else "展开 ▲",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            AnimatedVisibility(
                visible = isConsoleExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    ScrollableTabRow(
                        selectedTabIndex = state.selectedTab,
                        containerColor = Color(0xFF16161A),
                        contentColor = MaterialTheme.colorScheme.primary,
                        edgePadding = 0.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Tab(selected = state.selectedTab == 0, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(0)) }, text = { Text("控制", fontSize = 12.sp) })
                        Tab(selected = state.selectedTab == 1, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(1)) }, text = { Text("Logs", fontSize = 12.sp) })
                        Tab(selected = state.selectedTab == 2, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(2)) }, text = { Text("Aria", fontSize = 12.sp) })
                        Tab(selected = state.selectedTab == 3, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(3)) }, text = { Text("选择器", fontSize = 12.sp) })
                        Tab(selected = state.selectedTab == 4, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(4)) }, text = { Text("截图", fontSize = 12.sp) })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (state.selectedTab) {
                            0 -> BrowserControlTab(viewModel, state)
                            1 -> BrowserLogsTab(viewModel, state)
                            2 -> SearchableDataView(state.snapshotText, state.snapshotSearchQuery, "暂无 Aria 快照", onQueryChange = { viewModel.dispatch(BrowserIntent.ChangeSnapshotSearch(it)) })
                            3 -> DataView(state.selectorsText, "暂无选择器列表")
                            4 -> ScreenshotPreview(state.screenshotBytes, state.screenshotAxTree)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserControlTab(viewModel: BrowserViewModel, state: BrowserViewState) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { viewModel.dispatch(BrowserIntent.RunAutoFlow) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) { Text("一键自动化测试", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold) }
            Button(
                onClick = { viewModel.dispatch(BrowserIntent.TakeScreenshot) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) { Text("截图", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { viewModel.dispatch(BrowserIntent.FetchHtml) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E36)), contentPadding = PaddingValues(vertical = 4.dp)) { Text("HTML", fontSize = 10.sp, color = Color.White) }
            Button(onClick = { viewModel.dispatch(BrowserIntent.FetchSemanticSnapshot) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E36)), contentPadding = PaddingValues(vertical = 4.dp)) { Text("Aria", fontSize = 10.sp, color = Color.White) }
            Button(onClick = { viewModel.dispatch(BrowserIntent.FetchSelectors) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E36)), contentPadding = PaddingValues(vertical = 4.dp)) { Text("选择器", fontSize = 10.sp, color = Color.White) }
        }
        Button(
            onClick = { viewModel.dispatch(BrowserIntent.ClearCacheAndCookies) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) { Text("清除缓存与 Cookies", fontSize = 11.sp, color = Color.White) }
    }
}

@Composable
private fun BrowserLogsTab(viewModel: BrowserViewModel, state: BrowserViewState) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("LOGS", color = Color(0xFF888894), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            TextButton(onClick = { viewModel.dispatch(BrowserIntent.ClearLogs) }, contentPadding = PaddingValues(0.dp)) {
                Text("清空", fontSize = 11.sp)
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF0F0F12)).padding(6.dp)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.logs) { log ->
                    Text(log, color = if (log.contains("成功") || log.contains("完成")) Color(0xFF81C784) else Color(0xFFE0E0E0), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// ==================== WebView 组件演示（主角） ====================

/**
 * KBWebView 组件使用样板。
 *
 * 这是移动端演示的核心页面。展示的是"如何把 KBWebView 集成进你的 App"，
 * 每个 API 对应一个清晰的 UI 区块，可直接作为样板代码参考。
 *
 * 注意：这里直接使用 [rememberKBWebView] 在 Composable 里管理 WebView 生命周期，
 * 不需要 ViewModel 包装，这才是 KBWebView 作为 Compose 组件的正确用法。
 */
@Composable
fun MobileWebViewExampleScreen(onBack: () -> Unit) {
    // ── 直接用 rememberKBWebView，生命周期由 Compose 管理 ──────────────────
    val webView = rememberKBWebView(initialUrl = null)

    // 收集响应式状态
    val currentUrl by webView.currentUrl.collectAsState()
    val currentTitle by webView.currentTitle.collectAsState()
    val loadingState by webView.loadingState.collectAsState()
    val progress by webView.progress.collectAsState()
    val canGoBack by webView.canGoBack.collectAsState()
    val canGoForward by webView.canGoForward.collectAsState()

    // 本地 UI 状态
    val scope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf("https://www.bing.com") }
    var jsInput by remember { mutableStateOf("document.title") }
    var jsResult by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(listOf("KBWebView 已就绪")) }
    var newWindowUrl by remember { mutableStateOf("") }
    var callbackEnabled by remember { mutableStateOf(false) }
    var callbackMessage by remember { mutableStateOf("（尚未收到回调）") }

    fun log(msg: String) {
        logs = logs + msg
    }

    // 注册/注销 JS 回调示例
    LaunchedEffect(callbackEnabled) {
        if (callbackEnabled) {
            webView.registerJsCallback("onNativeMessage") { data ->
                callbackMessage = data
                log("收到 JS 回调: $data")
            }
            log("已注册 JS 回调 'onNativeMessage'")
        } else {
            webView.unregisterJsCallback("onNativeMessage")
            log("已注销 JS 回调 'onNativeMessage'")
        }
    }

    // onNewWindowRequest 拦截示例
    DisposableEffect(webView) {
        webView.onNewWindowRequest = { url ->
            newWindowUrl = url
            log("拦截到新窗口请求: $url（已阻止跳出 App）")
        }
        onDispose {
            webView.onNewWindowRequest = null
        }
    }

    // 默认加载一个带回调按钮的本地 HTML，方便演示
    LaunchedEffect(Unit) {
        webView.loadHtml(WEBVIEW_DEMO_HTML)
        log("已加载内置演示 HTML")
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── 顶部导航条 ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF16161A))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text("←", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("KBWebView 组件演示", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    text = currentTitle?.takeIf { it.isNotBlank() } ?: currentUrl ?: "—",
                    fontSize = 10.sp,
                    color = Color(0xFF888894),
                    maxLines = 1
                )
            }
            // 加载状态指示
            when (loadingState) {
                is LoadingState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color(0xFF64B5F6),
                    strokeWidth = 2.dp
                )
                is LoadingState.Error -> Text("✕", color = Color(0xFFEF5350), fontSize = 16.sp)
                else -> Text("✓", color = Color(0xFF81C784), fontSize = 16.sp)
            }
        }

        // ── 加载进度条（loadingState + progress）────────────────────────────
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

        // ── WebView 主体（占屏幕约 45%）──────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.9f)
                .background(Color(0xFF0F0F12))
        ) {
            KBWebView(webView = webView, modifier = Modifier.fillMaxSize())
        }

        // ── API 展示面板（可滚动）────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.1f)
                .background(Color(0xFF16161A))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── API 1: loadUrl / loadHtml + 基础导航 ─────────────────────────
            ApiSection(title = "1. 加载与导航", color = Color(0xFF64B5F6)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                    // loadUrl
                    ApiButton("loadUrl", Color(0xFF1565C0)) {
                        webView.loadUrl(urlInput)
                        log("loadUrl(\"$urlInput\")")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // loadHtml
                    ApiButton("loadHtml", Color(0xFF2E7D32), modifier = Modifier.weight(1f)) {
                        webView.loadHtml(WEBVIEW_DEMO_HTML)
                        log("loadHtml(...) — 加载内置演示页")
                    }
                    // reload
                    ApiButton("reload", Color(0xFF4A4A55), modifier = Modifier.weight(1f)) {
                        webView.reload()
                        log("reload()")
                    }
                    // stopLoading
                    ApiButton("stop", Color(0xFF4A4A55), modifier = Modifier.weight(1f)) {
                        webView.stopLoading()
                        log("stopLoading()")
                    }
                }
                // goBack / goForward（绑定 canGoBack / canGoForward 状态）
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ApiButton(
                        label = "← goBack",
                        color = if (canGoBack) Color(0xFF1565C0) else Color(0xFF2E2E36),
                        modifier = Modifier.weight(1f),
                        enabled = canGoBack
                    ) {
                        webView.goBack()
                        log("goBack()")
                    }
                    ApiButton(
                        label = "goForward →",
                        color = if (canGoForward) Color(0xFF1565C0) else Color(0xFF2E2E36),
                        modifier = Modifier.weight(1f),
                        enabled = canGoForward
                    ) {
                        webView.goForward()
                        log("goForward()")
                    }
                }
            }

            // ── API 2: 响应式状态监听 ─────────────────────────────────────────
            ApiSection(title = "2. 响应式状态（StateFlow）", color = Color(0xFF81C784)) {
                StateRow("currentUrl", currentUrl ?: "null")
                StateRow("currentTitle", currentTitle ?: "null")
                StateRow("loadingState", loadingState::class.simpleName ?: "—")
                StateRow("progress", "${(progress * 100).toInt()}%")
                StateRow("canGoBack", canGoBack.toString())
                StateRow("canGoForward", canGoForward.toString())
            }

            // ── API 3: evaluateJavascript ─────────────────────────────────────
            ApiSection(title = "3. evaluateJavascript", color = Color(0xFFFFB74D)) {
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
                            focusedBorderColor = Color(0xFFFFB74D),
                            unfocusedBorderColor = Color(0xFF2E2E36)
                        ),
                        textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    ApiButton("执行", Color(0xFFE65100)) {
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
                        color = Color(0xFFFFCC80),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E24))
                            .padding(8.dp)
                    )
                }
            }

            // ── API 4: registerJsCallback / unregisterJsCallback ──────────────
            ApiSection(title = "4. registerJsCallback / unregisterJsCallback", color = Color(0xFFCE93D8)) {
                Text(
                    text = "开启后，演示页里的按钮可通过 window.onNativeMessage('...') 向 Native 发送消息。",
                    fontSize = 11.sp,
                    color = Color(0xFF9E9EA8),
                    lineHeight = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
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

            // ── API 5: onNewWindowRequest ─────────────────────────────────────
            ApiSection(title = "5. onNewWindowRequest（拦截新窗口）", color = Color(0xFF80DEEA)) {
                Text(
                    text = "已设置拦截器。当页面通过 target=\"_blank\" 或 window.open() 请求打开新窗口时，URL 会被捕获到这里，而不是跳出 App。",
                    fontSize = 11.sp,
                    color = Color(0xFF9E9EA8),
                    lineHeight = 15.sp
                )
                if (newWindowUrl.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "拦截到: $newWindowUrl",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF80DEEA),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1E1E24))
                            .padding(8.dp)
                    )
                }
                ApiButton("触发测试（打开外链）", Color(0xFF00838F), modifier = Modifier.fillMaxWidth()) {
                    webView.evaluateJavascript("window.open('https://github.com/kbrowser', '_blank')", null)
                    log("触发 window.open() 测试")
                }
            }

            // ── API 6: clearCacheAndCookies ───────────────────────────────────
            ApiSection(title = "6. clearCacheAndCookies", color = Color(0xFFEF9A9A)) {
                Text(
                    text = "清除当前 WebView 的所有缓存与 Cookie，常用于退出登录或无痕模式切换。",
                    fontSize = 11.sp,
                    color = Color(0xFF9E9EA8),
                    lineHeight = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                ApiButton("clearCacheAndCookies()", Color(0xFFC62828), modifier = Modifier.fillMaxWidth()) {
                    webView.clearCacheAndCookies()
                    log("clearCacheAndCookies() — 缓存与 Cookie 已清除")
                }
            }

            // ── 日志区 ────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0F0F12))
                    .border(1.dp, Color(0xFF23232A), RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LOG", color = Color(0xFF888894), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(
                        text = "清空",
                        color = Color(0xFF64B5F6),
                        fontSize = 10.sp,
                        modifier = Modifier.clickable { logs = emptyList() }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs.reversed()) { entry ->
                        Text(
                            text = entry,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (entry.contains("→") || entry.contains("收到")) Color(0xFF81C784) else Color(0xFFE0E0E0)
                        )
                    }
                }
            }
        }
    }
}

// ==================== 小工具 Composable ====================

@Composable
private fun ApiSection(
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
        Text(text = title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
        content()
    }
}

@Composable
private fun ApiButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            disabledContainerColor = Color(0xFF2E2E36)
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 11.sp, color = if (enabled) Color.White else Color(0xFF666666))
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

// ==================== 内置演示 HTML ====================

/**
 * 内置演示 HTML，用于展示 JS 回调和新窗口拦截。
 * 页面里有一个按钮调用 window.onNativeMessage()，
 * 还有一个 target="_blank" 链接触发 onNewWindowRequest。
 */
private val WEBVIEW_DEMO_HTML = """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>KBWebView 演示页</title>
  <style>
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      background: #121214;
      color: #e0e0e0;
      padding: 20px;
      margin: 0;
    }
    h2 { color: #64B5F6; font-size: 18px; margin-bottom: 4px; }
    p { font-size: 13px; color: #9e9ea8; margin: 4px 0 12px; }
    button {
      background: #81C784;
      color: #121214;
      border: none;
      padding: 10px 20px;
      border-radius: 8px;
      font-weight: bold;
      font-size: 14px;
      cursor: pointer;
      display: block;
      width: 100%;
      margin-bottom: 10px;
    }
    a {
      color: #80DEEA;
      font-size: 13px;
    }
    .card {
      background: #1a1a1e;
      border: 1px solid #2e2e36;
      border-radius: 10px;
      padding: 14px;
      margin-bottom: 14px;
    }
  </style>
</head>
<body>
  <div class="card">
    <h2>registerJsCallback 演示</h2>
    <p>点击下方按钮，调用 Native 注册的 <code>onNativeMessage</code> 回调。<br>需要先在面板里开启"注册回调"开关。</p>
    <button onclick="
      if (window.onNativeMessage) {
        window.onNativeMessage('Hello from WebView! 时间: ' + new Date().toLocaleTimeString());
      } else {
        alert('Native 回调未注册，请先开启开关');
      }
    ">触发 Native 回调</button>
  </div>
  <div class="card">
    <h2>onNewWindowRequest 演示</h2>
    <p>点击下方链接，触发 <code>target="_blank"</code> 新窗口请求，会被 Native 拦截而不是跳出 App。</p>
    <a href="https://github.com" target="_blank">打开外部链接（会被拦截）</a>
  </div>
</body>
</html>
""".trimIndent()
