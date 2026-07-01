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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import xyz.kbrowser.webview.KBWebView
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.initializeKBrowser
import xyz.kbrowser.webviewdemo.*

@Composable
fun MobileApp() {
    var isInitialized by remember { mutableStateOf(false) }
    var isInitializing by remember { mutableStateOf(false) }

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
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "正在初始化...",
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    LaunchedEffect(Unit) {
                        isInitializing = true
                        val storageDir = getDefaultStorageDir()
                        KBrowser.initializeConfig(storageDir, useOsr = false)
                        initializeKBrowser()
                        isInitialized = true
                        isInitializing = false
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 3.dp
                        )
                    }
                }
            } else {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = HomeRoute) {
                    composable<HomeRoute> {
                        MobileHomeScreen(
                            onNavigateToBrowser = { navController.navigate(BrowserExampleRoute) },
                            onNavigateToWebView = { navController.navigate(WebViewDemoHomeRoute) }
                        )
                    }
                    composable<BrowserExampleRoute> {
                        val viewModel = remember { BrowserViewModel() }
                        DisposableEffect(Unit) {
                            onDispose { viewModel.state.value.page?.close() }
                        }
                        MobileBrowserExampleScreen(
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
            }
        }
    }
}

@Composable
fun MobileHomeScreen(
    onNavigateToBrowser: () -> Unit,
    onNavigateToWebView: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
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
            MobileHomeTileCard(
                title = "KBWebView 组件演示",
                description = "KBrowser 的核心价值：Compose Multiplatform 生态里缺失的 WebView 组件。完整展示所有 API 的使用方式，可直接作为集成样板代码参考。",
                iconColor = Color(0xFF81C784),
                buttonText = "查看 WebView API 样板",
                onClick = onNavigateToWebView
            )
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

@Composable
fun MobileBrowserExampleScreen(
    viewModel: BrowserViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var isConsoleExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF16161A))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text(text = "←", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "浏览器自动化演示", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

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
                    PrimaryScrollableTabRow(
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
