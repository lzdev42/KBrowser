package xyz.kbrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.kbrowser.webview.WebViewController

class BrowserViewModel : ViewModel() {
    private val _logs = MutableStateFlow<List<String>>(listOf("自动化工具初始化完成"))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _snapshotText = MutableStateFlow<String>("")
    val snapshotText: StateFlow<String> = _snapshotText.asStateFlow()

    private val _selectorsText = MutableStateFlow<String>("")
    val selectorsText: StateFlow<String> = _selectorsText.asStateFlow()

    private val _htmlPreview = MutableStateFlow<String>("")
    val htmlPreview: StateFlow<String> = _htmlPreview.asStateFlow()

    fun log(message: String) {
        _logs.value = _logs.value + "[${currentTime()}] $message"
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun currentTime(): String {
        val totalSeconds = currentTimeMillis() / 1000
        val hours = (totalSeconds / 3600) % 24
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }

    fun fetchHtml(controller: WebViewController) {
        log("开始抓取 HTML 源码...")
        controller.getOuterHtml { result ->
            _htmlPreview.value = result
            log("抓取 HTML 成功！(共 ${result.length} 字节)")
        }
    }

    fun fetchSemanticSnapshot(controller: WebViewController) {
        log("开始抓取 Aria 语义树（全量DOM）...")
        controller.getSemanticSnapshot { json ->
            viewModelScope.launch(Dispatchers.Default) {
                // 提取诊断信息并输出到日志
                val diagMatch = Regex("\"diagnostics\"\\s*:\\s*\\{([^}]+)\\}").find(json)
                if (diagMatch != null) {
                    val diag = diagMatch.groupValues[1]
                    val urlMatch = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(diag)
                    val totalMatch = Regex("\"totalElements\"\\s*:\\s*(\\d+)").find(diag)
                    val visMatch = Regex("\"visibleElements\"\\s*:\\s*(\\d+)").find(diag)
                    val hidMatch = Regex("\"hiddenElements\"\\s*:\\s*(\\d+)").find(diag)
                    val iframeMatch = Regex("\"iframeCount\"\\s*:\\s*(\\d+)").find(diag)
                    log("诊断: URL=${urlMatch?.groupValues?.get(1) ?: "?"}, 总元素=${totalMatch?.groupValues?.get(1) ?: "?"}, 可见=${visMatch?.groupValues?.get(1) ?: "?"}, 隐藏=${hidMatch?.groupValues?.get(1) ?: "?"}, iframe=${iframeMatch?.groupValues?.get(1) ?: "?"}")
                }
                
                val formattedYaml = formatJsonLikeYaml(json)
                
                withContext(Dispatchers.Main) {
                    _snapshotText.value = formattedYaml
                    log("抓取 Aria 语义树成功！数据字节数: ${json.length}")
                }
            }
        }
    }

    fun fetchSelectors(controller: WebViewController) {
        log("开始抓取选择器（全量DOM）...")
        controller.getSelectors { json ->
            viewModelScope.launch(Dispatchers.Default) {
                val totalMatch = Regex("\"total\"\\s*:\\s*(\\d+)").find(json)
                val visMatch = Regex("\"visible\"\\s*:\\s*(\\d+)").find(json)
                val hidMatch = Regex("\"hidden\"\\s*:\\s*(\\d+)").find(json)
                log("抓取选择器成功！总=${totalMatch?.groupValues?.get(1) ?: "?"}, 可见=${visMatch?.groupValues?.get(1) ?: "?"}, 隐藏=${hidMatch?.groupValues?.get(1) ?: "?"}")
                
                val formattedYaml = formatJsonLikeYaml(json)
                
                withContext(Dispatchers.Main) {
                    _selectorsText.value = formattedYaml
                }
            }
        }
    }

    fun clickSelector(controller: WebViewController, selector: String) {
        if (selector.isBlank()) return
        log("执行 CSS 选择器点击: '$selector'")
        controller.clickBySelector(selector)
    }

    fun clickCoordinates(controller: WebViewController, x: Int, y: Int) {
        log("执行 JCEF 原生 AWT 坐标虚拟点击: ($x, $y)")
        controller.clickByCoordinates(x, y)
    }

    fun hoverCoordinates(controller: WebViewController, x: Int, y: Int) {
        log("执行 JCEF 原生 AWT 坐标虚拟悬停: ($x, $y)")
        controller.hoverByCoordinates(x, y)
    }

    fun runAutoFlow(controller: WebViewController) {
        viewModelScope.launch {
            log("启动一键自动化测试流程...")

            log("[步骤 1] 正在提取 Aria 语义快照树...")
            controller.getSemanticSnapshot { json ->
                viewModelScope.launch(Dispatchers.Default) {
                    val formattedAria = formatJsonLikeYaml(json)
                    withContext(Dispatchers.Main) {
                        _snapshotText.value = formattedAria
                        log("[步骤 1] 语义树提取成功")

                        log("[步骤 2] 正在抓取 CSS 选择器...")
                        controller.getSelectors { selJson ->
                            viewModelScope.launch(Dispatchers.Default) {
                                val formattedSel = formatJsonLikeYaml(selJson)
                                withContext(Dispatchers.Main) {
                                    _selectorsText.value = formattedSel
                                    log("[步骤 2] 选择器抓取完成")

                                    log("[步骤 3] 正在抓取网页完整 outerHTML...")
                                    controller.getOuterHtml { html ->
                                        _htmlPreview.value = html
                                        log("[步骤 3] HTML 网页源码审计完成")

                                        log("[步骤 4] 正在对坐标 (250, 450) 分发 AWT 真实鼠标交互事件...")
                                        controller.clickByCoordinates(250, 450)
                                        log("[步骤 4] 坐标虚拟点击指令分发成功！")
                                        log("自动化测试流程全部执行完毕")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
            .replace("\"", "") // 去掉所有双引号，呈现干净的伪 YAML 视觉效果
            .replace(Regex("\\{\\s*\\}"), "{}")
            .replace(Regex("\\[\\s*\\]"), "[]")
    }
}