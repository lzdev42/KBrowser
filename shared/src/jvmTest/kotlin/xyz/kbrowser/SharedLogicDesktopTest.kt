package xyz.kbrowser

import kotlin.test.Test

class SharedLogicDesktopTest {

    @Test
    fun example() {
        println("=== JCEF REFLECTION INSPECTION ===")
        val classesToInspect = listOf(
            "org.cef.handler.CefRequestContextHandler",
            "org.cef.browser.CefRequestContext",
            "com.jetbrains.cef.JCefAppConfig"
        )
        for (className in classesToInspect) {
            try {
                val clazz = Class.forName(className)
                println("Class: ${clazz.name}")
                println("  Constructors:")
                clazz.declaredConstructors.forEach {
                    println("    $it")
                }
                println("  Methods:")
                clazz.declaredMethods.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) || java.lang.reflect.Modifier.isPublic(it.modifiers) }.forEach {
                    println("    $it")
                }
                println("  Fields:")
                clazz.declaredFields.forEach {
                    println("    $it")
                }
            } catch (e: Exception) {
                println("Class $className not found: ${e.message}")
            }
        }
    }
}