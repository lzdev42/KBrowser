package xyz.kbrowser

import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefRequestContext
import org.cef.network.CefCookieManager
import java.lang.reflect.Method

fun main() {
    println("--- CefRequestContext Methods ---")
    try {
        CefRequestContext::class.java.methods.map { it.name }.distinct().sorted().forEach { println(it) }
    } catch (e: Exception) {
        println("CefRequestContext NOT FOUND: ${e.message}")
    }

    println("\n--- CefCookieManager Methods ---")
    try {
        CefCookieManager::class.java.methods.map { it.name }.distinct().sorted().forEach { println(it) }
    } catch (e: Exception) {
        println("CefCookieManager NOT FOUND: ${e.message}")
    }
}
