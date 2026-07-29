package xyz.kbrowser.webview

import xyz.kbrowser.jcef.KBCefApp
import xyz.kbrowser.jcef.KBCefBrowser
import xyz.kbrowser.jcef.KBCefBrowserBuilder
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

fun main() {
    System.setProperty("jcef.chrome.runtime.enabled", "false")

    val url = System.getProperty(
        "kb.osr.test.url",
        "about:blank"
    )
    val holdSeconds = Integer.getInteger("kb.osr.test.holdSeconds", 300)
    val useOsr = System.getProperty("kb.osr.test.osr", "false").toBoolean()

    println("========================================================")
    println("  KBCefBrowser Fullscreen Test (OSR=$useOsr)")
    println("  URL: $url")
    println("  Hold: ${holdSeconds}s")
    println("========================================================")

    val storageDir = System.getProperty("user.home") + "/.browserpilot/jcef_cache"
    KBrowser.initializeConfig(storageDir, useOsr = useOsr)
    runBlocking { initializeKBrowser() }
    println("[Test] KBCefApp initialized (useOsr=$useOsr)")

    val isMac = System.getProperty("os.name").lowercase().contains("mac")
    val builder = KBCefBrowserBuilder()
        .setUrl(url)
        .setOffScreenRendering(useOsr)
    if (isMac) builder.myMouseWheelEventEnable = false
    val cefBrowser = KBCefBrowser(builder)
    println("[Test] KBCefBrowser created, OSR=$useOsr")

    SwingUtilities.invokeLater {
        val frame = JFrame("KBCefBrowser Fullscreen Test (OSR=$useOsr)").apply {
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            contentPane.layout = BorderLayout()
            contentPane.add(cefBrowser.getComponent(), BorderLayout.CENTER)
        }

        val gd: GraphicsDevice = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        if (gd.isFullScreenSupported) {
            gd.fullScreenWindow = frame
        } else {
            frame.extendedState = JFrame.MAXIMIZED_BOTH
            frame.isVisible = true
        }

        println("[Test] Window visible — fullscreen")

        SwingUtilities.invokeLater {
            println("\n========== CONTAINER HIERARCHY & BACKGROUND COLORS ==========")
            dumpContainerHierarchy(frame, 0)
            println("=============================================================\n")
        }
    }

    Thread.sleep(holdSeconds * 1000L)
    println("[Test] Done, exiting")
    exitProcess(0)
}

private fun dumpContainerHierarchy(comp: Component, depth: Int) {
    val indent = "  ".repeat(depth)
    val bg = try { comp.background } catch (_: Exception) { null }
    val opaque = if (comp is Container) comp.isOpaque else false
    val bgStr = bg?.let {
        "bg=RGB(${it.red},${it.green},${it.blue}) alpha=${it.alpha}" +
            if (comp is Container) " ${if (opaque) "OPAQUE" else "transparent"}" else ""
    } ?: "bg=null"
    val sizeStr = "${comp.width}x${comp.height}"
    val classStr = comp.javaClass.name.substringAfterLast('.')
    println("$indent$classStr [$sizeStr] $bgStr")

    if (comp is Container) {
        for (child in comp.components) {
            dumpContainerHierarchy(child, depth + 1)
        }
    }
}
