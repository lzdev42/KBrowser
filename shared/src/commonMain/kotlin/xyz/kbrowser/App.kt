package xyz.kbrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.kbrowser.webview.WebView
import xyz.kbrowser.webview.rememberWebViewController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    var isOsrMode by remember { mutableStateOf(true) }
    var maskEnabled by remember { mutableStateOf(false) }
    val controller = rememberWebViewController("https://www.zhipin.com/", isOsr = isOsrMode)
    val viewModel: BrowserViewModel = viewModel { BrowserViewModel() }

    val logs by viewModel.logs.collectAsState()
    val snapshotText by viewModel.snapshotText.collectAsState()
    val selectorsText by viewModel.selectorsText.collectAsState()
    val htmlPreview by viewModel.htmlPreview.collectAsState()

    var customSelector by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    var navigateUrlInput by remember { mutableStateOf("https://www.zhipin.com/") }
    var coordX by remember { mutableStateOf("250") }
    var coordY by remember { mutableStateOf("450") }

    // 遮罩开关变化时，通知controller
    LaunchedEffect(maskEnabled) {
        controller.setMaskEnabled(maskEnabled)
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF64B5F6),
            background = Color(0xFF121214),
            surface = Color(0xFF1E1E24)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // ========== 左侧：浏览器区域 (60%) ==========
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .border(1.dp, if (maskEnabled) Color(0xFFFF4500) else Color(0xFF2E2E36))
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
                        value = navigateUrlInput,
                        onValueChange = { navigateUrlInput = it },
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
                        onClick = {
                            if (navigateUrlInput.isNotBlank()) {
                                controller.loadUrl(navigateUrlInput)
                                viewModel.log("导航至: $navigateUrlInput")
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("跳转", fontSize = 13.sp)
                    }
                }

                // WebView (包含内置的AWT遮罩层)
                Box(modifier = Modifier.fillMaxSize()) {
                    WebView(
                        controller = controller,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // ========== 右侧：控制调试面板 (40%) ==========
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
                        checked = isOsrMode,
                        onCheckedChange = { isOsrMode = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = Color(0xFF888894)
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("OSR", color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Checkbox(
                        checked = maskEnabled,
                        onCheckedChange = { maskEnabled = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFFFF4500),
                            uncheckedColor = Color(0xFF888894)
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (maskEnabled) "遮罩 ON" else "遮罩 OFF",
                        color = if (maskEnabled) Color(0xFFFF4500) else Color(0xFF888894),
                        fontSize = 12.sp,
                        fontWeight = if (maskEnabled) FontWeight.Bold else FontWeight.Normal
                    )
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
                        TextButton(onClick = { viewModel.clearLogs() }, contentPadding = PaddingValues(0.dp)) {
                            Text("Clear", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(logs) { logMsg ->
                            Text(
                                text = logMsg,
                                color = if (logMsg.contains("成功") || logMsg.contains("完毕")) Color(0xFF81C784) else Color(0xFFE0E0E0),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tab
                TabRow(selectedTabIndex = selectedTab, containerColor = Color(0xFF16161A), contentColor = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("控制", fontSize = 13.sp) })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Aria", fontSize = 13.sp) })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("选择器", fontSize = 13.sp) })
                    Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("HTML", fontSize = 13.sp) })
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Tab内容
                Box(modifier = Modifier.weight(0.6f).fillMaxWidth()) {
                    when (selectedTab) {
                        0 -> {
                            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Button(onClick = { viewModel.runAutoFlow(controller) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), shape = RoundedCornerShape(8.dp)) {
                                    Text("一键测试自动化链", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { viewModel.fetchHtml(controller) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E36))) { Text("HTML", fontSize = 12.sp, color = Color.White) }
                                    Button(onClick = { viewModel.fetchSemanticSnapshot(controller) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E36))) { Text("Aria", fontSize = 12.sp, color = Color.White) }
                                    Button(onClick = { viewModel.fetchSelectors(controller) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E36))) { Text("选择器", fontSize = 12.sp, color = Color.White) }
                                }
                                Divider(color = Color(0xFF23232A))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("CSS 选择器点击", color = Color(0xFF888894), fontSize = 12.sp)
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(value = customSelector, onValueChange = { customSelector = it }, placeholder = { Text(".btn-login", fontSize = 12.sp) }, modifier = Modifier.weight(1f), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF2E2E36)))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(onClick = { viewModel.clickSelector(controller, customSelector) }, shape = RoundedCornerShape(8.dp)) { Text("点击", fontSize = 13.sp) }
                                    }
                                }
                                Divider(color = Color(0xFF23232A))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("坐标交互", color = Color(0xFF888894), fontSize = 12.sp)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(value = coordX, onValueChange = { coordX = it }, label = { Text("X", fontSize = 10.sp) }, modifier = Modifier.weight(1f), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF2E2E36)))
                                        OutlinedTextField(value = coordY, onValueChange = { coordY = it }, label = { Text("Y", fontSize = 10.sp) }, modifier = Modifier.weight(1f), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF2E2E36)))
                                    }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { viewModel.clickCoordinates(controller, coordX.toIntOrNull() ?: 0, coordY.toIntOrNull() ?: 0) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))) { Text("点击", fontSize = 12.sp, color = Color.White) }
                                        Button(onClick = { viewModel.hoverCoordinates(controller, coordX.toIntOrNull() ?: 0, coordY.toIntOrNull() ?: 0) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1))) { Text("悬停", fontSize = 12.sp, color = Color.White) }
                                    }
                                }
                            }
                        }
                        1 -> { DataView(snapshotText, "暂无Aria数据") }
                        2 -> { DataView(selectorsText, "暂无选择器数据") }
                        3 -> { DataView(htmlPreview, "暂无HTML数据") }
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