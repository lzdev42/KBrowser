package xyz.kbrowser.jcef

import com.jetbrains.cef.JCefAppConfig
import org.cef.CefApp
import org.cef.CefSettings
import org.cef.OS
import org.cef.SystemBootstrap
import org.cef.handler.CefAppHandlerAdapter
import java.util.concurrent.atomic.AtomicBoolean

class KBCefApp private constructor(val config: JCefAppConfig, storageDir: String) : Disposable {
    private val myCefApp: CefApp
    private val myCefSettings: CefSettings

    companion object {
        private val ourInitialized = AtomicBoolean(false)
        private var ourInstance: KBCefApp? = null

        @Synchronized
        @Suppress("DEPRECATION")
        fun getInstance(storageDir: String): KBCefApp {
            if (ourInstance == null) {
                val config = try {
                    if (System.getProperty("jcef.forceDeviceScaleFactor") == null) {
                        val scale = JCefAppConfig.getForceDeviceScaleFactor()
                        if (scale > 0) {
                            System.setProperty("jcef.forceDeviceScaleFactor", scale.toString())
                        }
                    }
                    JCefAppConfig.getInstance()
                } catch (t: Throwable) {
                    throw IllegalStateException(
                        "JetBrains Runtime (JBR) with JCEF module is required but not detected. " +
                        "Please check your JDK configuration.", t
                    )
                }
                ourInstance = KBCefApp(config, storageDir)
            }
            return ourInstance!!
        }

        @Synchronized
        fun getInstance(): KBCefApp {
            return ourInstance ?: throw IllegalStateException("KBCefApp is not initialized. Call getInstance(storageDir) first.")
        }

        fun isSupported(): Boolean {
            // IDEA does complex GLIBC checks on Linux here.
            // For now, we assume JBR-JCEF is compatible since user is running it.
            return true 
        }
    }

    init {
        ourInitialized.set(true)
        SystemBootstrap.setLoader(config.loader)

        try {
            val isRemote = try {
                val method = config.javaClass.getMethod("isRemoteEnabled")
                method.invoke(config) as Boolean
            } catch (e: Exception) {
                // JCefAppConfig 没有 isRemoteEnabled 方法时，默认开启 remote
                // Remote 模式是零拷贝 OSR 的前提条件（SharedMemory + NativeRasterLoader）
                true
            }
            // 启用/禁用 Remote 模式和离屏渲染
            val useOsr = xyz.kbrowser.webview.KBrowser.useOsrMode
            CefApp.setIsRemoteEnabled(useOsr)
            println("[KBCefApp] Set CefApp.setIsRemoteEnabled to: $useOsr (config reported: $isRemote)")
        } catch (e: Throwable) {
            println("[KBCefApp] Failed to set remote mode: ${e.message}")
        }

        val macCefFrameworkPathOSX = config.cefFrameworkPathOSX
        val settings = config.cefSettings
        
        // JCEF Settings
        settings.windowless_rendering_enabled = xyz.kbrowser.webview.KBrowser.useOsrMode
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_INFO

        // 默认背景色黑色（在网页渲染前 CEF 使用的底色）
        settings.background_color = settings.ColorType(0, 0, 0, 255)
        
        // Ensure Alloy rendering mode by disabling Chrome Runtime
        try {
            val chromeRuntimeField = settings.javaClass.getField("chrome_runtime")
            chromeRuntimeField.set(settings, false)
        } catch (e: Exception) {
            // 忽略找不到字段或设置失败的情况
        }
        
        // Disable remote debugging port by default
        settings.remote_debugging_port = 0
        
        // Set explicit cache path using the provided storageDir
        settings.cache_path = storageDir
        
        val args = config.appArgs.toMutableList()
        println("[KBCefApp] Raw Args from Config: $args")
        println("[KBCefApp] Server Exe: ${config.serverExe}")
        
        // Ensure standard IDEA args are present
        if (!args.contains("--autoplay-policy=no-user-gesture-required")) {
            args.add("--autoplay-policy=no-user-gesture-required")
        }
        if (!args.contains("--disable-component-update")) {
            args.add("--disable-component-update")
        }
        // Disable navigator.webdriver control flag
        if (!args.any { it.startsWith("--disable-features") && it.contains("AutomationControl") }) {
            args.add("--disable-features=AutomationControl")
        }
        
        args.add("--disable-chrome-runtime")

        println("[KBCefApp] Adding app handler...")
        System.out.flush()
        CefApp.addAppHandler(object : CefAppHandlerAdapter(args.toTypedArray()) {
            override fun onRegisterCustomSchemes(registrar: org.cef.callback.CefSchemeRegistrar) {
                registrar.addCustomScheme(
                    KBCefHtmlSchemeHandlerFactory.HTML_SCHEME_NAME,
                    true, true, false, false, false, false, false
                )
            }

            override fun onContextInitialized() {
                try {
                    CefApp.getInstance().registerSchemeHandlerFactory(
                        KBCefHtmlSchemeHandlerFactory.HTML_SCHEME_NAME,
                        "",
                        KBCefHtmlSchemeHandlerFactory()
                    )
                } catch (e: Exception) {
                    println("[KBCefApp] Failed to register HTML scheme handler factory: ${e.message}")
                }
                println("[KBCefApp] Context Initialized")
                System.out.flush()
            }
            
            override fun onBeforeChildProcessLaunch(commandLine: String?) {
                if (commandLine?.contains("--type=gpu-process") == true) {
                    println("[KBCefApp] Launching GPU process...")
                    System.out.flush()
                }
            }
        })

        // Platform specific startup is required on macOS to load the framework path
        if (OS.isMacintosh() && macCefFrameworkPathOSX != null) {
            println("[KBCefApp] MacOS: Calling startupAsync with $macCefFrameworkPathOSX")
            System.out.flush()
            CefApp.startupAsync(macCefFrameworkPathOSX)
            println("[KBCefApp] MacOS: startupAsync returned")
            System.out.flush()
        } else {
            println("[KBCefApp] Other OS: Calling startup...")
            System.out.flush()
            CefApp.startup(args.toTypedArray())
            println("[KBCefApp] Other OS: startup returned")
            System.out.flush()
        }

        myCefSettings = settings
        println("[KBCefApp] Calling CefApp.getInstance...")
        System.out.flush()
        
        // Fix: Pass args to getInstance
        myCefApp = CefApp.getInstance(args.toTypedArray(), settings, config.serverExe)
        println("[KBCefApp] CefApp.getInstance returned successfully!")
        System.out.flush()
    }

    fun createClient(): KBCefClient {
        return KBCefClient(myCefApp.createClient())
    }

    override fun dispose() {
        myCefApp.dispose()
    }
}
