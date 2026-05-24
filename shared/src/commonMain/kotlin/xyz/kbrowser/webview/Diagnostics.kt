package xyz.kbrowser.webview

data class Diagnostics(
    val errorCode: Int,
    val description: String,
    val failingUrl: String
)
