package xyz.kbrowser.webview

/**
 * 初始化 KBrowser 环境。
 * 必须在各平台的 main 函数的最早期（甚至在任何 UI 初始化之前）调用！
 * 否则由于底层引擎加载时机问题（如 JVM 上的 JCEF 和 AWT 锁死），可能会引发严重崩溃。
 */
expect suspend fun initializeKBrowser()
