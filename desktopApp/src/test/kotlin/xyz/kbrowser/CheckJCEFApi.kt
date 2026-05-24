package xyz.kbrowser

import org.cef.browser.CefBrowser
import org.cef.CefClient
import java.lang.reflect.Method

fun main() {
    println("--- CefBrowser Methods ---")
    CefBrowser::class.java.methods.map { it.name }.distinct().sorted().forEach { println(it) }
    
    println("\n--- CefClient Methods ---")
    CefClient::class.java.methods.map { it.name }.distinct().sorted().forEach { println(it) }
    
    try {
        val axClass = Class.forName("org.cef.handler.CefAccessibilityHandler")
        println("\nCefAccessibilityHandler EXISTS!")
        axClass.methods.map { it.name }.distinct().sorted().forEach { println(it) }
    } catch (e: Exception) {
        println("\nCefAccessibilityHandler NOT FOUND")
    }
}
