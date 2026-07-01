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
import xyz.kbrowser.webview.AxTreeData
import xyz.kbrowser.webview.SnapshotMode
import xyz.kbrowser.webview.getCleanedAxTree
import xyz.kbrowser.webview.toYamlSnapshot

data class BrowserViewState(
    val page: KBPage? = null,
    val logs: List<String> = listOf("自动化工具初始化完成"),
    val snapshotText: String = "",
    val snapshotSearchQuery: String = "",
    val selectorsText: String = "",
    val htmlPreview: String = "",
    val navigateUrlInput: String = "https://www.bing.com/",
    val customSelector: String = "",
    val selectedTab: Int = 0,
    val coordX: String = "250",
    val coordY: String = "450",
    val coordDeltaX: String = "0",
    val coordDeltaY: String = "300",
    val dragEndX: String = "400",
    val dragEndY: String = "450",
    val isLoading: Boolean = false,
    val keyboardInputText: String = "Hello KBrowser",
    val locatorSelector: String = "",
    val locatorSelectorType: String = "CSS",
    val locatorRoleName: String = "",
    val locatorValue: String = "",
    val customRefId: String = "",
    val screenshotBytes: ByteArray? = null,
    val screenshotAxTree: AxTreeData? = null
)

sealed interface BrowserIntent {
    data class ChangeNavigateUrl(val url: String) : BrowserIntent
    data class ChangeCustomSelector(val selector: String) : BrowserIntent
    data class ChangeSnapshotSearch(val query: String) : BrowserIntent
    data class ChangeCoordX(val x: String) : BrowserIntent
    data class ChangeCoordY(val y: String) : BrowserIntent
    data class ChangeCoordDeltaX(val v: String) : BrowserIntent
    data class ChangeCoordDeltaY(val v: String) : BrowserIntent
    data class ChangeDragEndX(val v: String) : BrowserIntent
    data class ChangeDragEndY(val v: String) : BrowserIntent
    data class ChangeTab(val tab: Int) : BrowserIntent
    object Navigate : BrowserIntent
    object ClearLogs : BrowserIntent
    object FetchHtml : BrowserIntent
    object FetchSemanticSnapshot : BrowserIntent
    object FetchSelectors : BrowserIntent
    data class ClickSelector(val selector: String) : BrowserIntent
    data class ClickCoordinates(val x: Int, val y: Int) : BrowserIntent
    data class HoverCoordinates(val x: Int, val y: Int) : BrowserIntent
    data class ScrollCoordinates(val x: Int, val y: Int, val deltaX: Int, val deltaY: Int) : BrowserIntent
    data class DragCoordinates(val startX: Int, val startY: Int, val endX: Int, val endY: Int) : BrowserIntent
    object RunAutoFlow : BrowserIntent

    // 键盘按键与输入测试
    data class ChangeKeyboardInput(val text: String) : BrowserIntent
    object SimulateTypeString : BrowserIntent
    object SimulateCtrlA : BrowserIntent
    object SimulateCmdA : BrowserIntent
    object SimulateBackspace : BrowserIntent
    object SimulateEnter : BrowserIntent
    object SimulateEscape : BrowserIntent
    object SimulateTab : BrowserIntent

    // KBLocator 测试意图
    data class ChangeLocatorSelector(val selector: String) : BrowserIntent
    data class ChangeLocatorSelectorType(val type: String) : BrowserIntent
    data class ChangeRefId(val refId: String) : BrowserIntent
    object ClickRefId : BrowserIntent
    object HoverRefId : BrowserIntent
    object ScrollRefId : BrowserIntent
    data class ChangeLocatorRoleName(val name: String) : BrowserIntent
    data class ChangeLocatorValue(val value: String) : BrowserIntent
    object LocatorClick : BrowserIntent
    object LocatorHover : BrowserIntent
    object LocatorFocus : BrowserIntent
    object LocatorCheck : BrowserIntent
    object LocatorFill : BrowserIntent
    object LocatorType : BrowserIntent
    object LocatorScroll : BrowserIntent
    object LocatorSelectOption : BrowserIntent
    object LocatorPress : BrowserIntent
    object LocatorGetText : BrowserIntent
    object LocatorIsVisible : BrowserIntent
    object LocatorCount : BrowserIntent
    object LocatorBoundingBox : BrowserIntent
    object LocatorGetAttribute : BrowserIntent
    object OverlayRawAxTree : BrowserIntent
    object OverlayCleanedAxTree : BrowserIntent
    object ClearOverlay : BrowserIntent
    object TakeScreenshot : BrowserIntent

