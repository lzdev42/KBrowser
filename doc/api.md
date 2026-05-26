# KBrowser API Reference

本文档旨在提供 KBrowser 引擎全套自动化与底层交互 API 的规范说明。
KBrowser 架构以 `KBWebView` 为纯净渲染底层，向上抽象出 `KBPage` 与 `KBLocator` 以提供类 Playwright 的语义化自动化测试接口，所有键盘及鼠标交互均采用底层原生防检测机制（坐标注入、CDP Chrome DevTools Protocol）。

---

## 1. 入口与页面管理 (KBrowser)

`KBrowser` 是框架的主入口模块，负责管理会话与标签页生命周期。

### `KBrowser.newPage(url: String, profile: KBProfile?, isOsr: Boolean): KBPage`
- **功能描述**：创建并返回一个新的浏览器标签页（页面级上下文）。
- **参数说明**：
  - `url`: 初始加载的统一资源定位符（可为 `about:blank` 或 `data:` 协议）。
  - `profile`: 会话隔离配置。具有相同 profileId 的页面共享 Cookie 和缓存；为 null 时将使用全局默认会话。
  - `isOsr`: 是否启用离屏渲染（Off-Screen Rendering）。开启时页面将不依赖系统级窗口句柄，适用于无头（Headless）自动化。
- **返回类型**：`KBPage`，代表当前创建的活跃页面。

---

## 2. 页面级核心控制 (KBPage)

`KBPage` 代表单一网页会话实例，负责路由导航、JS 通信及原生交互分发。

### 2.1 导航与状态
- **`suspend fun loadUrl(url: String)`**
  加载指定网页，挂起当前协程直至网页 `onPageFinished` 回调触发。若加载失败将抛出异常。
- **`val currentUrl: StateFlow<String?>`**
  响应式的当前完整 URL 状态流。
- **`val title: StateFlow<String?>`**
  响应式的网页标题状态流。
- **`val loadingState: StateFlow<LoadingState>`**
  当前页面的实时加载状态流，状态包含：`Initializing` (初始化), `Loading` (载入中), `Finished` (载入结束), `Error` (加载失败及错误码)。
- **`val progress: StateFlow<Float>`**
  实时加载进度条数据流，范围从 `0.0f` 到 `1.0f`。
- **`suspend fun clearCacheAndCookies()`**
  清空当前引擎实例的所有网络缓存及会话 Cookie。

### 2.2 脚本与语义提取
- **`suspend fun evaluateJavascript(script: String): String`**
  向当前页面的 V8 引擎注入并执行 JavaScript 脚本，返回序列化后的执行结果字符串。
- **`suspend fun getRawAxTree(): AxTreeData`**
  获取当前页面的全局 Aria 语义快照树。返回的树形结构经过自动剪枝（去除无意义容器），包含节点的屏幕物理坐标及语义特征。

### 2.3 物理坐标模拟 (底层原生输入)
直接绕过 DOM 机制，通过底层事件引擎向页面注入鼠标与滚轮信号。
- **`suspend fun clickByCoordinates(x: Int, y: Int)`**
  模拟物理鼠标移动并在此坐标处触发左键单击。
- **`suspend fun hoverByCoordinates(x: Int, y: Int)`**
  模拟物理鼠标移动至指定坐标。
- **`suspend fun scrollByCoordinates(x: Int, y: Int, deltaX: Int, deltaY: Int)`**
  在此坐标处触发物理滚轮滚动事件。
- **`suspend fun dragByCoordinates(startX: Int, startY: Int, endX: Int, endY: Int)`**
  模拟鼠标从起点按下并拖拽至终点的行为。

### 2.4 物理键盘模拟 (CDP 底层协议)
通过 Chrome DevTools Protocol (CDP) 底层协议直接写入虚拟键盘代码，具备100%防风控属性（不触发 webdriver 检测）。
- **`fun press(key: KeyboardKey)`**
  模拟物理敲击单一控制键或功能键（如 `ENTER`, `BACKSPACE`, `ESCAPE`）。
- **`fun pressKeyCombination(modifier: KeyboardKey, key: KeyboardKey)`**
  模拟物理敲击组合键（如 `META` + `A` 代表 macOS 下的 Cmd+A 全选）。
- **`fun typeChar(char: Char)`**
  模拟物理输入单一字符。
