package xyz.kbrowser.webview

/**
 * Platform-independent keyboard key enumeration.
 * Used by [KBPage.press], [KBLocator.press], and [KBLocator.type] for native key event injection.
 *
 * Each platform maps these enum values to their own key code systems:
 * - JVM: java.awt.event.KeyEvent.VK_* via JCEF sendKeyEvent
 * - Android: android.view.KeyEvent.KEYCODE_* via dispatchKeyEvent
 * - iOS: JS KeyboardEvent key/code values
 */
enum class KeyboardKey(
    val character: Char? = null,
    val isModifier: Boolean = false
) {
    // ===== Special keys =====
    ENTER(character = '\n'),
    TAB(character = '\t'),
    ESCAPE,
    BACKSPACE(character = '\b'),
    DELETE,

    // ===== Arrow keys =====
    ARROW_UP,
    ARROW_DOWN,
    ARROW_LEFT,
    ARROW_RIGHT,

    // ===== Modifier keys =====
    SHIFT(isModifier = true),
    CONTROL(isModifier = true),
    ALT(isModifier = true),
    META(isModifier = true),  // Mac Command key

    // ===== Space =====
    SPACE(character = ' '),

    // ===== Navigation keys =====
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
    INSERT,

    // ===== Function keys =====
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,

    // ===== Common combination keys (Ctrl+A, Ctrl+C, Ctrl+V, etc.) =====
    A(character = 'a'),
    C(character = 'c'),
    V(character = 'v'),
    X(character = 'x'),
    S(character = 's'),
    Z(character = 'z')
}