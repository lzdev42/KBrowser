# KBrowser 选择器使用指南

> [← 返回 API 参考](KBrowser_API_Reference.md)

---

## 概述

KBrowser 的 AXTree 快照为每个 DOM 节点动态生成唯一的 CSS 选择器（`AxNode.selector` 字段）。该选择器与当前快照的 DOM 状态绑定，每次调用 `getRawAxTree()` 重新生成，不会过期。

核心价值：
- **抗 anti-bot 混淆**：不依赖 class 名，antbot 改 class 无效
- **保证唯一**：每个节点的 selector 只匹配自己，不会选错
- **即用即抛**：快照时生成，操作时使用，不需要硬编码

---

## 选择器生成策略

按优先级从高到低：

| 优先级 | 策略 | 示例 | 说明 |
|--------|------|------|------|
| 1 | `#id` | `#login-btn` | id 存在且在文档中唯一时使用，最短最稳定 |
| 2 | 稳定属性 | `input[name="query"]` | data-testid / data-id / data-key / name / aria-label，唯一时使用 |
| 3 | 结构路径 | `body > div > ul > li:nth-of-type(3) > a` | 兜底方案，100% 唯一，完全不依赖 class 名 |

只有当高优先级策略验证唯一性失败时，才会降级到下一级。

---

## 遮挡检测（occludedBy）

AXTree 快照会自动检测每个节点的中心点是否被其他元素覆盖，结果写入 `occludedBy` 字段。

```json
{
  "refid": "r42",
  "role": "button",
  "text": "登录",
  "selector": "#login-btn",
  "occludedBy": "r88"
}
```

`occludedBy: "r88"` 表示：节点 r42 的中心点被 r88 覆盖，直接坐标点击会打到 r88 而不是 r42。

**处理策略**：

1. 找到 r88 是什么（通过 refid 在 AXTree 里查找）
2. 判断 r88 的类型：广告弹窗 → 找关闭按钮点击；遮罩层 → 点击关闭或等待消失
3. 处理完遮挡物后，再操作 r42

或者直接绕过坐标使用 JS 模式：

```kotlin
// 不走物理坐标，直接通过选择器以 JS 方式操作，不受遮挡影响
page.locator(node.selector).jsClick()
page.locator(node.selector).jsFill("内容")
```

`occludedBy` 为 `null` 或字段不存在时，表示该节点可以直接物理坐标点击。

**实现原理**：
- JVM：`DOM.getNodeForLocation(viewportCx, viewportCy)` 返回最顶层节点的 backendNodeId，与自身比较（纯 CDP，无 JS）
- Android/iOS：`document.elementFromPoint(clientX, clientY)` 返回最顶层元素，取其 `__kb_refid`

---

## 使用方式

### 1. 从 AXTree 获取选择器

```kotlin
val tree = page.getRawAxTree().getCleanedAxTree()

// 每个节点都有 selector 字段
tree.nodes.forEach { node ->
    println("${node.role}: ${node.text} → ${node.selector}")
}
```

AXTree JSON 输出示例：
```json
{
  "refid": "r42",
  "role": "textbox",
  "text": "",
  "selector": "input[name=\"query\"]",
  "centerX": 640,
  "centerY": 160
}
```

### 2. 用选择器操作元素

```kotlin
// 从 AXTree 找到目标节点
val searchBox = tree.nodes.first { it.role == "textbox" && it.text.isEmpty() }

// 用它的 selector 进行坐标模式操作（物理事件）
page.locator(searchBox.selector).click()
page.locator(searchBox.selector).type("逐字符输入")

// 或者直接进行 JS 模式操作（直接 DOM 事件模拟，无视遮挡）
page.locator(searchBox.selector).jsClick()
page.locator(searchBox.selector).jsFill("搜索内容")
```

### 3. 在测试面板中使用

1. 点击 **Aria** 按钮获取 AXTree
2. 在 Aria Tab 中找到目标节点的 `selector` 字段值
3. 复制该值（注意：JSON 中的 `\"` 实际是普通引号 `"`，粘贴时去掉反斜杠）
4. 在 KBLocator 区域：选择器类型选 **CSS**，粘贴选择器
5. 填入测试值，点击对应操作按钮（如 Click、JS Click、JS Fill 等）

---

