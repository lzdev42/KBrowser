package xyz.kbrowser.webview

/**
 * Result of an operation, for programmatically verifying whether a CDP operation succeeded.
 *
 * Callers can check the [success] property directly:
 * ```kotlin
 * val result = page.click("r12")
 * if (!result.success) { /* handle failure */ }
 * ```
 *
 * The return value may also be ignored (it affects nothing).
 */
sealed class OperationResult {
    abstract val success: Boolean

    /**
     * The operation succeeded and was verified.
     * @param action operation type: "click", "fill", "type", "scroll"
     * @param verified whether the result was programmatically verified (true) or only the event
     *   dispatch was confirmed (false)
     * @param detail optional description
     */
    data class Success(
        val action: String,
        val verified: Boolean = true,
        val detail: String = ""
    ) : OperationResult() {
        override val success: Boolean get() = true
        override fun toString(): String =
            if (detail.isNotEmpty()) "Success($action, verified=$verified, $detail)"
            else "Success($action, verified=$verified)"
    }

    /**
     * The operation failed.
     * @param action operation type
     * @param reason failure description (e.g. "occluded by #overlay", "expected 'abc' but got ''")
     * @param recoverable hint for whether retrying makes sense (occlusion → retryable, missing
     *   element → not retryable)
     */
    data class Failure(
        val action: String,
        val reason: String,
        val recoverable: Boolean = true
    ) : OperationResult() {
        override val success: Boolean get() = false
        override fun toString(): String = "Failure($action, $reason)"
    }

    /**
     * The operation was sent but could not be verified (e.g. hover has no observable state
     * change). Treated as success (success = true), since the fire-and-forget event was
     * accepted by the browser.
     */
    data object Acknowledged : OperationResult() {
        override val success: Boolean get() = true
        override fun toString(): String = "Acknowledged"
    }
}
