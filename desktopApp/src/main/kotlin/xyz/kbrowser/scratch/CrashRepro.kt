package xyz.kbrowser.scratch

import xyz.kbrowser.jcef.KBCefApp
import xyz.kbrowser.webview.initializeKBrowser
import org.cef.browser.CefRendering

fun main() {
    val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
    xyz.kbrowser.webview.KBrowser.setConfigPath(storageDir)
    kotlinx.coroutines.runBlocking { initializeKBrowser() }
    println("Initializing KBCefApp...")
    val app = KBCefApp.getInstance()
    println("App initialized. Creating client...")
    val client = app.createClient()
    println("Client created. Creating browser...")
    // This simulates what KBCefBrowserBuilder does
    val browser = client.cefClient.createBrowser("https://google.com", CefRendering.DEFAULT, false)
    println("Browser created successfully!")
    System.exit(0)
}
