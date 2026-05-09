package xyz.kbrowser.jcef

object SystemInfo {
    val isMac: Boolean = System.getProperty("os.name").contains("Mac", ignoreCase = true)
    val isWindows: Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true)
    val isLinux: Boolean = System.getProperty("os.name").contains("Linux", ignoreCase = true)
}
