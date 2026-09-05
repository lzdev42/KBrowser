package xyz.kbrowser.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F12)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "KBrowser WasmJs Demo",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "你好世界 مرحبا Привет こんにちは",
                    fontSize = 18.sp,
                    color = Color(0xFF888894)
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Missing glyphs are auto-downloaded by Compose Multiplatform 1.12+",
                    fontSize = 12.sp,
                    color = Color(0xFF4E4E5A)
                )
            }
        }
    }
}
