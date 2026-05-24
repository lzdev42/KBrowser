package xyz.kbrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.kbrowser.webview.KBPage
import xyz.kbrowser.webview.KBrowser
import xyz.kbrowser.webview.KBProfile
import xyz.kbrowser.webview.getCleanedAxTree

data class BrowserViewState(
    val page: KBPage? = null,
    val logs: List<String> = listOf("自动化工具初始化完成"),
    val snapshotText: String = "",
    val selectorsText: String = "",
    val htmlPreview: String = "",
    val isOsrMode: Boolean = false,
    val navigateUrlInput: String = "https://www.zhipin.com/",
    val customSelector: String = "",
    val selectedTab: Int = 0,
    val coordX: String = "250",
    val coordY: String = "450",
    val isLoading: Boolean = false
)

sealed interface BrowserIntent {
    data class ChangeNavigateUrl(val url: String) : BrowserIntent
    data class ChangeCustomSelector(val selector: String) : BrowserIntent
    data class ChangeCoordX(val x: String) : BrowserIntent
    data class ChangeCoordY(val y: String) : BrowserIntent
    data class ChangeTab(val tab: Int) : BrowserIntent
    data class ToggleOsr(val enabled: Boolean) : BrowserIntent
    object Navigate : BrowserIntent
    object ClearLogs : BrowserIntent
    object FetchHtml : BrowserIntent
    object FetchSemanticSnapshot : BrowserIntent
    object FetchSelectors : BrowserIntent
    data class ClickSelector(val selector: String) : BrowserIntent
    data class ClickCoordinates(val x: Int, val y: Int) : BrowserIntent
    data class HoverCoordinates(val x: Int, val y: Int) : BrowserIntent
    object RunAutoFlow : BrowserIntent
}

class BrowserViewModel : ViewModel() {
    private val _state = MutableStateFlow(BrowserViewState())
    val state: StateFlow<BrowserViewState> = _state.asStateFlow()

    init {
        // 在主线程启动页面，以便在 Desktop / Android 下顺利渲染
        viewModelScope.launch {
            try {
                println("[DEBUG] BrowserViewModel: 开始初始化默认浏览器标签页")
                log("正在初始化默认浏览器标签页...")
                val newPage = KBrowser.newPage(
                    url = "https://www.zhipin.com/",
                    profile = KBProfile("default_session")
                )
                println("[DEBUG] BrowserViewModel: KBrowser.newPage 返回成功")
                _state.update { it.copy(page = newPage) }
                log("默认标签页加载完成")
            } catch (e: Exception) {
                println("[DEBUG] BrowserViewModel: 初始化标签页失败: ${e.message}")
                e.printStackTrace()
                log("初始化标签页失败: ${e.message}")
            }
        }
    }