    // 会话管理
    object ClearCacheAndCookies : BrowserIntent

    // 交互锁定
    object LockInteraction : BrowserIntent
    object UnlockInteraction : BrowserIntent
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
                val newPage = KBrowser.newPage()
                println("[DEBUG] BrowserViewModel: KBrowser.newPage 返回成功")
                _state.update { it.copy(page = newPage) }
                // 监听新窗口请求，print URL
                newPage.onNewPage = { url ->
                    println("[NEW_WINDOW] 页面请求打开新窗口: $url")
                    log("🔗 新窗口请求: $url")
                }
                val initialUrl = _state.value.navigateUrlInput
                if (!initialUrl.isNullOrBlank()) {
                    newPage.loadUrl(initialUrl)
                }
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
            is BrowserIntent.ChangeSnapshotSearch -> {
                _state.update { it.copy(snapshotSearchQuery = intent.query) }
            }
            is BrowserIntent.ChangeCoordX -> {
                _state.update { it.copy(coordX = intent.x) }
            }
            is BrowserIntent.ChangeCoordY -> {
                _state.update { it.copy(coordY = intent.y) }
            }
            is BrowserIntent.ChangeCoordDeltaX -> {
                _state.update { it.copy(coordDeltaX = intent.v) }
            }
            is BrowserIntent.ChangeCoordDeltaY -> {
                _state.update { it.copy(coordDeltaY = intent.v) }
            }
            is BrowserIntent.ChangeDragEndX -> {
                _state.update { it.copy(dragEndX = intent.v) }
            }
            is BrowserIntent.ChangeDragEndY -> {
                _state.update { it.copy(dragEndY = intent.v) }
            }
            is BrowserIntent.ChangeTab -> {
                _state.update { it.copy(selectedTab = intent.tab) }
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
                log("开始抓取 Snapshot（YAML 格式 & 原始 JSON）...")
                viewModelScope.launch {
                    try {
                        val result = page.snapshot(SnapshotMode.VIEWPORT)
                        val yaml = result.yaml
                        val rawTree = result.rawTree
                        
                        val jsonParser = kotlinx.serialization.json.Json { 
                            prettyPrint = true
                            ignoreUnknownKeys = true
                        }
                        val json = jsonParser.encodeToString(xyz.kbrowser.webview.AxTreeData.serializer(), rawTree)
                        
                        println("========== SNAPSHOT ==========")
                        println(yaml)
                        println("========== END SNAPSHOT ==========")
                        
//                        println("========== RAW JSON SNAPSHOT ==========")
//                        println(json)
//                        println("========== END RAW JSON SNAPSHOT ==========")
                        
                        _state.update { it.copy(snapshotText = yaml) }
                        log("Snapshot 抓取完成")
                    } catch (e: Exception) {
                        log("Snapshot 抓取失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.FetchSelectors -> {
                val page = _state.value.page ?: return
                log("开始抓取选择器（全量DOM）...")
                viewModelScope.launch {
                    try {
                        val rawAxTree = page.snapshot().rawTree
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
                if (intent.selector == "ComposeHoverClick") {
                    log("成功捕获原生 Compose 按钮点击！证明 interop blending 运转完美！")
                    return
                }
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
                println("[CHAIN-01] VM.ClickCoordinates received x=${intent.x} y=${intent.y} page=$page")
                log("执行原生坐标虚拟点击: (${intent.x}, ${intent.y})")
                viewModelScope.launch {
                    try {
                        println("[CHAIN-02] VM about to call page.clickByCoordinates")
                        page.clickByCoordinates(intent.x, intent.y)
                        println("[CHAIN-03] VM page.clickByCoordinates returned")
                        log("坐标虚拟点击指令发送成功！")
                    } catch (e: Exception) {
                        println("[CHAIN-ERR] VM exception: ${e.message}")
                        e.printStackTrace()
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
            is BrowserIntent.ScrollCoordinates -> {
                val page = _state.value.page ?: return
                log("执行坐标滚动: (${intent.x}, ${intent.y}) delta=(${intent.deltaX}, ${intent.deltaY})")
                viewModelScope.launch {
                    try {
                        page.scrollByCoordinates(intent.x, intent.y, intent.deltaX, intent.deltaY)
                        log("✅ 坐标滚动成功")
                    } catch (e: Exception) {
                        log("❌ 坐标滚动失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.DragCoordinates -> {
                val page = _state.value.page ?: return
                log("执行坐标拖拽: (${intent.startX}, ${intent.startY}) → (${intent.endX}, ${intent.endY})")
                viewModelScope.launch {
                    try {
                        page.dragByCoordinates(intent.startX, intent.startY, intent.endX, intent.endY)
                        log("✅ 坐标拖拽成功")
                    } catch (e: Exception) {
                        log("❌ 坐标拖拽失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.RunAutoFlow -> {
                val page = _state.value.page ?: return
                viewModelScope.launch {
                    log("启动一键自动化测试流程...")
                    try {
                        log("[步骤 1] 正在提取 Aria 语义快照树...")
                        val snapshotResult = page.snapshot(SnapshotMode.VIEWPORT)
                        val rawAxTree = snapshotResult.rawTree
                        val snapshot = snapshotResult.yaml
                        log("[步骤 1] 语义树提取成功（原始节点数: ${rawAxTree.nodes.size}）")

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
            is BrowserIntent.ChangeKeyboardInput -> {
                _state.update { it.copy(keyboardInputText = intent.text) }
            }
            is BrowserIntent.SimulateTypeString -> {
                val page = _state.value.page ?: return
                val text = _state.value.keyboardInputText
                log("模拟键盘物理打字输入: \"$text\"")
                viewModelScope.launch {
                    try {
                        page.type(text)
                        log("物理打字输入发送完毕")
                    } catch (e: Exception) {
                        log("物理打字输入失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.SimulateCtrlA -> {
                val page = _state.value.page ?: return
                log("模拟键盘组合键: Ctrl+A")
                viewModelScope.launch {
                    try {
                        page.pressKeyCombination(xyz.kbrowser.webview.KeyboardKey.CONTROL, xyz.kbrowser.webview.KeyboardKey.A)
                        log("组合键 Ctrl+A 发送完毕")
                    } catch (e: Exception) {
                        log("组合键 Ctrl+A 发送失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.SimulateCmdA -> {
                val page = _state.value.page ?: return
                log("模拟键盘组合键: Cmd+A (Mac)")
                viewModelScope.launch {
                    try {
                        page.pressKeyCombination(xyz.kbrowser.webview.KeyboardKey.META, xyz.kbrowser.webview.KeyboardKey.A)
                        log("组合键 Cmd+A 发送完毕")
                    } catch (e: Exception) {
                        log("组合键 Cmd+A 发送失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.SimulateBackspace -> {
                val page = _state.value.page ?: return
                log("模拟键盘单个按键: Backspace")
                viewModelScope.launch {
                    try {
                        page.press(xyz.kbrowser.webview.KeyboardKey.BACKSPACE)
                        log("按键 Backspace 发送完毕")
                    } catch (e: Exception) {
                        log("按键 Backspace 发送失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.SimulateEnter -> {
                val page = _state.value.page ?: return
                viewModelScope.launch {
                    try {
                        page.press(xyz.kbrowser.webview.KeyboardKey.ENTER)
                        log("按键 Enter 发送完毕")
                    } catch (e: Exception) {
                        log("按键 Enter 发送失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.SimulateEscape -> {
                val page = _state.value.page ?: return
                viewModelScope.launch {
                    try {
                        page.press(xyz.kbrowser.webview.KeyboardKey.ESCAPE)
                        log("按键 Escape 发送完毕")
                    } catch (e: Exception) {
                        log("按键 Escape 发送失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.SimulateTab -> {
                val page = _state.value.page ?: return
                viewModelScope.launch {
                    try {
                        page.press(xyz.kbrowser.webview.KeyboardKey.TAB)
                        log("按键 Tab 发送完毕")
                    } catch (e: Exception) {
                        log("按键 Tab 发送失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.ChangeLocatorSelector -> {
                _state.update { it.copy(locatorSelector = intent.selector) }
            }
            is BrowserIntent.ChangeLocatorSelectorType -> {
                _state.update { it.copy(locatorSelectorType = intent.type) }
            }
            is BrowserIntent.ChangeRefId -> {
                _state.update { it.copy(customRefId = intent.refId) }
            }
            BrowserIntent.ClickRefId -> {
                val page = _state.value.page ?: return
                val refId = _state.value.customRefId
                if (refId.isBlank()) return
                log("执行 Aria RefId 点击: '$refId'")
                viewModelScope.launch {
                    try {
                        page.click(refId)
                        log("✅ 点击 $refId 成功")
                    } catch (e: Exception) {
                        log("❌ 点击失败: ${e.message}")
                    }
                }
            }
            BrowserIntent.HoverRefId -> {
                val page = _state.value.page ?: return
                val refId = _state.value.customRefId
                if (refId.isBlank()) return
                log("执行 Aria RefId 悬停: '$refId'")
                viewModelScope.launch {
                    try {
                        page.hover(refId)
                        log("✅ 悬停 $refId 成功")
                    } catch (e: Exception) {
                        log("❌ 悬停失败: ${e.message}")
                    }
                }
            }
            BrowserIntent.ScrollRefId -> {
                val page = _state.value.page ?: return
                val refId = _state.value.customRefId
                val deltaX = _state.value.coordDeltaX.toIntOrNull() ?: 0
                val deltaY = _state.value.coordDeltaY.toIntOrNull() ?: 300
                if (refId.isBlank()) return
                log("执行 RefId 滚动: '$refId' delta=($deltaX, $deltaY)")
                viewModelScope.launch {
                    try {
                        page.scroll(refId, deltaX, deltaY)
                        log("✅ 滚动 $refId 成功")
                    } catch (e: Exception) {
                        log("❌ 滚动失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.ChangeLocatorRoleName -> {
                _state.update { it.copy(locatorRoleName = intent.name) }
            }
            is BrowserIntent.ChangeLocatorValue -> {
                _state.update { it.copy(locatorValue = intent.value) }
            }
            is BrowserIntent.LocatorClick -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                if (selector.isBlank()) {
                    log("错误: 选择器不能为空")
                    return
                }
                log("执行 Locator($typeStr='$selector').click()")
                viewModelScope.launch {
                    try {
                        val locator = createLocator(page, selector, typeStr, roleName)
                        locator.click()
                        log("Locator 点击成功")
                    } catch (e: Exception) {
                        log("Locator 点击失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.LocatorHover -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                if (selector.isBlank()) {
                    log("错误: 选择器不能为空")
                    return
                }
                log("执行 Locator($typeStr='$selector').hover()")
                viewModelScope.launch {
                    try {
                        val locator = createLocator(page, selector, typeStr, roleName)
                        locator.hover()
                        log("Locator 悬停成功")
                    } catch (e: Exception) {
                        log("Locator 悬停失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.LocatorFocus -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                if (selector.isBlank()) {
                    log("错误: 选择器不能为空")
                    return
                }
                log("执行 Locator($typeStr='$selector').focus()")
                viewModelScope.launch {
                    try {
                        val locator = createLocator(page, selector, typeStr, roleName)
                        locator.focus()
                        log("Locator 聚焦成功")
                    } catch (e: Exception) {
                        log("Locator 聚焦失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.LocatorCheck -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                if (selector.isBlank()) {
                    log("错误: 选择器不能为空")
                    return
                }
                log("执行 Locator($typeStr='$selector').check()")
                viewModelScope.launch {
                    try {
                        val locator = createLocator(page, selector, typeStr, roleName)
                        locator.check()
                        log("Locator 勾选成功")
                    } catch (e: Exception) {
                        log("Locator 勾选失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.LocatorFill -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                val value = _state.value.locatorValue
                if (selector.isBlank()) {
                    log("错误: 选择器不能为空")
                    return
                }
                log("执行 Locator($typeStr='$selector').fill(\"$value\")")
                viewModelScope.launch {
                    try {
                        val locator = createLocator(page, selector, typeStr, roleName)
                        locator.fill(value)
                        log("Locator 填充完毕")
                    } catch (e: Exception) {
                        log("Locator 填充失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.LocatorType -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                val value = _state.value.locatorValue
                if (selector.isBlank()) {
                    log("错误: 选择器不能为空")
                    return
                }
                log("执行 Locator($typeStr='$selector').type(\"$value\")")
                viewModelScope.launch {
                    try {
                        val locator = createLocator(page, selector, typeStr, roleName)
                        locator.type(value)
                        log("Locator 物理打字完毕")
                    } catch (e: Exception) {
                        log("Locator 物理打字失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.LocatorGetText -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                if (selector.isBlank()) {
                    log("错误: 选择器不能为空")
                    return
                }
                log("执行 Locator($typeStr='$selector').getText()")
                viewModelScope.launch {
                    try {
                        val locator = createLocator(page, selector, typeStr, roleName)
                        val text = locator.getText()
                        log("Locator 获取文本成功: \"$text\"")
                    } catch (e: Exception) {
                        log("Locator 获取文本失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.LocatorIsVisible -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                if (selector.isBlank()) {
                    log("错误: 选择器不能为空")
                    return
                }
                log("执行 Locator($typeStr='$selector').isVisible()")
                viewModelScope.launch {
                    try {
                        val locator = createLocator(page, selector, typeStr, roleName)
                        val visible = locator.isVisible()
                        log("Locator 可见性验证结果: $visible")
                    } catch (e: Exception) {
                        log("Locator 可见性验证失败: ${e.message}")
                    }
                }
            }
            is BrowserIntent.LocatorScroll -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                val deltaX = _state.value.coordDeltaX.toIntOrNull() ?: 0
                val deltaY = _state.value.coordDeltaY.toIntOrNull() ?: 300
                if (selector.isBlank()) { log("错误: 选择器不能为空"); return }
                log("执行 Locator($typeStr='$selector').scroll(deltaX=$deltaX, deltaY=$deltaY)")
                viewModelScope.launch {
                    try {
                        createLocator(page, selector, typeStr, roleName).scroll(deltaX, deltaY)
                        log("✅ Locator 滚动成功")
                    } catch (e: Exception) { log("❌ Locator 滚动失败: ${e.message}") }
                }
            }
            is BrowserIntent.LocatorSelectOption -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                val value = _state.value.locatorValue
                if (selector.isBlank()) { log("错误: 选择器不能为空"); return }
                log("执行 Locator($typeStr='$selector').selectOption(\"$value\")")
                viewModelScope.launch {
                    try {
                        createLocator(page, selector, typeStr, roleName).selectOption(value)
                        log("✅ Locator 选项选择成功")
                    } catch (e: Exception) { log("❌ Locator 选项选择失败: ${e.message}") }
                }
            }
            is BrowserIntent.LocatorPress -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                val value = _state.value.locatorValue
                if (selector.isBlank()) { log("错误: 选择器不能为空"); return }
                log("执行 Locator($typeStr='$selector').press(Enter)")
                viewModelScope.launch {
                    try {
                        createLocator(page, selector, typeStr, roleName).press(xyz.kbrowser.webview.KeyboardKey.ENTER)
                        log("✅ Locator 按键成功")
                    } catch (e: Exception) { log("❌ Locator 按键失败: ${e.message}") }
                }
            }
            is BrowserIntent.LocatorCount -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                if (selector.isBlank()) { log("错误: 选择器不能为空"); return }
                log("执行 Locator($typeStr='$selector').count()")
                viewModelScope.launch {
                    try {
                        val count = createLocator(page, selector, typeStr, roleName).count()
                        log("✅ Locator 匹配数量: $count")
                    } catch (e: Exception) { log("❌ Locator count 失败: ${e.message}") }
                }
            }
            is BrowserIntent.LocatorBoundingBox -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                if (selector.isBlank()) { log("错误: 选择器不能为空"); return }
                log("执行 Locator($typeStr='$selector').boundingBox()")
                viewModelScope.launch {
                    try {
                        val box = createLocator(page, selector, typeStr, roleName).boundingBox()
                        if (box != null) log("✅ BoundingBox: x=${box.x} y=${box.y} w=${box.width} h=${box.height}")
                        else log("⚠️ BoundingBox: 元素未找到")
                    } catch (e: Exception) { log("❌ Locator boundingBox 失败: ${e.message}") }
                }
            }
            is BrowserIntent.LocatorGetAttribute -> {
                val page = _state.value.page ?: return
                val selector = _state.value.locatorSelector
                val typeStr = _state.value.locatorSelectorType
                val roleName = _state.value.locatorRoleName
                val attrName = _state.value.locatorValue.ifBlank { "href" }
                if (selector.isBlank()) { log("错误: 选择器不能为空"); return }
                log("执行 Locator($typeStr='$selector').getAttribute(\"$attrName\")")
                viewModelScope.launch {
                    try {
                        val v = createLocator(page, selector, typeStr, roleName).getAttribute(attrName)
                        log("✅ getAttribute($attrName) = \"$v\"")
                    } catch (e: Exception) { log("❌ Locator getAttribute 失败: ${e.message}") }
                }
            }
            BrowserIntent.ClearCacheAndCookies -> {
                val page = _state.value.page ?: return
                log("正在清除缓存和 Cookie...")
                viewModelScope.launch {
                    try {
                        page.clearCacheAndCookies()
                        log("✅ 缓存和 Cookie 已清除")
                    } catch (e: Exception) { log("❌ 清除失败: ${e.message}") }
                }
            }
            BrowserIntent.LockInteraction -> {
                val page = _state.value.page ?: return
                page.setInteractionLocked(true)
                log("🔒 交互已锁定（用户无法操作，自动化正常）")
            }
            BrowserIntent.UnlockInteraction -> {
                val page = _state.value.page ?: return
                page.setInteractionLocked(false)
                log("🔓 交互已解锁")
            }
            BrowserIntent.TakeScreenshot -> {
                val page = _state.value.page ?: return
                log("正在捕获网页截图（同步抓取 AXTree 用于叠加框框）...")
                viewModelScope.launch {
                    try {
                        // 并行抓截图和 AXTree
                        val bytes = page.webView.takeScreenshot()?.imageData
                        val axTree = try { page.snapshot().rawTree } catch (e: Exception) { null }
                        if (bytes != null) {
                            _state.update { it.copy(screenshotBytes = bytes, screenshotAxTree = axTree) }
                            log("截图成功！大小: ${bytes.size} 字节，AXTree 节点数: ${axTree?.nodes?.size ?: 0}")
                            xyz.kbrowser.webview.showScreenshotPreview(bytes)
                        } else {
                            log("截图失败：未获取到图像字节")
                        }
                    } catch (e: Exception) {
                        log("截图失败，发生异常: ${e.message}")
                    }
                }
            }
            BrowserIntent.OverlayRawAxTree -> {
                val page = _state.value.page ?: return
                log("正在抓取原始 AXTree 并画框...")
                viewModelScope.launch {
                    try {
                        val axTree = page.snapshot().rawTree
                        log("原始节点数: ${axTree.nodes.size}，注入画框 JS...")
                        page.evaluateJavascript(buildOverlayJs(axTree.nodes, cleaned = false))
                        log("✅ 原始 AXTree 框框已画出（蓝色=可见，灰色=不可见）")
                    } catch (e: Exception) {
                        log("画框失败: ${e.message}")
                    }
                }
            }
            BrowserIntent.OverlayCleanedAxTree -> {
                val page = _state.value.page ?: return
                log("正在抓取清洗后 AXTree 并画框...")
                viewModelScope.launch {
                    try {
                        val axTree = page.snapshot().rawTree.getCleanedAxTree()
                        log("清洗后节点数: ${axTree.nodes.size}，注入画框 JS...")
                        page.evaluateJavascript(buildOverlayJs(axTree.nodes, cleaned = true))
                        log("✅ 清洗后 AXTree 框框已画出（绿色=可见，灰色=不可见）")
                    } catch (e: Exception) {
                        log("画框失败: ${e.message}")
                    }
                }
            }
            BrowserIntent.ClearOverlay -> {
                val page = _state.value.page ?: return
                viewModelScope.launch {
                    try {
                        page.evaluateJavascript("""
                            (function(){
                                var el = document.getElementById('__kb_overlay__');
                                if (el) el.remove();
                            })()
                        """.trimIndent())
                        log("框框已清除")
                    } catch (e: Exception) {
                        log("清除失败: ${e.message}")
                    }
                }
            }
        }
    }

    private fun buildOverlayJs(nodes: List<xyz.kbrowser.webview.AxNode>, cleaned: Boolean): String {
        // 每个节点生成一个绝对定位的 div 框
        val color = if (cleaned) "rgba(0,200,80,0.5)" else "rgba(0,120,255,0.5)"
        val labelBg = if (cleaned) "rgba(0,160,60,0.85)" else "rgba(0,80,200,0.85)"
        val rects = nodes.filter { it.isVisible && it.width > 0 && it.height > 0 }
            .joinToString("\n") { n ->
                val label = "${n.refid} ${n.role} ${n.text.take(12).replace("'", "\\'")}".trim()
                """  addBox(${n.x},${n.y},${n.width},${n.height},'${label}');"""
            }
        return """
(function(){
  var old = document.getElementById('__kb_overlay__');
  if (old) old.remove();
  var layer = document.createElement('div');
  layer.id = '__kb_overlay__';
  layer.style.cssText = 'position:absolute;top:0;left:0;width:0;height:0;pointer-events:none;z-index:2147483647;';
  document.documentElement.appendChild(layer);
  function addBox(x,y,w,h,label){
    var d = document.createElement('div');
    d.style.cssText = 'position:absolute;left:'+x+'px;top:'+y+'px;width:'+w+'px;height:'+h+'px;outline:1.5px solid ${color};box-sizing:border-box;';
    var t = document.createElement('span');
    t.textContent = label;
    t.style.cssText = 'position:absolute;top:0;left:0;font-size:9px;line-height:1.2;background:${labelBg};color:#fff;padding:0 2px;max-width:'+w+'px;overflow:hidden;white-space:nowrap;pointer-events:none;';
    d.appendChild(t);
    layer.appendChild(d);
  }
$rects
})()
        """.trimIndent()
    }

    private fun createLocator(page: KBPage, selector: String, typeStr: String, roleName: String): xyz.kbrowser.webview.KBLocator {
        val type = when (typeStr) {
            "XPath" -> xyz.kbrowser.webview.KBSelectorType.XPATH
            "Text" -> xyz.kbrowser.webview.KBSelectorType.TEXT
            "Role" -> xyz.kbrowser.webview.KBSelectorType.ROLE
            "Placeholder" -> xyz.kbrowser.webview.KBSelectorType.PLACEHOLDER
            "Title" -> xyz.kbrowser.webview.KBSelectorType.TITLE
            "TestId" -> xyz.kbrowser.webview.KBSelectorType.TEST_ID
            "Label" -> xyz.kbrowser.webview.KBSelectorType.LABEL
            "AltText" -> xyz.kbrowser.webview.KBSelectorType.ALT_TEXT
            else -> xyz.kbrowser.webview.KBSelectorType.CSS
        }
        return if (type == xyz.kbrowser.webview.KBSelectorType.ROLE) {
            page.getByRole(selector, roleName.takeIf { it.isNotEmpty() })
        } else {
            xyz.kbrowser.webview.KBLocator(page, selector, type)
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