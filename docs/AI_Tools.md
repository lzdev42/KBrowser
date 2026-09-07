# KBrowser AI Tools — Agent 工具层参考

> KBrowser 内置一个传输无关的 AI 工具层（`xyz.kbrowser.toolcall`），把浏览器自动化能力收敛为
> 7 个面向 LLM agent 的工具。宿主（如 AI 驱动的桌面应用）把它注册进自己的 tool registry 即可。
> demo 里另带一个 MCP stdio transport——**仅用于开发调试**（让外部 AI 助手连上真实浏览器
> 跑 agent 循环来调试 KBrowser），不是库功能，见文末说明。

## 架构

```
shared/src/jvmMain/xyz/kbrowser/toolcall/          ← KBrowser 库（传输无关）
  BrowserTools         7 个工具：ToolSpec（名称+描述+JSON Schema）+ call() 分发
  BrowserToolSession   宿主注入接口：tab 解析/创建/关闭/列举策略
  ToolResult/ToolContent/ToolSpec/ToolTabInfo  数据模型

desktopApp/.../mcp/McpMain.kt                      ← Demo（纯传输）
  McpTransport         stdio JSON-RPC 2.0（MCP 协议 2025-06-18）
  DemoTabSession       演示性 session：新窗口自动接管、LRU 活跃 tab、last-tab 保护
```

宿主内嵌用法：

```kotlin
val tools = BrowserTools(mySessionImpl)        // mySessionImpl: BrowserToolSession
registry.register(tools.listSpecs())           // schema 注册进宿主 tool registry
val result: ToolResult = tools.call("browser_act", argsJson)
```

### Demo 自带的 MCP transport（调试工具，不是库功能）

> **这东西的定位是"开发调试后门"，不是 KBrowser 的功能。**
>
> 用途只有一个：把 demo 进程挂到你的 AI 编码助手（opencode / Claude）上，让 AI 真实
> 驱动一个浏览器跑 agent 循环，来调试 KBrowser 本身或验证 agent 工具设计。
> 实际价值已被验证：Enter 键提交失效、AX 树渲染竞态、stale refid 报错不友好——这些
> 问题全是"AI 连上 MCP 自己跑出来的"，靠写单元测试发现不了。
>
> 给宿主应用接入 AI 工具，**直接内嵌 `BrowserTools`（见上节），不要走 MCP**——内嵌
> 是进程内函数调用，没有协议开销，还能共享宿主上下文（凭据、workspace、业务逻辑）。
> MCP 只在"宿主进程外部的 AI"需要操作浏览器时才有意义。

demo 进程可以把同一套工具以 MCP stdio server 的形式暴露给外部 AI 客户端——仅是
`BrowserTools` 的一个 transport 适配器，库本身不依赖 MCP：

```bash
./gradlew :desktopApp:runMcpServer            # 或 exportMcpLaunch 生成免 Gradle 启动脚本
```

opencode 配置示例（`~/.config/opencode/opencode.json`）：

```json
{
  "mcp": {
    "kbrowser": {
      "type": "local",
      "command": ["/绝对路径/KBrowser/desktopApp/build/mcp/run-mcp-server.sh"],
      "enabled": true
    }
  }
}
```

Claude Code: `claude mcp add kbrowser -- /绝对路径/run-mcp-server.sh`。
启动后 AI 端即出现本页描述的 7 个 `browser_*` 工具。

## Agent 循环

```
browser_navigate(url)                → pageId + snapshot（refid 句柄）
browser_act(click, refid)            → 命中验证；遮挡自动降级 JS
browser_observe(kind=delta)          → 刚才发生了什么（导航/弹窗/报错/请求）
browser_act(wait_for, text=...)      → 时序控制
browser_act(scroll_to, refid)        → 视口外元素滚入
browser_tabs(close_others)           → 收尾清理
```

设计原则：**refid 是唯一元素句柄**（来自 snapshot 的 YAML）；物理交互优先、遮挡自动降级；
错误即数据（`isError:true` 的结果携带恢复指引，AI 可自我纠正）；所有工具支持可选 `pageId`。

---

## 工具参考

### browser_navigate

加载 URL 并等待完成，自动返回 `pageId` 头部 + 页面 snapshot（含渲染竞态防护：AX 树为空时重试）。

| 参数 | 类型 | 说明 |
|---|---|---|
| `url` | string, **required** | 绝对 URL（支持 `data:` 页面） |
| `newTab` | boolean | true 时在新的受管 tab 打开（返回新 pageId） |
| `pageId` | string | 目标 tab；缺省 = 活跃 tab |

### browser_navigate_back

历史回退，返回回退后页面的 snapshot + pageId。无可回退历史时报错。

| 参数 | 类型 | 说明 |
|---|---|---|
| `pageId` | string | 目标 tab；缺省 = 活跃 tab |

