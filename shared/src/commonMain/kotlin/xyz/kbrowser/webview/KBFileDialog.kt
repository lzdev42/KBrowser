package xyz.kbrowser.webview

/**
 * File dialog mode, corresponding to HTML input types and CEF FileDialogMode.
 */
enum class KBFileDialogMode {
    OPEN,
    OPEN_MULTIPLE,
    OPEN_FOLDER,
    SAVE
}

/**
 * File dialog request from the browser engine when file selection is triggered.
 *
 * @property mode Dialog mode
 * @property title Dialog title (may be empty)
 * @property defaultFilePath Default file path (may be empty)
 * @property acceptFilters Accepted file type filters, e.g. ".jpg", ".png", "image/png"
 * @property acceptExtensions File extensions list (JCEF only)
 * @property acceptDescriptions Filter descriptions (JCEF only)
 */
data class KBFileDialogRequest(
    val mode: KBFileDialogMode,
    val title: String,
    val defaultFilePath: String,
    val acceptFilters: List<String>,
    val acceptExtensions: List<String>,
    val acceptDescriptions: List<String>
)

/**
 * File dialog callback to return selected file paths to the browser engine.
 *
 * After handling a [KBFileDialogRequest], callers MUST invoke either [selectFiles] or [cancel],
 * otherwise the browser engine will remain blocked waiting for the file selection.
 *
 * Thread safety: [selectFiles] and [cancel] can be called from any thread.
 */
interface KBFileDialogCallback {
    /**
     * Submit selected file paths.
     * @param filePaths Absolute file paths. Should not be empty; use [cancel] instead.
     */
    fun selectFiles(filePaths: List<String>)

    /** Cancel file selection. */
    fun cancel()
}
