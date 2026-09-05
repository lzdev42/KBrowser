package xyz.kbrowser.webview

/**
 * Initializes the KBrowser environment.
 * Must be called as early as possible in each platform's main function (before any UI
 * initialization)! Otherwise the underlying engine's load timing (e.g. JCEF/AWT deadlock on
 * JVM) can cause severe crashes.
 */
expect suspend fun initializeKBrowser()