### browser_snapshot

页面 → 紧凑 YAML。每个交互元素带 `refid`，传给 `browser_act`。页面变化后 refid 可能失效，需重新获取。

| 参数 | 类型 | 说明 |
|---|---|---|
| `mode` | string | `viewport`（默认，视口内元素，token 最省）/ `clean`（全页面，用于找视口外元素） |
| `pageId` | string | 目标 tab |

### browser_act

交互收口，一个工具覆盖所有操作。结果带验证信息（如 `ok (hit #submitBtn)`、
`FAILED: hit #overlay, expected #button (occluded?)`）——通常无需重新 snapshot 即可判断成败。

| `action` | 必需参数 | 说明 |
|---|---|---|
| `click` | `refid` | 物理点击（CDP 鼠标事件）；被遮挡时自动降级 DOM click（`fallbackJs=false` 关闭） |
| `type` | `refid`, `text` | 点击聚焦 → 逐字符物理键入（30-150ms 随机延迟，拟人） |
| `fill` | `refid`, `text` | JS 设值 + 回读验证（长文本用它，不用 `type`） |
| `select` | `refid`, `text` | `<select>` 选中匹配 option |
| `press` | `key`, 可选 `refid` | 有 refid 先聚焦；支持组合键 `"Control+a"` |
| `hover` | `refid` | 物理悬停 |
| `scroll` | `refid` 或 `x,y` + `dx,dy` | 物理滚轮；返回 scrollTop 前后对比验证 |
| `scroll_to` | `refid` | 元素滚入视口中心；返回实际 rect 验证可见性 |
| `drag` | `startRefid`, `endRefid` | 物理拖拽 |
| `upload` | `refid`, `paths` | CDP `DOM.setFileInputFiles`，无需文件对话框（仅 JVM） |
| `dialog` | `accept`（,`promptText`） | 处理 `observe(delta)` 报告的 JS alert/confirm/prompt |
| `wait_for` | `text` / `textGone` / `urlPattern`（至少一个） | 200ms 轮询等待条件，AND 组合，默认 10s 上限（`timeoutMs` 可调，最大 60s） |
| `reload` | — | 重新加载当前页（之后需重新 snapshot） |
| `wait` | `ms` | 纯等待（≤10s），能用 `wait_for` 时优先 `wait_for` |

### browser_observe

不取 snapshot 查询页面状态。

| `kind` | 参数 | 返回 |
|---|---|---|
| `delta`（默认） | — | 自上次查询以来的增量：URL 变化、`navigated`、活动弹窗、console/JS/网络错误、XHR/Fetch 请求列表（含 `requestId`） |
| `body` | `requestId` | 该请求的响应体（CDP 缓存有淘汰，尽早取） |
| `health` | — | 堆内存、DOM 节点数、错误计数、崩溃状态 |
| `screenshot` | `quality`(1-99 可选) | 页面截图；带 `quality` 返回 JPEG（省 token），否则 PNG。像素与 CSS 坐标 1:1 |
| `tabs` | — | 打开的 tab 列表（同 `browser_tabs action=list`） |

### browser_tabs

多 tab 管理。新窗口请求（target=_blank 等）自动变成受管 tab。

| `action` | 参数 | 说明 |
|---|---|---|
| `list`（默认） | — | 列出所有 tab：pageId、url、title、是否活跃、idle 秒数 |
| `close` | `pageId` | 关闭指定 tab（拒绝关闭最后一个） |
| `close_others` | — | 关闭除活跃 tab 外的所有 tab（多 tab 任务收尾） |

### browser_eval

执行 JS 表达式并返回结果，读取 AX 树之外的数据。

| 参数 | 类型 | 说明 |
|---|---|---|
| `expression` | string, **required** | 应返回 string/number/可 JSON 序列化值 |
| `pageId` | string | 目标 tab |

---

## 返回格式与错误约定

- 成功：`ToolResult(content, isError=false)`；MCP 下为 `isError:false` 的 tool result
- 失败：`isError=true`，文本以 `ERROR:` 开头——**错误是给 AI 读的数据，不是协议错误**
- `STALE_REFID`：refid 失效（导航/动态更新后），指引重新 snapshot
- `ElementNotFoundException` / `IllegalArgumentException` 等同样落为 isError 文本
- snapshot 文本超长截断至 80k 字符并标注

## 刻意不暴露的能力

- **selector 定位器全家**（getByRole/getByText/...）：refid 工作流替代；`fill/select` 内部使用
- **手动 js 系列**（jsClick/jsFill/...）：遮挡降级自动化，AI 不选变体
- **坐标点击**（clickByCoordinates）：实测 refid 路径严格更优（遮挡检测/无坐标系换算/token），坐标仅保留给滚动兜底
- **executeCdp**：留给宿主逃生舱，不给 AI
