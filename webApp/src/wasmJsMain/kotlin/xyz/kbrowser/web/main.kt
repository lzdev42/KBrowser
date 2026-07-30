package xyz.kbrowser.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import xyz.kbrowser.WithFontResourcesLoaded
import xyz.kbrowser.FontMode

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        WithFontResourcesLoaded {
            WebApp()
        }
    }
}

@Composable
private fun WebApp() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    when (screen) {
        Screen.Home -> HomeScreen(
            onFontTest = { screen = Screen.FontTest }
        )
        Screen.FontTest -> MultiLanguageFontTest(
            onBack = { screen = Screen.Home }
        )
    }
}

private sealed class Screen {
    data object Home : Screen()
    data object FontTest : Screen()
}

@Composable
private fun HomeScreen(onFontTest: () -> Unit) {
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
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onFontTest,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A90D9)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Multi-Language Font Test", color = Color.White)
            }
        }
    }
}
