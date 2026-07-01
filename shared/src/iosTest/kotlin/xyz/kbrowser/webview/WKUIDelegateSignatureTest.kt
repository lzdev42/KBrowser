package xyz.kbrowser.webview

import kotlin.test.Test
import kotlin.test.assertTrue
import platform.WebKit.*
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObject
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Runtime diagnostic: verifies that the Obj-C runtime sees all three
 * WKUIDelegateProtocol JS-panel overrides on a Kotlin/Native object.
 *
 * If `respondsToSelector:` returns false for alert/prompt but true for
 * confirm, the root cause is a Kotlin/Native method-registration issue.
 * If it returns true for all three, the root cause is elsewhere (most
 * likely a WebKit security restriction on the page's origin, e.g.
 * `about:blank` loaded via `loadHTMLString:baseURL:`).
 */
@OptIn(ExperimentalForeignApi::class)
class WKUIDelegateRespondsSelectorTest {

    @Test
    fun verify_all_three_selectors_are_responded_to() {
        val delegate = object : NSObject(), WKUIDelegateProtocol {
            override fun webView(
                webView: WKWebView,
                runJavaScriptAlertPanelWithMessage: String,
                initiatedByFrame: WKFrameInfo,
                completionHandler: () -> Unit
            ) { completionHandler() }

            override fun webView(
                webView: WKWebView,
                runJavaScriptConfirmPanelWithMessage: String,
                initiatedByFrame: WKFrameInfo,
                completionHandler: (Boolean) -> Unit
            ) { completionHandler(true) }

            override fun webView(
                webView: WKWebView,
                runJavaScriptTextInputPanelWithPrompt: String,
                defaultText: String?,
                initiatedByFrame: WKFrameInfo,
                completionHandler: (String?) -> Unit
            ) { completionHandler(null) }
        }

        val alertSel    = "webView:runJavaScriptAlertPanelWithMessage:initiatedByFrame:completionHandler:"
        val confirmSel  = "webView:runJavaScriptConfirmPanelWithMessage:initiatedByFrame:completionHandler:"
        val promptSel   = "webView:runJavaScriptTextInputPanelWithPrompt:defaultText:initiatedByFrame:completionHandler:"

        val alertOk   = delegate.respondsToSelector(NSSelectorFromString(alertSel))
        val confirmOk = delegate.respondsToSelector(NSSelectorFromString(confirmSel))
        val promptOk  = delegate.respondsToSelector(NSSelectorFromString(promptSel))

        println("[KB_DIAG] respondsToSelector alert   = $alertOk")
        println("[KB_DIAG] respondsToSelector confirm = $confirmOk")
        println("[KB_DIAG] respondsToSelector prompt  = $promptOk")

        assertTrue(alertOk,   "Obj-C runtime does NOT see alert override selector — Kotlin/Native method registration bug for () -> Unit block")
        assertTrue(confirmOk, "Obj-C runtime does NOT see confirm override selector")
        assertTrue(promptOk,  "Obj-C runtime does NOT see prompt override selector — Kotlin/Native method registration bug for (String?) -> Unit block")
    }
}
