package xyz.kbrowser.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
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

@Composable
internal fun MultiLanguageFontTest(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F12))
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E36))
            ) { Text("Back", color = Color.White) }
            Spacer(Modifier.width(16.dp))
            Text(
                text = "Multi-Language Font Test",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(Modifier.height(24.dp))

        val samples = listOf(
            FontSample("Simplified Chinese", "你好世界，欢迎使用 KBrowser", Color(0xFF64B5F6)),
            FontSample("Traditional Chinese", "你好世界，歡迎使用 KBrowser", Color(0xFF81C784)),
            FontSample("Arabic", "مرحبا بالعالم", Color(0xFFFFB74D)),
            FontSample("Hebrew", "שלום עולם", Color(0xFFCE93D8)),
            FontSample("Thai", "สวัสดีชาวโลก", Color(0xFFEF9A9A)),
            FontSample("Japanese", "こんにちは世界", Color(0xFF80DEEA)),
            FontSample("Korean", "안녕하세요 세계", Color(0xFFA5D6A7)),
            FontSample("Hindi", "नमस्ते दुनिया", Color(0xFFFFCC80)),
            FontSample("Russian", "Привет мир", Color(0xFF90CAF9)),
            FontSample("English", "Hello World", Color(0xFFE0E0E0))
        )

        samples.forEach { sample ->
            FontSampleCard(sample)
            Spacer(Modifier.height(12.dp))
        }
    }
}

private data class FontSample(
    val language: String,
    val text: String,
    val accentColor: Color
)

@Composable
private fun FontSampleCard(sample: FontSample) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF16161A))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(sample.accentColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = sample.language,
                fontSize = 12.sp,
                color = Color(0xFF888894),
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = sample.text,
            fontSize = 24.sp,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}
