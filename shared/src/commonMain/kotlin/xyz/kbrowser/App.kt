package xyz.kbrowser

import androidx.compose.animation.*
import kotlinx.coroutines.launch
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import xyz.kbrowser.webview.KBWebView
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import xyz.kbrowser.webview.rememberKBWebView
import xyz.kbrowser.webviewdemo.*

@Serializable object HomeRoute
@Serializable object BrowserExampleRoute
@Serializable object WebViewDemoHomeRoute
@Serializable data class WebViewDemoRoute(val index: Int)

@Composable
fun App() {
    val platformName = remember { getPlatform().name.lowercase() }
    val isMobile = platformName.contains("android") || platformName.contains("ios")

    if (isMobile) {
        MobileApp()
    } else {
        DesktopApp()
    }
}

@Composable
fun DesktopApp() {
    val scope = rememberCoroutineScope()
    var isInitialized by remember { mutableStateOf(false) }
    var isInitializing by remember { mutableStateOf(false) }
    var chosenModeIsOsr by remember { mutableStateOf(true) }

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
            if (!isInitialized) {
                if (isInitializing) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(60.dp),
                                strokeWidth = 4.dp
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "正在加载浏览器内核...",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    InitSelectionScreen(
                        onModeSelected = { useOsr ->
                            isInitializing = true
                            scope.launch {
                                chosenModeIsOsr = useOsr
                                val storageDir = getDefaultStorageDir()
                                KBrowser.initializeConfig(storageDir, useOsr = useOsr)
                                initializeKBrowser()
                                isInitialized = true
                                isInitializing = false
                            }
                        }
                    )
                }
            } else {
                if (chosenModeIsOsr) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = HomeRoute) {
                        composable<HomeRoute> {
                            HomeScreen(
                                onNavigateToBrowser = { navController.navigate(BrowserExampleRoute) },
                                onNavigateToWebView = { navController.navigate(WebViewDemoHomeRoute) }
                            )
                        }
                        composable<BrowserExampleRoute> {
                            val viewModel = remember { BrowserViewModel() }
                            DisposableEffect(Unit) {
                                onDispose { viewModel.state.value.page?.close() }
                            }
                            BrowserExampleScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable<WebViewDemoHomeRoute> {
                            DemoHomePage(
                                onDemoClick = { index -> navController.navigate(WebViewDemoRoute(index)) }
                            )
                        }
                        composable<WebViewDemoRoute> { backStackEntry ->
                            val route = backStackEntry.toRoute<WebViewDemoRoute>()
                            when (route.index) {
                                0 -> BasicBrowsingDemo(onBack = { navController.popBackStack() })
                                1 -> HtmlContentDemo(onBack = { navController.popBackStack() })
                                2 -> JsCommunicationDemo(onBack = { navController.popBackStack() })
                                3 -> NewWindowAndFileDemo(onBack = { navController.popBackStack() })
                                4 -> LifecycleCallbacksDemo(onBack = { navController.popBackStack() })
                                5 -> CacheManagementDemo(onBack = { navController.popBackStack() })
                                6 -> ScreenshotDemo(onBack = { navController.popBackStack() })
                                else -> DemoHomePage(onDemoClick = { navController.navigate(WebViewDemoRoute(it)) })
                            }
                        }
                    }
                } else {
                    PureNonOsrWebViewScreen()
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
                title = "KBWebView 组件演示",
                description = "跨平台 WebView 组件的完整 API 演示：加载、导航、JS 通信、生命周期回调、文件处理等，可直接作为集成样板代码参考。",
                iconColor = Color(0xFF81C784),
                buttonText = "查看组件演示",
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

        // 用 Box 包裹整个内容区，让悬浮卡片能提升到 SwingPanel 父级之外
        // 这是 compose.interop.blending=true 下让 Compose 覆盖层正常响应事件的关键：
        // 覆盖层不能是 SwingPanel 直接父级 Box 的子节点，否则事件会穿透到 Swing 层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
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

                // WebView 挂载区（纯 SwingPanel，不在此处叠加任何 Compose 视图）
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
        } // end Row

        // ── Compose 混合渲染实验：悬浮卡片 ──────────────────────────────────────
        // 关键：放在 Row（含 SwingPanel）的同级 Box 里，而不是 SwingPanel 的直接父级 Box 里。
        // compose.interop.blending=true 下，事件穿透只发生在 SwingPanel 直接父级的 Box 内；
        // 提升到这一层后，Compose 覆盖层可以正常接收鼠标/点击事件。
        if (state.page != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xEE1A1A1E))
                    .border(1.dp, Color(0xFF3A3A42), RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .width(240.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "⚡️ Compose 混合渲染实验",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "此悬浮卡片为纯 Compose 视图，覆盖在 JCEF 浏览器上层，且可正常响应点击事件。",
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
                        Text(
                            "点击此 Compose 按钮",
                            fontSize = 11.sp,
                            color = Color(0xFF121214),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        } // end outer Box
    }
}


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

@Composable
fun PureNonOsrWebViewScreen() {
    val webView = rememberKBWebView("https://webglsamples.org/aquarium/aquarium.html", null)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Color(0xFF1E1E24))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "非 OSR 模式 (WebGL Aquarium)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "系统原生窗口渲染 - 无法覆盖 Compose UI",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
        KBWebView(
            webView = webView,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun InitSelectionScreen(onModeSelected: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "KBrowser 渲染模式抉择",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp
            ),
            color = Color(0xFFE3F2FD),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Text(
            text = "请选择最契合您应用场景的底层渲染模式，启动后不可切换",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 40.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(0.85f),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onModeSelected(true) }
                    .border(1.dp, Brush.linearGradient(listOf(Color(0xFF64B5F6), Color(0xFF00E5FF))), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2129))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1565C0), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "推荐 / 易开发",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "OSR (离屏渲染) 模式",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "• 完美融入 Compose 视图树\n• 支持叠加任意 Compose 弹窗与 UI 元素\n• 采用零拷贝极速内存共享绘制\n• 适用于需要复杂界面混排的应用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB0BEC5),
                        lineHeight = 22.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { onModeSelected(true) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                    ) {
                        Text("以 OSR 模式启动", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Card(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onModeSelected(false) }
                    .border(1.dp, Brush.linearGradient(listOf(Color(0xFFB388FF), Color(0xFFEA80FC))), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2129))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF6A1B9A), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "极致性能",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Non-OSR (原生窗口) 模式",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "• 独占系统底层物理窗口进行绘制\n• 强悍的三维渲染性能 (支持 WebGL 满帧)\n• 不支持直接在其上叠加 Compose 元素\n• 适用于独立的游戏、大屏数据可视化等",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB0BEC5),
                        lineHeight = 22.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { onModeSelected(false) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E24AA))
                    ) {
                        Text("以 非 OSR 模式启动", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}