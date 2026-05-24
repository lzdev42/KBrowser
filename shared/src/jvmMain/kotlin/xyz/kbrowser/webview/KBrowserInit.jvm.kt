package xyz.kbrowser.webview

actual fun initializeKBrowser() {
    // 强制关闭新版 JCEF 的 Chrome Runtime 原生窗口模式，使用纯渲染 Alloy/OSR 模式
    // 注意：由于 JBR 内部和 macOS AppKit 的限制，这几个系统属性必须在 AWT 和 Compose
    // 初始化之前被设置。如果在创建窗口时才懒加载设置，会导致 SIGSEGV 致命崩溃！
    System.setProperty("jcef.chrome.runtime.enabled", "false")
    System.setProperty("jcef.chrome.runtime", "false")
    System.setProperty("jcef.osr.enabled", "false")

    // 强制在 AWT 和 Compose 启动前初始化 JCEF 核心，避免稍后在 UI 线程中懒加载导致的各种崩溃
    try {
        xyz.kbrowser.jcef.KBCefApp.getInstance()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