## 关于 JSON 转义

AXTree 以 JSON 格式输出，JSON 字符串中的引号会被转义为 `\"`。

| JSON 中显示 | 实际值（粘贴到输入框） |
|-------------|----------------------|
| `input[name=\"query\"]` | `input[name="query"]` |
| `#login-btn` | `#login-btn` |
| `body > div:nth-of-type(1) > a` | `body > div:nth-of-type(1) > a` |

简单规则：**去掉所有 `\` 就是实际的选择器**。

---

## 选择器 vs refid 以及模式选择

KBrowser 在 `refid` 和 `selector` (Locator) 上均提供了坐标模式与 JS 模式：

| 定位方式 | 操作方法 (坐标模式) | 操作方法 (JS 模式) | 特点与底层机制 |
|---------|-------------------|------------------|--------------|
| **refid** | `page.click(refid)`<br>`page.hover(refid)` | `page.jsClick(refid)`<br>`page.jsHover(refid)` | **基于快照缓存**。坐标模式直接读取缓存坐标发送 CDP 物理事件，速度最快。JS 模式则提取缓存选择器并执行 DOM 事件注入。适合快速一次性操作。 |
| **selector**<br>(Locator) | `page.locator(sel).click()`<br>`page.locator(sel).fill(val)` | `page.locator(sel).jsClick()`<br>`page.locator(sel).jsFill(val)` | **动态延迟解析**。每次操作时重新在 DOM 树中定位该元素。坐标模式计算其实时物理坐标进行交互。JS 模式直接对解析出的 DOM 节点执行 JS。适合复合表单填充、多步流转等稳定性要求高的场景。 |

### 模式选择指南

1. **优先使用坐标模式（默认行为）**：
   * 目标是模拟真实用户操作，以便通过防爬虫检测。
   * 操作简单的按钮、链接且页面没有复杂的漂浮广告遮挡。
2. **当遇到以下情况时，切换到 JS 模式（`js*` 系列函数）**：
   * 目标元素被遮挡（例如被隐私策略弹窗、确认框覆盖）。
   * 目标元素在视口之外（不需要调用 scroll 滚动，直接操作）。
   * 需要快速填充大量表单（例如 `jsFill` 比普通物理 `type` 速度快数十倍）。
   * 需要对处于非 input 状态的自定义组件设置值。

---

## 结构路径选择器详解

当节点没有 id、没有 data-testid 等稳定属性时，会生成结构路径：

```
body > div:nth-of-type(1) > div:nth-of-type(4) > div > div:nth-of-type(4) > ul > li:nth-of-type(9) > a
```

含义：从 `body` 开始，逐层描述每个祖先的标签名和在同类兄弟中的位置。

特点：
- **绝对唯一**：路径精确描述了从根到目标的完整结构
- **不依赖 class**：antbot 混淆 class 名不影响
- **DOM 结构敏感**：如果页面 DOM 结构发生变化（插入/删除节点），路径可能失效
- **适合即时使用**：快照后立即操作，不存储不复用

---

## 常见问题

**Q: 结构路径太长，会影响性能吗？**
A: 不会。`querySelector` 对结构路径的查找是 O(depth)，现代浏览器处理几十层嵌套也是微秒级。

**Q: 页面动态加载后选择器还有效吗？**
A: 如果 DOM 结构没变就有效。如果有新元素插入导致 nth-of-type 偏移，需要重新调用 `getRawAxTree()` 获取新的选择器。

**Q: fill 有效但 type 无效？**
A: `fill` 通过 JS 直接设值，不需要坐标聚焦。`type` 需要先坐标点击聚焦再逐字符输入，如果元素被遮挡或坐标偏移会失败。遇到这种情况用 `fill` 即可。

**Q: occludedBy 字段有什么用？**
A: 标识哪个元素挡住了目标节点的中心点。`occludedBy: "r88"` 表示坐标点击会打到 r88 而非目标节点。此时需要先处理遮挡物（关闭弹窗/广告），或改用 `locator(selector).jsClick()` 绕过坐标。

**Q: 多个元素有相同的 name 属性怎么办？**
A: 不需要担心。选择器生成算法会验证唯一性，如果 `input[name="query"]` 匹配多个元素，会自动降级到结构路径，保证每个节点的 selector 只匹配自己。
