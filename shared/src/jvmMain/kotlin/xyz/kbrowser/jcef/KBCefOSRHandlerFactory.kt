package xyz.kbrowser.jcef

import org.cef.handler.CefRenderHandler
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.swing.JComponent

interface KBCefOSRHandlerFactory {
    
    fun createComponent(isMouseWheelEventEnabled: Boolean): JComponent {
        return KBCefOsrComponent()
    }
    
    fun createCefRenderHandler(component: JComponent): CefRenderHandler {
        val osrComponent = component as KBCefOsrComponent
        val screenBoundsProvider = createScreenBoundsProvider()
        
        // Always try to use the NativeOsrHandler since it uses reflection 
        // and falls back to standard loading if JBR/NativeRasterLoader is unavailable.
        val handler = KBCefNativeOsrHandler(osrComponent, screenBoundsProvider)
        osrComponent.setRenderHandler(handler)
        
        return handler
    }
    
    fun createScreenBoundsProvider(): (JComponent) -> Rectangle {
        return { component ->
            if (!GraphicsEnvironment.isHeadless()) {
                try {
                    if (component.isShowing) {
                        component.graphicsConfiguration.device.defaultConfiguration.bounds
                    } else {
                        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                    }
                } catch (e: Exception) {
                    Rectangle(0, 0, 0, 0)
                }
            } else {
                Rectangle(0, 0, 0, 0)
            }
        }
    }
    
    companion object {
        val DEFAULT = object : KBCefOSRHandlerFactory {}
        fun getInstance(): KBCefOSRHandlerFactory = DEFAULT
    }
}
