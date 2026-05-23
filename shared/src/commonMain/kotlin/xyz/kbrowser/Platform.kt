package xyz.kbrowser

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/**
 * 跨平台时间戳（毫秒），用于替代 commonMain 中不可用的 java.time / kotlin.system
 */
expect fun currentTimeMillis(): Long