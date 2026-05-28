package xyz.kbrowser.webview

/**
 * 调试工具：将截图字节数组在一个独立窗口中展示。
 * 窗口尺寸固定为图片分辨率，鼠标移动时在光标旁实时显示 CSS 坐标，
 * 方便直接读取坐标后输入到坐标点击测试框中验证对齐精度。
 *
 * 仅在 JVM 平台有实际实现，其他平台为空操作。
 */
expect fun showScreenshotPreview(bytes: ByteArray)
