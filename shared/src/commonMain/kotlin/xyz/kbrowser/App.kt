package xyz.kbrowser

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.kbrowser.webview.KBWebView
import xyz.kbrowser.webview.LoadingState

// 路由页面定义
sealed interface AppScreen {
    object Home : AppScreen
    object BrowserExample : AppScreen
    object WebViewExample : AppScreen
}

@Composable
fun App() {
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
                AppScreen.Home -> HomeScreen(
                    onNavigateToBrowser = { currentScreen = AppScreen.BrowserExample },
                    onNavigateToWebView = { currentScreen = AppScreen.WebViewExample }
                )
                AppScreen.BrowserExample -> {
                    val viewModel = remember { BrowserViewModel() }
                    DisposableEffect(Unit) {
                        onDispose {
                            viewModel.state.value.page?.close()
                        }
                    }
                    BrowserExampleScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = AppScreen.Home }
                    )
                }
                AppScreen.WebViewExample -> {
                    val viewModel = remember { WebViewExampleViewModel() }
                    DisposableEffect(Unit) {
                        onDispose {
                            viewModel.state.value.page?.close()
                        }
                    }
                    WebViewExampleScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = AppScreen.Home }
                    )
                }
            }
        }
    }
}

// ==================== 1. 首页 (HomeScreen) ====================
@Composable
fun HomeScreen(
    onNavigateToBrowser: () -> Unit,
    onNavigateToWebView: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 大标题与副标题
        Text(
            text = "KBrowser 核心引擎演示系统",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "基于 Kotlin Multiplatform 与 Compose 的高性能跨平台渲染内核",
            fontSize = 14.sp,
            color = Color(0xFF888894),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(48.dp))

        // 磁贴卡片
        Row(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(280.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 卡片 1：浏览器模式
            HomeTileCard(
                modifier = Modifier.weight(1f),
                title = "Browser 浏览器模式",
                description = "支持多标签页浏览、全网页加载渲染、智能语义快照树 (Aria) 提取以及 CSS 选择器抓取，内置全面的流程自动化调试工具。",
                iconColor = Color(0xFF64B5F6),
                buttonText = "进入浏览器模式",
                onClick = onNavigateToBrowser
            )

            // 卡片 2：WebView API 实验室
            HomeTileCard(
                modifier = Modifier.weight(1f),
                title = "KBWebView API 实验室",
                description = "全面演示底层 API：支持高画质 Markdown 实时预览编译、JS 双向通信与回调、实时鼠标坐标捕获监听、原生坐标模拟手势分发及安全防检测遮罩。",
                iconColor = Color(0xFF81C784),
                buttonText = "进入 API 实验室",
                onClick = onNavigateToWebView
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
        Text(
            text = "v1.2.0 • powered by JCEF & Webkit • macOS 渲染就绪",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF4E4E5A)
        )
    }
}

@Composable
fun HomeTileCard(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    iconColor: Color,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxHeight()
            .border(1.dp, Color(0xFF2E2E36), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(iconColor))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color(0xFF9E9EA8),
                    lineHeight = 18.sp
                )
            }
            
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = iconColor),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = buttonText,
                    color = Color(0xFF121214),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// ==================== 2. 浏览器示例页面 (BrowserExampleScreen) ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserExampleScreen(
    viewModel: BrowserViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 导航顶部条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF16161A))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text(
                    text = "←",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Browser 浏览器演示模式",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 左侧：浏览器区域 (60%)
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
            ) {
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
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.dispatch(BrowserIntent.Navigate) },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("跳转", fontSize = 13.sp)
                    }
                }

                // WebView 挂载区
                Box(modifier = Modifier.fillMaxSize()) {
                    val activePage = state.page
                    if (activePage != null) {
                        KBWebView(
                            webView = activePage.webView,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // 右侧：控制调试面板 (40%)
            Column(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .background(Color(0xFF16161A))
                    .padding(16.dp)
            ) {
                Text(
                    text = "KBrowser 自动化调试台",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 开关区
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = state.isOsrMode,
                        onCheckedChange = { viewModel.dispatch(BrowserIntent.ToggleOsr(it)) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = Color(0xFF888894)
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("OSR", color = Color.White, fontSize = 12.sp)
                }

                // 终端日志
                Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F0F12))
                        .border(1.dp, Color(0xFF23232A), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SYSTEM LOGS", color = Color(0xFF888894), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        TextButton(onClick = { viewModel.dispatch(BrowserIntent.ClearLogs) }, contentPadding = PaddingValues(0.dp)) {
                            Text("Clear", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(state.logs) { logMsg ->
                            Text(
                                text = logMsg,
                                color = if (logMsg.contains("成功") || logMsg.contains("完毕") || logMsg.contains("完成")) Color(0xFF81C784) else Color(0xFFE0E0E0),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tab Row
                PrimaryTabRow(selectedTabIndex = state.selectedTab, containerColor = Color(0xFF16161A), contentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) {
                    Tab(selected = state.selectedTab == 0, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(0)) }, text = { Text("控制", fontSize = 13.sp) })
                    Tab(selected = state.selectedTab == 1, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(1)) }, text = { Text("Aria", fontSize = 13.sp) })
                    Tab(selected = state.selectedTab == 2, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(2)) }, text = { Text("选择器", fontSize = 13.sp) })
                    Tab(selected = state.selectedTab == 3, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(3)) }, text = { Text("HTML", fontSize = 13.sp) })
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Tab 内容
                Box(modifier = Modifier.weight(0.6f).fillMaxWidth()) {
                    when (state.selectedTab) {
                        0 -> {
                            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Button(
                                    onClick = { viewModel.dispatch(BrowserIntent.RunAutoFlow) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("一键测试自动化链", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.dispatch(BrowserIntent.FetchHtml) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E36))) { Text("HTML", fontSize = 12.sp, color = Color.White) }
                                    Button(onClick = { viewModel.dispatch(BrowserIntent.FetchSemanticSnapshot) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E36))) { Text("Aria", fontSize = 12.sp, color = Color.White) }
                                    Button(onClick = { viewModel.dispatch(BrowserIntent.FetchSelectors) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E36))) { Text("选择器", fontSize = 12.sp, color = Color.White) }
                                }
                                HorizontalDivider(color = Color(0xFF23232A))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("CSS 选择器点击", color = Color(0xFF888894), fontSize = 12.sp)
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = state.customSelector,
                                            onValueChange = { viewModel.dispatch(BrowserIntent.ChangeCustomSelector(it)) },
                                            placeholder = { Text(".btn-login", fontSize = 12.sp) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF2E2E36)),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(onClick = { viewModel.dispatch(BrowserIntent.ClickSelector(state.customSelector)) }, shape = RoundedCornerShape(8.dp)) { Text("点击", fontSize = 13.sp) }
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF23232A))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("坐标交互", color = Color(0xFF888894), fontSize = 12.sp)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = state.coordX,
                                            onValueChange = { viewModel.dispatch(BrowserIntent.ChangeCoordX(it)) },
                                            label = { Text("X", fontSize = 10.sp) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF2E2E36)),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                        )
                                        OutlinedTextField(
                                            value = state.coordY,
                                            onValueChange = { viewModel.dispatch(BrowserIntent.ChangeCoordY(it)) },
                                            label = { Text("Y", fontSize = 10.sp) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF2E2E36)),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                        )
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { viewModel.dispatch(BrowserIntent.ClickCoordinates(state.coordX.toIntOrNull() ?: 0, state.coordY.toIntOrNull() ?: 0)) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))) { Text("点击", fontSize = 12.sp, color = Color.White) }
                                        Button(onClick = { viewModel.dispatch(BrowserIntent.HoverCoordinates(state.coordX.toIntOrNull() ?: 0, state.coordY.toIntOrNull() ?: 0)) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1))) { Text("悬停", fontSize = 12.sp, color = Color.White) }
                                    }
                                }
                            }
                        }
                        1 -> { DataView(state.snapshotText, "暂无Aria数据") }
                        2 -> { DataView(state.selectorsText, "暂无选择器数据") }
                        3 -> { DataView(state.htmlPreview, "暂无HTML数据") }
                    }
                }
            }
        }
    }
}

