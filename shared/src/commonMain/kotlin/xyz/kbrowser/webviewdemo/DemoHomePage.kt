package xyz.kbrowser.webviewdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class DemoEntry(
    val title: String,
    val description: String,
    val iconColor: Color
)

private val demoEntries = listOf(
    DemoEntry("基础浏览", "loadUrl + 导航控制 + 状态监听", Color(0xFF64B5F6)),
    DemoEntry("HTML 内容渲染", "loadHtml 实时 Markdown 预览", Color(0xFF81C784)),
    DemoEntry("JS 双向通信", "evaluateJavascript + registerJsCallback + registerJsHandler", Color(0xFFFFB74D)),
    DemoEntry("新窗口与文件处理", "onNewWindowRequest + onFileDialogRequest", Color(0xFF80DEEA)),
    DemoEntry("生命周期回调", "setWebViewClient + setWebChromeClient", Color(0xFFCE93D8)),
    DemoEntry("缓存管理", "clearCacheAndCookies", Color(0xFFEF9A9A)),
    DemoEntry("网页截图", "takeScreenshot 截图预览", Color(0xFF80DEEA))
)

@Composable
fun DemoHomePage(onDemoClick: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F12))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "KBWebView API 演示",
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

        demoEntries.forEachIndexed { index, entry ->
            DemoEntryCard(
                entry = entry,
                index = index,
                onClick = { onDemoClick(index) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "v1.2.0 • KBWebView Demo",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF4E4E5A)
        )
    }
}

@Composable
private fun DemoEntryCard(
    entry: DemoEntry,
    index: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2E2E36), RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(entry.iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(entry.iconColor))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${index + 1}. ${entry.title}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.description,
                    fontSize = 12.sp,
                    color = Color(0xFF9E9EA8),
                    lineHeight = 16.sp
                )
            }
            Text(
                text = "→",
                color = entry.iconColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
