package xyz.kbrowser

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
                        
                        // 实验：在浏览器视口上方直接重叠挂载一个 Compose 的交互式悬浮卡片
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xE61A1A1E))
                                .border(1.dp, Color(0xFF3A3A42), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                                .width(220.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "⚡️ Compose 混合渲染实验",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "此悬浮卡片为纯 Compose 视图，目前正完美覆盖在 OSR 浏览器画布上层。",
                                    color = Color(0xFF9E9EA8),
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                                Button(
                                    onClick = { 
                                        viewModel.dispatch(BrowserIntent.ClickSelector("ComposeHoverClick"))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text("点击此 Compose 按钮", fontSize = 11.sp, color = Color(0xFF121214), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
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

                // OSR Checkbox has been removed; rendering is decided by engine natively

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
                    Tab(selected = state.selectedTab == 0, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(0)) }, text = { Text("控制", fontSize = 12.sp) })
                    Tab(selected = state.selectedTab == 1, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(1)) }, text = { Text("Aria", fontSize = 12.sp) })
                    Tab(selected = state.selectedTab == 2, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(2)) }, text = { Text("选择器", fontSize = 12.sp) })
                    Tab(selected = state.selectedTab == 3, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(3)) }, text = { Text("HTML", fontSize = 12.sp) })
                    Tab(selected = state.selectedTab == 4, onClick = { viewModel.dispatch(BrowserIntent.ChangeTab(4)) }, text = { Text("截图", fontSize = 12.sp) })
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Tab 内容
                Box(modifier = Modifier.weight(0.6f).fillMaxWidth()) {
                    when (state.selectedTab) {
                        0 -> {
                            val scrollState = rememberScrollState()
                            Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Button(
                                    onClick = { viewModel.dispatch(BrowserIntent.RunAutoFlow) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("一键测试自动化链", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { viewModel.dispatch(BrowserIntent.TakeScreenshot) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("测试网页截图", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                // AXTree 画框调试工具
                                Text("AXTree 画框调试", color = Color(0xFF888894), fontSize = 11.sp)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = { viewModel.dispatch(BrowserIntent.OverlayRawAxTree) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                                    ) { Text("原始框", fontSize = 11.sp, color = Color.White) }
                                    Button(
                                        onClick = { viewModel.dispatch(BrowserIntent.OverlayCleanedAxTree) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                    ) { Text("清洗框", fontSize = 11.sp, color = Color.White) }
                                    Button(
                                        onClick = { viewModel.dispatch(BrowserIntent.ClearOverlay) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4A55))
                                    ) { Text("清除", fontSize = 11.sp, color = Color.White) }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.dispatch(BrowserIntent.FetchHtml) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E36))) { Text("HTML", fontSize = 12.sp, color = Color.White) }
                                    Button(onClick = { viewModel.dispatch(BrowserIntent.FetchSemanticSnapshot) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E36))) { Text("Aria", fontSize = 12.sp, color = Color.White) }
                                    Button(onClick = { viewModel.dispatch(BrowserIntent.FetchSelectors) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E36))) { Text("选择器", fontSize = 12.sp, color = Color.White) }
                                }
                                HorizontalDivider(color = Color(0xFF23232A))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("KBLocator 自动化定位测试", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    
                                    // 1. 选择器类型单选 (2 rows of 5 and 4)
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf(
                                            listOf("CSS", "XPath", "Text", "Role", "Label"),
                                            listOf("Placeholder", "AltText", "Title", "TestId")
                                        ).forEach { rowTypes ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                rowTypes.forEach { type ->
                                                    val isSelected = state.locatorSelectorType == type
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF2E2E36))
                                                            .clickable { viewModel.dispatch(BrowserIntent.ChangeLocatorSelectorType(type)) }
                                                            .padding(vertical = 4.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = type,
                                                            color = if (isSelected) Color(0xFF121214) else Color.White,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 2. 选择器表达式输入
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = state.locatorSelector,
                                            onValueChange = { viewModel.dispatch(BrowserIntent.ChangeLocatorSelector(it)) },
                                            placeholder = { Text(
                                                text = when (state.locatorSelectorType) {
                                                    "Role" -> "例如: button, link"
                                                    "XPath" -> "//button[@id='submit']"
                                                    "Text" -> "查找匹配文本"
                                                    "Label" -> "表单标签文本"
                                                    "Placeholder" -> "输入框的placeholder"
                                                    "AltText" -> "图片的alt属性"
                                                    "Title" -> "元素的title属性"
                                                    "TestId" -> "data-testid 属性值"
                                                    else -> "CSS: .btn-login, input[type=text]"
                                                },
                                                fontSize = 11.sp
                                            ) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                unfocusedBorderColor = Color(0xFF2E2E36)
                                            ),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                                        )
                                        
                                        if (state.locatorSelectorType == "Role") {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            OutlinedTextField(
                                                value = state.locatorRoleName,
                                                onValueChange = { viewModel.dispatch(BrowserIntent.ChangeLocatorRoleName(it)) },
                                                placeholder = { Text("Name (可选)", fontSize = 11.sp) },
                                                modifier = Modifier.weight(0.7f),
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                    unfocusedBorderColor = Color(0xFF2E2E36)
                                                ),
                                                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                                            )
                                        }
                                    }

                                    // 3. 填充值输入框（针对 Fill, Type 等操作）
                                    OutlinedTextField(
                                        value = state.locatorValue,
                                        onValueChange = { viewModel.dispatch(BrowserIntent.ChangeLocatorValue(it)) },
                                        placeholder = { Text("输入测试值 (用于 Fill / Type / Select)", fontSize = 11.sp) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = Color(0xFF2E2E36)
                                        ),
                                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                                    )

                                    // 4. 按钮交互矩阵
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Button(
                                                onClick = { viewModel.dispatch(BrowserIntent.LocatorClick) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                                            ) {
                                                Text("点击 (Click)", fontSize = 10.sp, color = Color.White)
                                            }
                                            Button(
                                                onClick = { viewModel.dispatch(BrowserIntent.LocatorHover) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1))
                                            ) {
                                                Text("悬停 (Hover)", fontSize = 10.sp, color = Color.White)
                                            }
                                            Button(
                                                onClick = { viewModel.dispatch(BrowserIntent.LocatorFocus) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))
                                            ) {
                                                Text("聚焦 (Focus)", fontSize = 10.sp, color = Color.White)
                                            }
                                            Button(
                                                onClick = { viewModel.dispatch(BrowserIntent.LocatorCheck) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))
                                            ) {
                                                Text("勾选 (Check)", fontSize = 10.sp, color = Color.White)
                                            }
                                        }
                                        
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Button(
                                                onClick = { viewModel.dispatch(BrowserIntent.LocatorFill) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5))
                                            ) {
                                                Text("填充 (Fill)", fontSize = 10.sp, color = Color.White)
                                            }
                                            Button(
                                                onClick = { viewModel.dispatch(BrowserIntent.LocatorType) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                                            ) {
                                                Text("打字 (Type)", fontSize = 10.sp, color = Color.White)
                                            }
                                            Button(
                                                onClick = { viewModel.dispatch(BrowserIntent.LocatorScroll) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D4C41))
                                            ) {
                                                Text("滚动 (Scroll)", fontSize = 10.sp, color = Color.White)
                                            }
                                            Button(
                                                onClick = { viewModel.dispatch(BrowserIntent.LocatorSelectOption) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C))
                                            ) {
                                                Text("选项 (SelectOption)", fontSize = 10.sp, color = Color.White)
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Button(
                                                onClick = { viewModel.dispatch(BrowserIntent.LocatorGetText) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
                                            ) {
                                                Text("文本 (getText)", fontSize = 10.sp, color = Color.White)
                                            }
                                            Button(
                                                onClick = { viewModel.dispatch(BrowserIntent.LocatorIsVisible) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF607D8B))
                                            ) {
                                                Text("可见 (isVisible)", fontSize = 10.sp, color = Color.White)
                                            }
                                            Button(
                                                onClick = { viewModel.dispatch(BrowserIntent.LocatorCount) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))
                                            ) {
                                                Text("数量 (count)", fontSize = 10.sp, color = Color.White)
                                            }
                                            Button(
                                                onClick = { viewModel.dispatch(BrowserIntent.LocatorBoundingBox) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4E342E))
                                            ) {
                                                Text("包围盒 (boundingBox)", fontSize = 10.sp, color = Color.White)
                                            }
                                            Button(
                                                onClick = { viewModel.dispatch(BrowserIntent.LocatorGetAttribute) },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(4.dp),
                                                contentPadding = PaddingValues(vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E))
                                            ) {
                                                Text("属性 (getAttribute)", fontSize = 10.sp, color = Color.White)
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF23232A))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Aria RefId 交互", color = Color(0xFF888894), fontSize = 12.sp)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = state.customRefId,
                                            onValueChange = { viewModel.dispatch(BrowserIntent.ChangeRefId(it)) },
                                            label = { Text("RefId", fontSize = 10.sp) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF2E2E36)),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                        )
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { viewModel.dispatch(BrowserIntent.ClickRefId) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))) { Text("点击", fontSize = 12.sp, color = Color.White) }
                                        Button(onClick = { viewModel.dispatch(BrowserIntent.HoverRefId) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1))) { Text("悬停", fontSize = 12.sp, color = Color.White) }
                                        Button(onClick = { viewModel.dispatch(BrowserIntent.ScrollRefId) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D4C41))) { Text("滚动", fontSize = 12.sp, color = Color.White) }
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = state.coordDeltaX,
                                            onValueChange = { viewModel.dispatch(BrowserIntent.ChangeCoordDeltaX(it)) },
                                            label = { Text("ΔX", fontSize = 10.sp) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF2E2E36)),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                        )
                                        OutlinedTextField(
                                            value = state.coordDeltaY,
                                            onValueChange = { viewModel.dispatch(BrowserIntent.ChangeCoordDeltaY(it)) },
                                            label = { Text("ΔY", fontSize = 10.sp) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF2E2E36)),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                        )
                                        Button(
                                            onClick = { viewModel.dispatch(BrowserIntent.ScrollCoordinates(state.coordX.toIntOrNull() ?: 0, state.coordY.toIntOrNull() ?: 0, state.coordDeltaX.toIntOrNull() ?: 0, state.coordDeltaY.toIntOrNull() ?: 0)) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D4C41))
                                        ) { Text("滚动", fontSize = 12.sp, color = Color.White) }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = state.dragEndX,
                                            onValueChange = { viewModel.dispatch(BrowserIntent.ChangeDragEndX(it)) },
                                            label = { Text("终点X", fontSize = 10.sp) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF2E2E36)),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                        )
                                        OutlinedTextField(
                                            value = state.dragEndY,
                                            onValueChange = { viewModel.dispatch(BrowserIntent.ChangeDragEndY(it)) },
                                            label = { Text("终点Y", fontSize = 10.sp) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF2E2E36)),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                        )
                                        Button(
                                            onClick = { viewModel.dispatch(BrowserIntent.DragCoordinates(state.coordX.toIntOrNull() ?: 0, state.coordY.toIntOrNull() ?: 0, state.dragEndX.toIntOrNull() ?: 0, state.dragEndY.toIntOrNull() ?: 0)) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFAD1457))
                                        ) { Text("拖拽", fontSize = 12.sp, color = Color.White) }
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF23232A))
                                // 物理键盘模拟
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("物理键盘模拟", color = Color(0xFF888894), fontSize = 12.sp)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = state.keyboardInputText,
                                            onValueChange = { viewModel.dispatch(BrowserIntent.ChangeKeyboardInput(it)) },
                                            placeholder = { Text("输入要打字的文本", fontSize = 11.sp) },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF2E2E36)),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                                        )
                                        Button(
                                            onClick = { viewModel.dispatch(BrowserIntent.SimulateTypeString) },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                                        ) { Text("打字输入", fontSize = 12.sp, color = Color.White) }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.dispatch(BrowserIntent.SimulateCtrlA) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(vertical = 4.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))
                                        ) { Text("Ctrl+A", fontSize = 10.sp, color = Color.White) }
                                        Button(
                                            onClick = { viewModel.dispatch(BrowserIntent.SimulateCmdA) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(vertical = 4.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))
                                        ) { Text("Cmd+A", fontSize = 10.sp, color = Color.White) }
                                        Button(
                                            onClick = { viewModel.dispatch(BrowserIntent.SimulateBackspace) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(vertical = 4.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4E342E))
                                        ) { Text("退格", fontSize = 10.sp, color = Color.White) }
                                        Button(
                                            onClick = { viewModel.dispatch(BrowserIntent.SimulateEnter) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(vertical = 4.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
                                        ) { Text("Enter", fontSize = 10.sp, color = Color.White) }
                                        Button(
                                            onClick = { viewModel.dispatch(BrowserIntent.SimulateEscape) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(vertical = 4.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A148C))
                                        ) { Text("Escape", fontSize = 10.sp, color = Color.White) }
                                        Button(
                                            onClick = { viewModel.dispatch(BrowserIntent.SimulateTab) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(vertical = 4.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006064))
                                        ) { Text("Tab", fontSize = 10.sp, color = Color.White) }
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF23232A))
                                // 会话管理
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("会话管理", color = Color(0xFF888894), fontSize = 12.sp)
                                    Button(
                                        onClick = { viewModel.dispatch(BrowserIntent.ClearCacheAndCookies) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                                    ) {
                                        Text("清除缓存和Cookie", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { viewModel.dispatch(BrowserIntent.LockInteraction) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
                                        ) {
                                            Text("🔒 锁定交互", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Button(
                                            onClick = { viewModel.dispatch(BrowserIntent.UnlockInteraction) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                        ) {
                                            Text("🔓 解锁交互", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                        1 -> { SearchableDataView(state.snapshotText, state.snapshotSearchQuery, "暂无Aria数据", onQueryChange = { viewModel.dispatch(BrowserIntent.ChangeSnapshotSearch(it)) }) }
                        2 -> { DataView(state.selectorsText, "暂无选择器数据") }
                        3 -> { DataView(state.htmlPreview, "暂无HTML数据") }
                        4 -> { ScreenshotPreview(state.screenshotBytes, state.screenshotAxTree) }
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
fun SearchableDataView(text: String, query: String, emptyMessage: String, onQueryChange: (String) -> Unit) {
    val lines = remember(text) { if (text.isBlank()) emptyList() else text.lines() }
    val q = query.lowercase().trim()
    val matchIndices = remember(lines, q) {
        if (q.isBlank()) emptyList()
        else lines.indices.filter { lines[it].lowercase().contains(q) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(q) {
        if (matchIndices.isNotEmpty()) listState.animateScrollToItem(matchIndices.first())
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("搜索节点（role / text / refid / class...）", fontSize = 11.sp, color = Color(0xFF666672)) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(44.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color(0xFFE0E0E0), fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6C63FF),
                    unfocusedBorderColor = Color(0xFF3A3A4A),
                    cursorColor = Color(0xFF6C63FF),
                    focusedContainerColor = Color(0xFF1A1A22),
                    unfocusedContainerColor = Color(0xFF1A1A22)
                ),
                shape = RoundedCornerShape(6.dp),
                leadingIcon = { Text("🔍", fontSize = 14.sp) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                            Text("✕", fontSize = 12.sp, color = Color(0xFF888894))
                        }
                    }
                }
            )
            if (q.isNotBlank()) {
                Text(
                    "${matchIndices.size}处",
                    fontSize = 11.sp,
                    color = if (matchIndices.isEmpty()) Color(0xFFE57373) else Color(0xFF81C784),
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F0F12)).padding(8.dp)) {
            if (lines.isEmpty()) {
                Text(emptyMessage, color = Color(0xFF888894), fontSize = 12.sp)
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(lines.size) { idx ->
                        val line = lines[idx]
                        val isMatch = q.isNotBlank() && line.lowercase().contains(q)
                        Text(
                            text = line,
                            color = if (isMatch) Color(0xFFFFFFFF) else Color(0xFF81C784),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = if (isMatch)
                                Modifier.fillMaxWidth().background(Color(0x336C63FF)).padding(horizontal = 2.dp)
                            else
                                Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

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

@Composable
fun ScreenshotPreview(bytes: ByteArray?, axTree: xyz.kbrowser.webview.AxTreeData? = null) {
    val bitmap = remember(bytes) {
        if (bytes == null) null else {
            try { makeImageBitmap(bytes) } catch (e: Exception) { null }
        }
    }

    var showOverlay by remember { mutableStateOf(true) }
    var onlyVisible by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F0F12))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            ) {
                // 工具栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("截图: ${bitmap.width}×${bitmap.height}", color = Color(0xFF81C784), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    if (axTree != null) {
                        val nodeCount = if (onlyVisible) axTree.nodes.count { it.isVisible } else axTree.nodes.size
                        Text("节点: $nodeCount", color = Color(0xFF64B5F6), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showOverlay, onCheckedChange = { showOverlay = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF6C63FF)))
                        Text("显示框框", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = onlyVisible, onCheckedChange = { onlyVisible = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF6C63FF)))
                        Text("仅可见", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                    }
                }

                // 截图 + 叠加框
                Box(modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF2E2E36))) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = "screenshot",
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (showOverlay && axTree != null) {
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                        ) {
                            val scaleX = size.width / bitmap.width
                            val scaleY = size.height / bitmap.height
                            val nodes = if (onlyVisible) axTree.nodes.filter { it.isVisible } else axTree.nodes
                            nodes.forEach { node ->
                                if (node.width <= 0 || node.height <= 0) return@forEach
                                val color = when (node.role.lowercase()) {
                                    "button" -> androidx.compose.ui.graphics.Color(0xFFFF5252)
                                    "link" -> androidx.compose.ui.graphics.Color(0xFFFF8A65)
                                    "textbox", "combobox", "searchbox" -> androidx.compose.ui.graphics.Color(0xFF40C4FF)
                                    "heading" -> androidx.compose.ui.graphics.Color(0xFFFFD740)
                                    "img" -> androidx.compose.ui.graphics.Color(0xFF69F0AE)
                                    "listitem", "menuitem" -> androidx.compose.ui.graphics.Color(0xFFE040FB)
                                    "statictext" -> androidx.compose.ui.graphics.Color(0x88FFFFFF)
                                    else -> androidx.compose.ui.graphics.Color(0x6600BCD4)
                                }
                                drawRect(
                                    color = color,
                                    topLeft = androidx.compose.ui.geometry.Offset(node.x * scaleX, node.y * scaleY),
                                    size = androidx.compose.ui.geometry.Size(node.width * scaleX, node.height * scaleY),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                                )
                            }
                        }
                    }
                }

                // 图例
                if (showOverlay) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(
                            "button" to Color(0xFFFF5252), "link" to Color(0xFFFF8A65),
                            "input" to Color(0xFF40C4FF), "heading" to Color(0xFFFFD740),
                            "img" to Color(0xFF69F0AE), "listitem" to Color(0xFFE040FB),
                            "text" to Color(0x88FFFFFF), "其他" to Color(0xFF00BCD4)
                        ).forEach { (label, color) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Box(Modifier.size(9.dp).background(color))
                                Text(label, color = Color(0xFFAAAAAA), fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无截图数据，请在 [控制] Tab 中点击 [测试网页截图]", color = Color(0xFF888894), fontSize = 12.sp)
            }
        }
    }
}