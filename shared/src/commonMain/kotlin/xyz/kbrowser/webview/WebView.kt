package xyz.kbrowser.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun WebView(
    controller: WebViewController,
    modifier: Modifier = Modifier
)

@Composable
expect fun rememberWebViewController(initialUrl: String = "about:blank", isOsr: Boolean = false): WebViewController
