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

// 用它的 selector 操作
page.locator(searchBox.selector).click()
page.locator(searchBox.selector).fill("搜索内容")
page.locator(searchBox.selector).type("逐字符输入")
```

### 3. 在测试面板中使用

1. 点击 **Aria** 按钮获取 AXTree
2. 在 Aria Tab 中找到目标节点的 `selector` 字段值
3. 复制该值（注意：JSON 中的 `\"` 实际是普通引号 `"`，粘贴时去掉反斜杠）
4. 在 KBLocator 区域：选择器类型选 **CSS**，粘贴选择器
5. 填入测试值，点击对应操作按钮

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

## 选择器 vs refid

两种方式都能操作元素，区别：

| | selector | refid |
|--|---------|-------|
| 操作方式 | `page.locator(selector).click()` | `page.click(refid)` |
| 底层机制 | 重新查找 DOM 节点 → 取坐标 → 点击 | 直接用缓存坐标 → 点击 |
| 适用场景 | 需要 fill/type/check 等复合操作 | 简单点击/悬停/滚动 |
| 抗 DOM 变化 | 每次重新查找，更可靠 | 依赖缓存，DOM 变化后可能坐标偏移 |

推荐：
- 简单点击用 `page.click(refid)` — 最快，直接用坐标
- 输入/填充用 `page.locator(selector).fill(value)` — 需要先聚焦再操作
- 复杂交互用 `page.locator(selector)` — 支持 fill/type/check/selectOption 等

---

## AI Agent 集成建议

给 AI 的 AXTree 已经包含 selector 字段，AI 可以直接使用：

```
AI 收到 AXTree JSON → 找到目标节点 → 读取 selector 字段 → 调用 locator(selector).click()
```

AI 不需要自己推断选择器，不需要看 HTML，不需要理解 CSS 选择器语法。它只需要：
1. 从 AXTree 中根据 role/text/位置判断哪个是目标节点
2. 取出该节点的 `selector` 字段
3. 原样传给 `page.locator(selector)` 执行操作

---

## 常见问题

**Q: 结构路径太长，会影响性能吗？**
A: 不会。`querySelector` 对结构路径的查找是 O(depth)，现代浏览器处理几十层嵌套也是微秒级。

**Q: 页面动态加载后选择器还有效吗？**
A: 如果 DOM 结构没变就有效。如果有新元素插入导致 nth-of-type 偏移，需要重新调用 `getRawAxTree()` 获取新的选择器。

**Q: fill 有效但 type 无效？**
A: `fill` 通过 JS 直接设值，不需要坐标聚焦。`type` 需要先坐标点击聚焦再逐字符输入，如果元素被遮挡或坐标偏移会失败。遇到这种情况用 `fill` 即可。

**Q: 多个元素有相同的 name 属性怎么办？**
A: 不用担心。选择器生成算法会验证唯一性，如果 `input[name="query"]` 匹配多个元素，会自动降级到结构路径，保证每个节点的 selector 只匹配自己。