- **`suspend fun type(text: String)`**
  模拟人类真实的连续打字行为。方法内部包含 `30ms ~ 150ms` 的随机物理延迟。

---

## 3. 元素定位与交互 (KBLocator Factory)

采用基于语义与选择器的延迟计算定位机制，方法链式调用。生成 `KBLocator` 实例：

- **`fun locator(selector: String): KBLocator`**
  通过 CSS 选择器或 XPath (`xpath=//...`) 寻找元素。
- **`fun getByRole(role: String, name: String? = null): KBLocator`**
  基于 W3C Aria 语义规范定位元素。
- **`fun getByText(text: String, exact: Boolean = true): KBLocator`**
  基于视觉呈现的文本内容定位元素。
- **`fun getByLabel(label: String): KBLocator`**
  基于 `<label>` 关联的表单元素定位。
- **`fun getByPlaceholder(text: String): KBLocator`**
  基于表单占位符（Placeholder）定位元素。
- **`fun getByTestId(testId: String): KBLocator`**
  基于 `data-testid` 属性进行测试靶点定位。

---

## 4. 节点交互 (KBLocator)

一旦获得 `KBLocator` 实例，可通过以下 API 触发物理交互。所有操作在执行前都会自动执行可见性校验，并利用坐标系统(`x, y`)触发原生事件，**绝不通过 JavaScript 触发 `.click()`，确保绝对的隐蔽性**。

### 4.1 节点操作
- **`suspend fun click()`**
  将鼠标物理移动至该元素的几何中心点，并触发点击。
- **`suspend fun hover()`**
  将鼠标物理悬停于该元素的几何中心点。
- **`suspend fun fill(value: String)`**
  物理点击该元素获取焦点，随后将目标值赋予该元素（内部使用原生事件填充，并辅以 JS 强制变更）。
- **`suspend fun type(text: String)`**
  物理点击该元素获取焦点，**执行 Cmd/Ctrl+A 和 Delete 清空原内容**，然后调用 `KBPage.type` 执行带有随机延迟的逐字敲击。
- **`suspend fun press(key: KeyboardKey)`**
  物理点击获取该元素的焦点后，按下指定功能键。
- **`suspend fun selectOption(value: String)`**
  处理原生的下拉菜单（Select）。物理点击展开菜单后，计算并点击对应选项的坐标。

### 4.2 节点检索与断言
- **`suspend fun isVisible(): Boolean`**
  该元素当前是否挂载于 DOM 且几何可见。
- **`suspend fun getText(): String`**
  提取该元素的内部纯文本（包含其子节点文本）。
- **`suspend fun getAttribute(name: String): String?`**
  获取该元素指定的 DOM 属性。
- **`suspend fun count(): Int`**
  返回当前定位规则匹配到的元素总数。
- **`suspend fun boundingBox(): Rect?`**
  获取该元素相对于视口的当前物理坐标边界盒（x, y, width, height）。

### 4.3 定位器链式筛选
- **`fun nth(index: Int): KBLocator`**
  获取匹配集合中的第 `index` 个元素，支持负数索引（如 `-1` 为最后一个）。
- **`fun first(): KBLocator`** / **`fun last(): KBLocator`**
  快速筛选首个或末尾元素。
- **`fun filter(predicate: (LocateResult) -> Boolean): KBLocator`**
  基于运行时解析结果执行自定义代码级断言过滤。

---

## 5. 键盘枚举规范 (KeyboardKey)

提供平台无关的标准虚拟键位映射枚举 `xyz.kbrowser.webview.KeyboardKey`：

- **基础按键**：`ENTER`, `TAB`, `ESCAPE`, `BACKSPACE`, `DELETE`, `SPACE`
- **方向控制**：`ARROW_UP`, `ARROW_DOWN`, `ARROW_LEFT`, `ARROW_RIGHT`
- **修饰键**：`SHIFT`, `CONTROL`, `ALT`, `META` (对应 Mac Command 键)
- **字母与功能**：`A`, `C`, `V`, `X`, `S`, `Z`, `F1`~`F12` 等

> **注意**: 在分发组合键（如 Ctrl+A）时，引擎会自动通过底层协议（CDP）执行严谨的 DOWN/UP 状态转换闭环，开发者只需调用 `pressKeyCombination` 即可，无需手动管理修饰键状态。