// ==================== 3. KBWebView API 实验室 (WebViewExampleScreen) ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewExampleScreen(
    viewModel: WebViewExampleViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 导航顶部条与 API 状态监视器
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF16161A))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text(
                    text = "←",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            
            // 模式选择器
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F0F12))
                    .border(1.dp, Color(0xFF2E2E36), RoundedCornerShape(8.dp))
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Markdown 渲染",
                    color = if (state.selectedMode == 0) Color.White else Color(0xFF888894),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (state.selectedMode == 0) Color(0xFF2E2E36) else Color.Transparent)
                        .clickable { viewModel.dispatch(WebViewExampleIntent.ChangeMode(0)) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                Text(
                    text = "网页 API 实验室",
                    color = if (state.selectedMode == 1) Color.White else Color(0xFF888894),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (state.selectedMode == 1) Color(0xFF2E2E36) else Color.Transparent)
                        .clickable { viewModel.dispatch(WebViewExampleIntent.ChangeMode(1)) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 状态监视器
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F0F12))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Title: ${state.currentTitle.takeIf { it.isNotEmpty() } ?: "N/A"}",
                    color = Color(0xFF64B5F6),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "URL: ${state.currentUrl.takeIf { it.isNotEmpty() } ?: "local_html"}",
                    color = Color(0xFF81C784),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.weight(1.5f)
                )
                
                // Loading & Progress
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (state.loadingState == LoadingState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = Color(0xFF64B5F6)
                        )
                    }
                    Text(
                        text = "Prog: ${(state.progress * 100).toInt()}%",
                        color = Color(0xFFFFB74D),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 加载进度条
        if (state.loadingState == LoadingState.Loading) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Color(0xFF64B5F6),
                trackColor = Color.Transparent
            )
        } else {
            Spacer(modifier = Modifier.height(2.dp))
        }

        // 主体双栏内容
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121214))
        ) {
            if (state.selectedMode == 0) {
                // ================== Markdown 模式 ==================
                // 左侧：Markdown 编辑器 (50%)
                Column(
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxHeight()
                        .border(1.dp, Color(0xFF2E2E36))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Markdown 编辑器 (实时同步)",
                        color = Color(0xFF888894),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    OutlinedTextField(
                        value = state.markdownInput,
                        onValueChange = { viewModel.dispatch(WebViewExampleIntent.ChangeMarkdown(it)) },
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF64B5F6),
                            unfocusedBorderColor = Color(0xFF2E2E36)
                        ),
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                    )
                }

                // 右侧：KBWebView 挂载预览区 (50%)
                Column(
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxHeight()
                        .border(1.dp, Color(0xFF2E2E36))
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val activePage = state.page
                        if (activePage != null) {
                            KBWebView(
                                webView = activePage.webView,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Color(0xFF64B5F6))
                            }
                        }
                    }
                }
            } else {
                // ================== 网页 API 模式 ==================
                // 左侧：KBWebView 页面渲染区 (55%)
                Column(
                    modifier = Modifier
                        .weight(0.55f)
                        .fillMaxHeight()
                        .border(1.dp, Color(0xFF2E2E36))
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val activePage = state.page
                        if (activePage != null) {
                            KBWebView(
                                webView = activePage.webView,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Color(0xFF64B5F6))
                            }
                        }
                    }
                }

                // 右侧：API 实验室控制调试台 (45%)
                Column(
                    modifier = Modifier
                        .weight(0.45f)
                        .fillMaxHeight()
                        .background(Color(0xFF16161A))
                        .padding(12.dp)
                ) {
                    // 调试工具标题与导航
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "KBWebView API 控制台",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        // 导航控制按钮组
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { viewModel.dispatch(WebViewExampleIntent.GoBack) },
                                enabled = state.canGoBack,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Text("←", color = if (state.canGoBack) Color.White else Color(0xFF4E4E5A), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = { viewModel.dispatch(WebViewExampleIntent.GoForward) },
                                enabled = state.canGoForward,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Text("→", color = if (state.canGoForward) Color.White else Color(0xFF4E4E5A), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = { viewModel.dispatch(WebViewExampleIntent.Reload) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Text("↻", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = { viewModel.dispatch(WebViewExampleIntent.StopLoading) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Text("✕", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // 地址栏及快捷加载
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.navigateUrlInput,
                            onValueChange = { viewModel.dispatch(WebViewExampleIntent.ChangeUrlInput(it)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF64B5F6),
                                unfocusedBorderColor = Color(0xFF2E2E36)
                            ),
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                            placeholder = { Text("输入 URL...", fontSize = 11.sp) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = { viewModel.dispatch(WebViewExampleIntent.Navigate) },
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("加载", fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(
                            onClick = { viewModel.dispatch(WebViewExampleIntent.LoadLocalInteractiveTestPage) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81C784)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("交互测试页", fontSize = 11.sp, color = Color(0xFF121214))
                        }
                    }

                    // 二分控制区：上面是各种 API 交互参数，下面是日志区
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // API 模块 1：JS 执行与双向回调
                        Card(
                            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF23232A), RoundedCornerShape(8.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("1. 脚本与双向回调 (JS Communication)", color = Color(0xFF81C784), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = state.jsInput,
                                        onValueChange = { viewModel.dispatch(WebViewExampleIntent.ChangeJsInput(it)) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF64B5F6),
                                            unfocusedBorderColor = Color(0xFF2E2E36)
                                        ),
                                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Button(
                                        onClick = { viewModel.dispatch(WebViewExampleIntent.ExecuteJs) },
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text("执行 JS", fontSize = 11.sp)
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = state.isCaptureCallbackRegistered,
                                            onCheckedChange = { viewModel.dispatch(WebViewExampleIntent.ToggleCaptureCallback(it)) },
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("实时坐标捕获", color = Color.White, fontSize = 11.sp)
                                    }
                                    
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "鼠标捕获: (${state.capturedX}, ${state.capturedY})",
                                            color = Color(0xFFFFB74D),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "填充",
                                            color = Color(0xFF64B5F6),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clickable { viewModel.dispatch(WebViewExampleIntent.UseCapturedCoords) }
                                                .border(1.dp, Color(0xFF64B5F6), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // API 模块 2：坐标交互模拟
                        Card(
                            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF23232A), RoundedCornerShape(8.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("2. 原生手势坐标模拟 (Gesture Simulation)", color = Color(0xFF64B5F6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = state.coordXInput,
                                        onValueChange = { viewModel.dispatch(WebViewExampleIntent.ChangeCoordX(it)) },
                                        label = { Text("X", fontSize = 9.sp) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF2E2E36)),
                                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    )
                                    OutlinedTextField(
                                        value = state.coordYInput,
                                        onValueChange = { viewModel.dispatch(WebViewExampleIntent.ChangeCoordY(it)) },
                                        label = { Text("Y", fontSize = 9.sp) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF2E2E36)),
                                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Button(onClick = { viewModel.dispatch(WebViewExampleIntent.SimulateClick) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) { Text("模拟点击", fontSize = 10.sp) }
                                    Button(onClick = { viewModel.dispatch(WebViewExampleIntent.SimulateHover) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(vertical = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1))) { Text("模拟悬停", fontSize = 10.sp, color = Color.White) }
                                    Button(onClick = { viewModel.dispatch(WebViewExampleIntent.SimulateScroll) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(vertical = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E57C2))) { Text("模拟滚动", fontSize = 10.sp, color = Color.White) }
                                    Button(onClick = { viewModel.dispatch(WebViewExampleIntent.SimulateDrag) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(vertical = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD81B60))) { Text("模拟拖拽", fontSize = 10.sp, color = Color.White) }
                                }
                            }
                        }

                        // API 模块 3：无痕清理
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .border(1.dp, Color(0xFF23232A), RoundedCornerShape(8.dp))
                                    .clickable { viewModel.dispatch(WebViewExampleIntent.ClearCache) },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier.fillPanel().padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text("🗑", color = Color(0xFFFF7043), fontSize = 12.sp)
                                        Text("无痕清理 Cookie/缓存", color = Color(0xFFFF7043), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // 终端调试日志区
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0F0F12))
                                .border(1.dp, Color(0xFF23232A), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("SYSTEM LOGS", color = Color(0xFF888894), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                TextButton(
                                    onClick = { viewModel.dispatch(WebViewExampleIntent.ClearLogs) },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.height(20.dp)
                                ) {
                                    Text("Clear", color = Color(0xFF64B5F6), fontSize = 10.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(state.logs) { logMsg ->
                                    Text(
                                        text = logMsg,
                                        color = if (logMsg.contains("成功") || logMsg.contains("完毕") || logMsg.contains("完成")) Color(0xFF81C784) else Color(0xFFE0E0E0),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 辅助布局修饰符
private fun Modifier.scale(scale: Float): Modifier = this.then(
    // 简单用以在 desktop 缩放 switch 尺寸
    Modifier
)

private fun Modifier.fillPanel(): Modifier = this.then(
    Modifier.fillMaxWidth().height(36.dp)
)

@Composable
fun DataView(text: String, emptyMessage: String) {
    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F0F12)).padding(8.dp)) {
        if (text.isBlank()) {
            Text(emptyMessage, color = Color(0xFF888894), fontSize = 12.sp)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { Text(text, color = Color(0xFF81C784), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}