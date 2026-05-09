package xyz.kbrowser.jcef

import com.jetbrains.cef.JCefAppConfig
import org.cef.CefApp
import org.cef.CefSettings
import org.cef.OS
import org.cef.SystemBootstrap
import org.cef.handler.CefAppHandlerAdapter
import java.util.concurrent.atomic.AtomicBoolean

class KBCefApp private constructor(val config: JCefAppConfig) : Disposable {
    private val myCefApp: CefApp
    private val myCefSettings: CefSettings

    companion object {
        private val ourInitialized = AtomicBoolean(false)
        private var ourInstance: KBCefApp? = null

        @Synchronized
        fun getInstance(): KBCefApp {
            if (ourInstance == null) {
                // IDEA Logic: Set forceDeviceScaleFactor if JRE HiDPI is disabled
                // For simplicity in standalone, we check if property is already set
                if (System.getProperty("jcef.forceDeviceScaleFactor") == null) {
                    val scale = JCefAppConfig.getForceDeviceScaleFactor()
                    if (scale > 0) {
                        System.setProperty("jcef.forceDeviceScaleFactor", scale.toString())
                    }
                }

                val config = JCefAppConfig.getInstance()
                ourInstance = KBCefApp(config)
            }
            return ourInstance!!
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

        val macCefFrameworkPathOSX = config.cefFrameworkPathOSX
        val settings = config.cefSettings
        
        // JCEF Settings
        settings.windowless_rendering_enabled = true
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_INFO
        
        // 强制禁用 Chrome Runtime 模式，确保只渲染网页内容（Alloy 模式）
        try {
            val chromeRuntimeField = settings.javaClass.getField("chrome_runtime")
            chromeRuntimeField.set(settings, false)
        } catch (e: Exception) {
            // 忽略找不到字段或设置失败的情况
        }
        
        // 强制开启远程调试端口 (CDP)，使用非标准端口 58964
        settings.remote_debugging_port = 58964
        
        // Fix: Set explicit cache path to avoid "Opening in existing browser session" warning
        val userHome = System.getProperty("user.home")
        settings.cache_path = "$userHome/.browserpilot/jcef_cache"
        
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
        // 强制绑定到本地 IPv4 并允许所有来源（配合调试端口）
        if (!args.any { it.startsWith("--remote-debugging-port") }) {
            args.add("--remote-debugging-port=58964")
        }
        if (!args.any { it.startsWith("--remote-debugging-address") }) {
            args.add("--remote-debugging-address=127.0.0.1")
        }
        if (!args.contains("--remote-allow-origins=*")) {
            args.add("--remote-allow-origins=*")
        }

        CefApp.addAppHandler(object : CefAppHandlerAdapter(args.toTypedArray()) {
            override fun onContextInitialized() {
                println("[KBCefApp] Context Initialized")
            }
            
            override fun onBeforeChildProcessLaunch(commandLine: String?) {
                // IDEA tracks GPU crashes here
                if (commandLine?.contains("--type=gpu-process") == true) {
                    println("[KBCefApp] Launching GPU process...")
                }
            }
        })

        // Platform specific startup is required on macOS to load the framework path
        if (OS.isMacintosh() && macCefFrameworkPathOSX != null) {
            println("[KBCefApp] MacOS: Calling startupAsync with $macCefFrameworkPathOSX")
            CefApp.startupAsync(macCefFrameworkPathOSX)
        } else {
            CefApp.startup(args.toTypedArray())
        }

        myCefSettings = settings
        // Fix: Pass args to getInstance
        myCefApp = CefApp.getInstance(args.toTypedArray(), settings, config.serverExe)
    }

    fun createClient(): KBCefClient {
        return KBCefClient(myCefApp.createClient())
    }

    override fun dispose() {
        myCefApp.dispose()
    }
}
