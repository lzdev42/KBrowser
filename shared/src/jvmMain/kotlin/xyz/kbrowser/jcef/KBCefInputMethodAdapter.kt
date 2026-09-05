package xyz.kbrowser.jcef

import org.cef.browser.CefBrowser
import org.cef.misc.CefRange
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import java.awt.font.TextHitInfo
import java.awt.im.InputMethodRequests
import java.text.AttributedCharacterIterator
import java.text.AttributedString
import java.text.CharacterIterator

/**
 * IME adapter modeled after IntelliJ IDEA's JBCefInputMethodAdapter.
 *
 * Responsibilities:
 * 1. [InputMethodRequests] — reports caret/character bounds to the OS input method
 *    so the candidate window is positioned correctly.
 * 2. [InputMethodListener] — receives OS IME composition/commit events and forwards
 *    them to the CEF IME API.
 */
class KBCefInputMethodAdapter(private val component: KBCefOsrComponent) : InputMethodRequests, InputMethodListener {

    companion object {
        /** Marker for an invalid range in CEF (from == to == -1). */
        private val DEFAULT_RANGE = CefRange(-1, -1)
    }

    @Volatile
    private var browser: CefBrowser? = null

    /**
     * Whether an IME composition is in progress. Maintained by
     * [inputMethodTextChanged] and queried by [KBCefOsrComponent.processKeyEvent]
     * so KEY_TYPED events are swallowed during composition (prevents ASCII/CJK
     * double input).
     */
    @Volatile
    var isComposing: Boolean = false
        private set

    /** Character bounds reported by the CEF OnImeCompositionRangeChanged callback. */
    @Volatile
    private var compositionCharacterBounds: Array<Rectangle>? = null

    /** Selection range reported by the CEF OnImeCompositionRangeChanged callback. */
    @Volatile
    private var compositionSelectionRange: CefRange? = null

    /** Selected text reported by the CEF OnTextSelectionChanged callback. */
    @Volatile
    private var selectedText: String = ""

    /** Selection range reported by the CEF OnTextSelectionChanged callback. */
    @Volatile
    private var selectionRange: CefRange = DEFAULT_RANGE

    fun setBrowser(browser: CefBrowser?) {
        this.browser = browser
    }

    /**
     * Called by [KBCefOsrHandler.OnImeCompositionRangeChanged] with the character
     * bounds of the current composition, relative to the browser view.
     */
    fun onImeCompositionRangeChanged(selectionRange: CefRange?, characterBounds: Array<Rectangle>?) {
        this.compositionSelectionRange = selectionRange
        this.compositionCharacterBounds = characterBounds
    }

    /**
     * Called by [KBCefOsrHandler.OnTextSelectionChanged] with the current text selection.
     */
    fun onTextSelectionChanged(text: String?, range: CefRange?) {
        this.selectedText = text ?: ""
        this.selectionRange = range ?: DEFAULT_RANGE
    }

    /**
     * Called by the OS input method to obtain the caret location for positioning
     * the candidate window.
     */
    override fun getTextLocation(offset: TextHitInfo?): Rectangle {
        val bounds = compositionCharacterBounds
        val rect = if (bounds != null && bounds.isNotEmpty()) {
            // Browser view coordinates are CSS pixels; scale by pixelDensity to physical pixels.
            val b = bounds[0]
            val density = component.renderHandler?.pixelDensity ?: 1.0
            Rectangle(
                (b.x * density).toInt(),
                (b.y * density).toInt(),
                (b.width * density).toInt(),
                (b.height * density).toInt()
            )
        } else {
            defaultImePosition
        }

        val componentLocation: Point = try {
            component.locationOnScreen
        } catch (_: Exception) {
            Point(0, 0)
        }
        rect.translate(componentLocation.x, componentLocation.y)
        return rect
    }

    override fun getLocationOffset(x: Int, y: Int): TextHitInfo? {
        val componentLocation: Point = try {
            component.locationOnScreen
        } catch (_: Exception) {
            return null
        }
        val p = Point(x, y)
        p.translate(-componentLocation.x, -componentLocation.y)

        val bounds = compositionCharacterBounds ?: return null
        for (i in bounds.indices) {
            if (bounds[i].contains(p)) {
                return TextHitInfo.leading(i)
            }
        }
        return null
    }

    override fun getInsertPositionOffset(): Int = 0

    override fun getCommittedText(
        beginIndex: Int,
        endIndex: Int,
        attributes: Array<out AttributedCharacterIterator.Attribute>?
    ): AttributedCharacterIterator {
        return AttributedString("").iterator
    }

    override fun getCommittedTextLength(): Int = 0

    override fun cancelLatestCommittedText(
        attributes: Array<out AttributedCharacterIterator.Attribute>?
    ): AttributedCharacterIterator? = null

