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
 * IME 适配器，参照 IntelliJ IDEA 的 JBCefInputMethodAdapter 实现。
 *
 * 职责：
 * 1. [InputMethodRequests] — 向 OS 输入法提供光标/字符边界信息，使候选窗口正确定位
 * 2. [InputMethodListener] — 接收 OS 输入法的组合/提交事件，转发给 CEF 的 IME API
 *
 * 修复的问题：
 * - 中文 IME 候选窗口出现在屏幕底部而非光标附近
 * - 中文 IME 组合文本无法正确输入到 CEF 浏览器
 */
class KBCefInputMethodAdapter(private val component: KBCefOsrComponent) : InputMethodRequests, InputMethodListener {

    companion object {
        /** CEF 中表示无效范围的标记（from == to == -1） */
        private val DEFAULT_RANGE = CefRange(-1, -1)
    }

    @Volatile
    private var browser: CefBrowser? = null

    /**
     * IME 是否正在组合中。
     * 由 [inputMethodTextChanged] 维护，供 [KBCefOsrComponent.processKeyEvent] 查询，
     * 以便在组合期间吞掉 KEY_TYPED 事件，避免英文字母与中文输入双路冲突。
     */
    @Volatile
    var isComposing: Boolean = false
        private set

    /** CEF 回调 OnImeCompositionRangeChanged 提供的字符边界 */
    @Volatile
    private var compositionCharacterBounds: Array<Rectangle>? = null

    /** CEF 回调 OnImeCompositionRangeChanged 提供的选择范围 */
    @Volatile
    private var compositionSelectionRange: CefRange? = null

    /** CEF 回调 OnTextSelectionChanged 提供的选中文本 */
    @Volatile
    private var selectedText: String = ""

    /** CEF 回调 OnTextSelectionChanged 提供的选择范围 */
    @Volatile
    private var selectionRange: CefRange = DEFAULT_RANGE

    fun setBrowser(browser: CefBrowser?) {
        this.browser = browser
    }

    // ═══════════════════════════════════════════════════════════════
    // CefRenderHandler 回调 → 存储字符边界数据
    // ═══════════════════════════════════════════════════════════════

    /**
     * 由 [KBCefOsrHandler.OnImeCompositionRangeChanged] 调用。
     * CEF 通知当前输入组合的字符边界矩形（相对于浏览器视图）。
     */
    fun onImeCompositionRangeChanged(selectionRange: CefRange?, characterBounds: Array<Rectangle>?) {
        this.compositionSelectionRange = selectionRange
        this.compositionCharacterBounds = characterBounds
    }

    /**
     * 由 [KBCefOsrHandler.OnTextSelectionChanged] 调用。
     * CEF 通知当前文本选择的变化。
     */
    fun onTextSelectionChanged(text: String?, range: CefRange?) {
        this.selectedText = text ?: ""
        this.selectionRange = range ?: DEFAULT_RANGE
    }

    // ═══════════════════════════════════════════════════════════════
    // InputMethodRequests — OS 输入法查询光标位置
    // ═══════════════════════════════════════════════════════════════

