package xyz.kbrowser.webview

/**
 * 操作执行结果，用于程序化验证 CDP 操作是否成功。
 *
 * 调用方可直接检查 [success] 属性：
 * ```kotlin
 * val result = page.click("r12")
 * if (!result.success) { /* 处理失败 */ }
 * ```
 *
 * 也可忽略返回值（不影响任何逻辑）。
 */
sealed class OperationResult {
    abstract val success: Boolean

    /**
     * 操作成功且已验证。
     * @param action 操作类型: "click", "fill", "type", "scroll"
     * @param verified 是否经过程序化验证（true）还是仅确认事件已发送（false）
     * @param detail 可选描述信息
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
     * 操作失败。
     * @param action 操作类型
     * @param reason 失败原因描述（如 "被 #overlay 遮挡", "期望值 'abc' 实际值 ''"）
     * @param recoverable AI 可据此决定是否重试（遮挡→可重试，元素不存在→不可重试）
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
     * 操作已发送但无法验证（如 hover 无可观测状态变化）。
     * 视为成功（success = true），因为 fire-and-forget 事件已被浏览器接收。
     */
    data object Acknowledged : OperationResult() {
        override val success: Boolean get() = true
        override fun toString(): String = "Acknowledged"
    }
}
