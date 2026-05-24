package xyz.kbrowser.webview

interface JsResultCallback {
    fun confirm()
    fun cancel()
}

interface JsPromptResultCallback {
    fun confirm(value: String?)
    fun cancel()
}

enum class PermissionResource {
    CAMERA,
    MICROPHONE,
    GEOLOCATION,
    PROTECTED_MEDIA_IDENTIFIER,
    AUDIO_CAPTURE,
    VIDEO_CAPTURE
}

interface PermissionRequest {
    val origin: String
    val resources: List<PermissionResource>
    fun grant()
    fun deny()
}

interface KBWebChromeClient {
    fun onJsAlert(url: String, message: String, callback: JsResultCallback)
    fun onJsConfirm(url: String, message: String, callback: JsResultCallback)
    fun onJsPrompt(url: String, message: String, defaultValue: String?, callback: JsPromptResultCallback)
    fun onPermissionRequest(request: PermissionRequest)
}