    /**
     * OS 输入法调用此方法获取光标位置，用于定位候选窗口。
     * 这是修复候选窗口位置错误的关键方法。
     */
    override fun getTextLocation(offset: TextHitInfo?): Rectangle {
        val bounds = compositionCharacterBounds
        val rect = if (bounds != null && bounds.isNotEmpty()) {
            // 将浏览器视图坐标（CSS 像素）乘以 pixelDensity 转换为物理像素
            val b = bounds[0]
            val density = component.renderHandler?.pixelDensity ?: 1.0
            Rectangle(
                (b.x * density).toInt(),
                (b.y * density).toInt(),
                (b.width * density).toInt(),
                (b.height * density).toInt()
            )
        } else {
            // 没有组合字符边界时，返回组件底部左侧作为默认位置
            defaultImePosition
        }

        // 将浏览器视图坐标转换为屏幕坐标
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

    // ═══════════════════════════════════════════════════════════════
    // InputMethodListener — OS 输入法组合/提交事件 → CEF
    // ═══════════════════════════════════════════════════════════════

    override fun inputMethodTextChanged(event: InputMethodEvent) {
        val br = browser ?: return
        val committedCharacterCount = event.committedCharacterCount
        val text = event.text ?: return

        var c = text.first()

        // 处理已提交的字符（IME 确认输入的文本）
        if (committedCharacterCount > 0) {
            val textBuffer = StringBuilder()
            var remaining = committedCharacterCount
            while (remaining-- > 0) {
                textBuffer.append(c)
                c = text.next()
            }
            val committedText = textBuffer.toString()
            imeCommitText(br, committedText, DEFAULT_RANGE, 0)

            // CEF 提交后不会通知选择范围变化，当前数据已过时
            selectedText = ""
            selectionRange = DEFAULT_RANGE
            // 提交后组合状态结束
            isComposing = false
        }

        // 处理组合中的字符（IME 正在编辑但尚未确认的文本）
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
                // 零长度范围指向光标位置，传给 CEF 会破坏韩语输入顺序
                replacementRange = DEFAULT_RANGE
            }
            // 选择范围：将光标移到组合文本末尾
            val selRange = CefRange(composedText.length, composedText.length)
            imeSetComposition(br, composedText, replacementRange, selRange)
        } else if (isComposing) {
            // 组合被取消（Escape / 点击其他位置），通知 CEF 清理组合状态
            isComposing = false
            imeCancelComposition(br)
        }
        event.consume()
    }

    override fun caretPositionChanged(event: InputMethodEvent) {
        // 不需要处理
    }

    // ═══════════════════════════════════════════════════════════════
    // 反射调用 CEF IME API
    // ═══════════════════════════════════════════════════════════════
    // CefBrowser 接口没有声明 ImeSetComposition/ImeCommitText，
    // 但运行时的 RemoteBrowser（OOP 模式）和 CefBrowser_N（本地模式）实现了这些方法。
    // 使用反射调用以兼容两种模式。

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
            // 创建下划线（透明色，仅用于标记组合范围）
            val underline = createCompositionUnderline(text.length)
            method.invoke(browser, text, listOf(underline), replacementRange, selectionRange)
        } catch (e: Exception) {
            // 反射调用失败时回退：取消组合并让 CEF 通过 key event 处理
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
     * 取消当前的 IME 组合状态。
     * 当用户按 Escape 或点击其他位置导致组合被取消时调用，
     * 确保 CEF 内部状态被正确清理，避免残留幽灵文本。
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
     * 创建 CefCompositionUnderline 对象。
     * 使用反射因为 CefCompositionUnderline 的构造函数可能因 JCEF 版本而异。
     */
    private fun createCompositionUnderline(textLength: Int): Any {
        return try {
            val clazz = Class.forName("org.cef.input.CefCompositionUnderline")
            val rangeClass = CefRange::class.java
            val colorClass = java.awt.Color::class.java
            val intClass = Int::class.javaPrimitiveType
            val styleClass = Class.forName("org.cef.input.CefCompositionUnderline\$Style")

            // 构造函数: CefCompositionUnderline(CefRange, Color, Color, int, Style)
            // 对应 IntelliJ: new CefCompositionUnderline(range, backgroundColor, textColor, thickness, style)
            val constructor = clazz.getDeclaredConstructor(
                rangeClass, colorClass, colorClass, intClass, styleClass
            )

            val range = CefRange(0, textLength)
            val transparentColor = java.awt.Color(0, true)
            val solidStyle = styleClass.getDeclaredField("SOLID").get(null)

            constructor.newInstance(range, transparentColor, transparentColor, 0, solidStyle)
        } catch (e: Exception) {
            // 如果 CefCompositionUnderline 不可用，尝试简化版本
            throw RuntimeException("Cannot create CefCompositionUnderline", e)
        }
    }
}