    fun dispatch(intent: BrowserIntent) {
        when (intent) {
            is BrowserIntent.ChangeNavigateUrl -> {
                _state.update { it.copy(navigateUrlInput = intent.url) }
            }
            is BrowserIntent.ChangeCustomSelector -> {
                _state.update { it.copy(customSelector = intent.selector) }
            }
            is BrowserIntent.ChangeCoordX -> {
                _state.update { it.copy(coordX = intent.x) }
            }
            is BrowserIntent.ChangeCoordY -> {
                _state.update { it.copy(coordY = intent.y) }
            }
            is BrowserIntent.ChangeTab -> {
                _state.update { it.copy(selectedTab = intent.tab) }
            }
            is BrowserIntent.ToggleOsr -> {
                val url = _state.value.navigateUrlInput
                val oldPage = _state.value.page
                
                _state.update { it.copy(isOsrMode = intent.enabled) }
                
                viewModelScope.launch {
                    log("正在切换 OSR 离屏渲染模式为: ${intent.enabled}，重建浏览器...")
                    // TODO: 如果有资源清理逻辑，可以在这里清理 oldPage
                    try {
                        val newPage = KBrowser.newPage(
                            url = url,
                            profile = KBProfile("default_session"),
                            isOsr = intent.enabled
                        )
                        _state.update { it.copy(page = newPage) }
                        log("模式切换完成！")
                    } catch (e: Exception) {
                        log("模式切换失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.Navigate -> {
                val url = _state.value.navigateUrlInput
                val page = _state.value.page
                if (page != null && url.isNotBlank()) {
                    viewModelScope.launch {
                        try {
                            log("开始加载: $url")
                            page.loadUrl(url)
                            log("加载成功: $url")
                        } catch (e: Exception) {
                            log("加载失败: ${e.message}")
                        }
                    }
                }
            }
            is BrowserIntent.ClearLogs -> {
                _state.update { it.copy(logs = emptyList()) }
            }
            is BrowserIntent.FetchHtml -> {
                val page = _state.value.page ?: return
                log("开始抓取 HTML 源码...")
                viewModelScope.launch {
                    try {
                        val result = page.evaluateJavascript("document.documentElement.outerHTML")
                        val cleanHtml = if (result.startsWith("\"") && result.endsWith("\"")) {
                            result.substring(1, result.length - 1)
                                .replace("\\\"", "\"")
                                .replace("\\n", "\n")
                                .replace("\\r", "\r")
                                .replace("\\t", "\t")
                        } else {
                            result
                        }
                        _state.update { it.copy(htmlPreview = cleanHtml) }
                        log("抓取 HTML 成功！(共 ${cleanHtml.length} 字节)")
                    } catch (e: Exception) {
                        log("抓取 HTML 失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.FetchSemanticSnapshot -> {
                val page = _state.value.page ?: return
                log("开始抓取 Aria 语义树（全量DOM）...")
                viewModelScope.launch {
                    try {
                        val rawAxTree = page.getRawAxTree()
                        val cleanedTree = rawAxTree.getCleanedAxTree()
                        log("诊断: URL=${rawAxTree.url}, 总元素=${rawAxTree.totalElements}, 可见=${rawAxTree.visibleElements}")
                        
                        val jsonParser = kotlinx.serialization.json.Json { prettyPrint = true }
                        val jsonStr = jsonParser.encodeToString(xyz.kbrowser.webview.AxTreeData.serializer(), cleanedTree)
                        val formattedYaml = formatJsonLikeYaml(jsonStr)
                        
                        _state.update { it.copy(snapshotText = formattedYaml) }
                        log("抓取 Aria 语义树成功！保留节点数: ${cleanedTree.nodes.size}")
                    } catch (e: Exception) {
                        log("抓取 Aria 语义树失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.FetchSelectors -> {
                val page = _state.value.page ?: return
                log("开始抓取选择器（全量DOM）...")
                viewModelScope.launch {
                    try {
                        val rawAxTree = page.getRawAxTree()
                        val nodesWithSelector = rawAxTree.nodes.filter { it.id.isNotEmpty() || it.className.isNotEmpty() }
                        
                        log("抓取选择器成功！包含 id/class 的节点数: ${nodesWithSelector.size}")
                        
                        val builder = StringBuilder()
                        nodesWithSelector.forEach { node ->
                            val selector = buildString {
                                append(node.tagName)
                                if (node.id.isNotEmpty()) append("#").append(node.id)
                                if (node.className.isNotEmpty()) {
                                    node.className.split(" ").filter { it.isNotEmpty() }.forEach {
                                        append(".").append(it)
                                    }
                                }
                            }
                            builder.append("- selector: \"$selector\"\n")
                            builder.append("  text: \"${node.text.take(50)}\"\n")
                            builder.append("  refid: \"${node.refid}\"\n")
                            builder.append("  bounds: [${node.x}, ${node.y}, ${node.width}, ${node.height}]\n\n")
                        }
                        
                        _state.update { it.copy(selectorsText = builder.toString()) }
                    } catch (e: Exception) {
                        log("抓取选择器失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.ClickSelector -> {
                val page = _state.value.page ?: return
                if (intent.selector.isBlank()) return
                log("执行 CSS 选择器点击: '${intent.selector}'")
                viewModelScope.launch {
                    try {
                        val js = """
                            (function() {
                                var el = document.querySelector("${intent.selector}");
                                if (!el) return 'NOT_FOUND';
                                el.click();
                                return 'OK';
                            })()
                        """.trimIndent()
                        val result = page.evaluateJavascript(js)
                        log("点击选择器结果: $result")
                    } catch (e: Exception) {
                        log("点击选择器失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.ClickCoordinates -> {
                val page = _state.value.page ?: return
                log("执行原生坐标虚拟点击: (${intent.x}, ${intent.y})")
                viewModelScope.launch {
                    try {
                        page.clickByCoordinates(intent.x, intent.y)
                        log("坐标虚拟点击指令发送成功！")
                    } catch (e: Exception) {
                        log("坐标虚拟点击失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.HoverCoordinates -> {
                val page = _state.value.page ?: return
                log("执行原生坐标虚拟悬停: (${intent.x}, ${intent.y})")
                viewModelScope.launch {
                    try {
                        page.hoverByCoordinates(intent.x, intent.y)
                        log("坐标虚拟悬停指令发送成功！")
                    } catch (e: Exception) {
                        log("坐标虚拟悬停失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.RunAutoFlow -> {
                val page = _state.value.page ?: return
                viewModelScope.launch {
                    log("启动一键自动化测试流程...")
                    try {
                        log("[步骤 1] 正在提取 Aria 语义快照树...")
                        val rawAxTree = page.getRawAxTree()
                        val cleanedTree = rawAxTree.getCleanedAxTree()
                        val jsonParser = kotlinx.serialization.json.Json { prettyPrint = true }
                        val jsonStr = jsonParser.encodeToString(xyz.kbrowser.webview.AxTreeData.serializer(), cleanedTree)
                        val snapshot = formatJsonLikeYaml(jsonStr)
                        
                        log("[步骤 1] 语义树提取成功")

                        log("[步骤 2] 正在抓取 CSS 选择器...")
                        val nodesWithSelector = rawAxTree.nodes.filter { it.id.isNotEmpty() || it.className.isNotEmpty() }
                        val builder = StringBuilder()
                        nodesWithSelector.take(30).forEach { node ->
                            val selector = buildString {
                                append(node.tagName)
                                if (node.id.isNotEmpty()) append("#").append(node.id)
                            }
                            builder.append("- selector: \"$selector\"\n")
                            builder.append("  refid: \"${node.refid}\"\n\n")
                        }
                        val selectors = builder.toString()
                        log("[步骤 2] 选择器抓取完成")

                        log("[步骤 3] 正在抓取网页完整 outerHTML...")
                        val result = page.evaluateJavascript("document.documentElement.outerHTML")
                        val cleanHtml = if (result.startsWith("\"") && result.endsWith("\"")) {
                            result.substring(1, result.length - 1).replace("\\\"", "\"")
                        } else {
                            result
                        }
                        log("[步骤 3] HTML 网页源码审计完成")

                        log("[步骤 4] 正在对坐标 (250, 450) 分发真实鼠标交互事件...")
                        page.clickByCoordinates(250, 450)
                        log("[步骤 4] 坐标虚拟点击指令分发成功！")
                        
                        _state.update {
                            it.copy(
                                snapshotText = snapshot,
                                selectorsText = selectors,
                                htmlPreview = cleanHtml
                            )
                        }
                        log("自动化测试流程全部执行完毕")
                    } catch (e: Exception) {
                        log("自动化测试流程执行出错: ${e.message}")
                    }
                }
            }
        }
    }

    private fun log(message: String) {
        val timeString = currentTime()
        _state.update {
            it.copy(logs = it.logs + "[$timeString] $message")
        }
    }

    private fun currentTime(): String {
        val totalSeconds = currentTimeMillis() / 1000
        val hours = (totalSeconds / 3600) % 24
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }

    private fun formatJsonLikeYaml(json: String): String {
        val sb = StringBuilder()
        var indent = 0
        var inString = false
        var escape = false
        
        for (i in json.indices) {
            val c = json[i]
            if (escape) {
                sb.append(c)
                escape = false
                continue
            }
            if (c == '\\') {
                sb.append(c)
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                sb.append(c)
                continue
            }
            if (inString) {
                sb.append(c)
                continue
            }
            
            when (c) {
                '{', '[' -> {
                    sb.append(c).append('\n')
                    indent += 2
                    sb.append(" ".repeat(indent))
                }
                '}', ']' -> {
                    sb.append('\n')
                    indent -= 2
                    sb.append(" ".repeat(indent)).append(c)
                }
                ',' -> {
                    sb.append(",\n")
                    sb.append(" ".repeat(indent))
                }
                ' ', '\n', '\r', '\t' -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
            .replace("\"", "")
            .replace(Regex("\\{\\s*\\}"), "{}")
            .replace(Regex("\\[\\s*\\]"), "[]")
    }

    override fun onCleared() {
        super.onCleared()
        // 销毁时清理底层的 WebView 并关闭页面
        _state.value.page?.close()
    }
}