    override fun getSelectedText(
        attributes: Array<out AttributedCharacterIterator.Attribute>?
    ): AttributedCharacterIterator {
        return AttributedString(selectedText).iterator
    }

    private val defaultImePosition: Rectangle
        get() = Rectangle(0, component.height, 0, 0)

    override fun inputMethodTextChanged(event: InputMethodEvent) {
        val br = browser ?: return
        val committedCharacterCount = event.committedCharacterCount
        val text = event.text ?: return

        var c = text.first()

        // Committed characters (confirmed by the IME)
        if (committedCharacterCount > 0) {
            val textBuffer = StringBuilder()
            var remaining = committedCharacterCount
            while (remaining-- > 0) {
                textBuffer.append(c)
                c = text.next()
            }
            val committedText = textBuffer.toString()
            imeCommitText(br, committedText, DEFAULT_RANGE, 0)

            // CEF sends no selection update after a commit, so the cached selection is stale
            selectedText = ""
            selectionRange = DEFAULT_RANGE
            isComposing = false
        }

        // Composed characters (still being edited by the IME)
        val composedBuffer = StringBuilder()
        while (c != CharacterIterator.DONE) {
            composedBuffer.append(c)
            c = text.next()
        }
        val composedText = composedBuffer.toString()
        if (composedText.isNotEmpty()) {
            isComposing = true
            var replacementRange = selectionRange
            if (replacementRange.from == replacementRange.to) {
                // A zero-length range points at the caret; passing it to CEF breaks Korean input ordering.
                replacementRange = DEFAULT_RANGE
            }
            // Selection range: place the caret at the end of the composed text
            val selRange = CefRange(composedText.length, composedText.length)
            imeSetComposition(br, composedText, replacementRange, selRange)
        } else if (isComposing) {
            // Composition cancelled (Escape / clicking elsewhere): tell CEF to clean up
            isComposing = false
            imeCancelComposition(br)
        }
        event.consume()
    }

    override fun caretPositionChanged(event: InputMethodEvent) {
    }

    // The CefBrowser interface does not declare ImeSetComposition/ImeCommitText, but the
    // runtime implementations do (RemoteBrowser in OOP mode, CefBrowser_N in local mode),
    // so reflection is used to support both.

    private fun imeSetComposition(
        browser: CefBrowser,
        text: String,
        replacementRange: CefRange,
        selectionRange: CefRange
    ) {
        try {
            val method = browser.javaClass.getMethod(
                "ImeSetComposition",
                String::class.java,
                java.util.List::class.java,
                CefRange::class.java,
                CefRange::class.java
            )
            // Transparent color: the underline only marks the composition range
            val underline = createCompositionUnderline(text.length)
            method.invoke(browser, text, listOf(underline), replacementRange, selectionRange)
        } catch (e: Exception) {
            // On failure, do nothing: CEF then handles the raw key events instead
        }
    }

    private fun imeCommitText(
        browser: CefBrowser,
        text: String,
        replacementRange: CefRange,
        relativeCursorPos: Int
    ) {
        try {
            val method = browser.javaClass.getMethod(
                "ImeCommitText",
                String::class.java,
                CefRange::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(browser, text, replacementRange, relativeCursorPos)
        } catch (e: Exception) {
        }
    }

    /**
     * Cancels the current IME composition (e.g. Escape or clicking elsewhere),
     * making sure CEF cleans up its internal state so no ghost text remains.
     */
    private fun imeCancelComposition(browser: CefBrowser) {
        try {
            val method = browser.javaClass.getMethod("ImeCancelComposition")
            method.invoke(browser)
        } catch (e: Exception) {
            println("[KBCefInputMethodAdapter] ImeCancelComposition 反射调用失败: ${e.message}")
        }
    }

    /**
     * Creates a CefCompositionUnderline via reflection: its constructor signature
     * varies across JCEF versions.
     */
    private fun createCompositionUnderline(textLength: Int): Any {
        return try {
            val clazz = Class.forName("org.cef.input.CefCompositionUnderline")
            val rangeClass = CefRange::class.java
            val colorClass = java.awt.Color::class.java
            val intClass = Int::class.javaPrimitiveType
            val styleClass = Class.forName("org.cef.input.CefCompositionUnderline\$Style")

            val constructor = clazz.getDeclaredConstructor(
                rangeClass, colorClass, colorClass, intClass, styleClass
            )

            val range = CefRange(0, textLength)
            val transparentColor = java.awt.Color(0, true)
            val solidStyle = styleClass.getDeclaredField("SOLID").get(null)

            constructor.newInstance(range, transparentColor, transparentColor, 0, solidStyle)
        } catch (e: Exception) {
            throw RuntimeException("Cannot create CefCompositionUnderline", e)
        }
    }
}
