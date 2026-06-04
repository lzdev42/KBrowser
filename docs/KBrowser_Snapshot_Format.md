# KBrowser Snapshot 格式说明

> [← 返回 API 参考](KBrowser_API_Reference.md)

---

## 概述

`AxTreeData.toYamlSnapshot()` 将 AXTree 转换为人类和 AI 都易读的文本格式。

相比原始 JSON，这个格式：
- **token 更少**：树形结构天然压缩，子节点文本上浮后节点数大幅减少
- **语义更清晰**：图标按钮的文字自动上浮，placeholder 直接显示，一眼看懂
- **信息完整**：坐标、选择器、遮挡信息全部保留

---

## 格式示例

```
# KBrowser Snapshot
# url: https://www.zhipin.com/web/user/
# viewport: 1075x926
#
# Format: - role "text" @refid [center:x,y] [selector:...] [occludedBy:@refid]
# @refid  → use with page.click(refid) or page.locator(selector)
# occludedBy → coordinate click blocked; dismiss that element first
#

- document "【BOSS直聘注册登录】"
  - link "找工作 BOSS直聘直接谈" @r73 [center:309,262] [selector:body>div>div>div:nth-of-type(2)>div:nth-of-type(1)>a]
  - generic "APP扫码登录" @r88 [center:441,201] [selector:body>div>div>div:nth-of-type(2)>div:nth-of-type(2)>div:nth-of-type(1)] [class:btn-sign-switch ewm-switch]
  - heading "验证码登录/注册" @r91 [center:657,236]
  - list
    - listitem "我要找工作" @r95 [center:571,312] [active]
    - listitem "我要招聘" @r96 [center:751,312]
  - textbox [placeholder:手机号] @r29 [center:691,380] [selector:...>input]
  - textbox [placeholder:短信验证码] @r30 [center:657,456] [selector:...>input]
  - generic "发送验证码" @r113 [center:772,456] [class:btn-sms]
  - button "登录/注册" @r117 [center:657,534] [selector:...>button]
  - link "微信登录/注册" @r119 [center:656,594]
  - checkbox [unchecked] @r31 [center:486,633]
  - link "《用户协议》" @r125 [center:668,633]
  - link "《隐私政策》" @r126 [center:734,633]
```

---

## 字段说明

每一行格式：
```
- role "text" @refid [center:x,y] [selector:css] [occludedBy:@refid] [active] [checked/unchecked] [href:url] [class:name] [placeholder:hint]
```

| 字段 | 含义 | 示例 |
|------|------|------|
| `role` | 节点语义角色 | `button` `link` `textbox` `heading` `generic` |
| `"text"` | 节点文本（子节点文本自动上浮） | `"登录/注册"` |
| `@refid` | 节点唯一 ID，直接用于操作 | `@r117` |
| `[center:x,y]` | 节点中心点坐标（CSS 文档像素） | `[center:657,534]` |
| `[selector:...]` | 动态生成的唯一 CSS 选择器 | `[selector:body>div>button]` |
| `[occludedBy:@refid]` | 遮挡该节点的元素 refid | `[occludedBy:@r88]` |
| `[placeholder:hint]` | input 的 placeholder 提示 | `[placeholder:手机号]` |
| `[active]` | 当前激活状态（tab、选项等） | `[active]` |
| `[checked]` / `[unchecked]` | checkbox/radio 勾选状态 | `[checked]` |
| `[href:url]` | link 的跳转地址 | `[href:/web/user/]` |
| `[class:name]` | className（无文本时显示，辅助理解语义） | `[class:btn-sign-switch]` |

---

## AI 使用指南

### 操作节点

```
# 看到 @r117，可以使用 refid 操作：
# 物理坐标点击（默认）：
page.click("r117")
# JS 模拟点击（无视遮挡）：
page.jsClick("r117")

# 看到 [selector:...]，可以使用选择器定位并执行多种操作：
# 物理点击（基于最新坐标）：
page.locator("body > div > button.sure-btn").click()
# JS 点击（直接触发 DOM 事件，无视遮挡与视口）：
page.locator("body > div > button.sure-btn").jsClick()

# 看到 textbox [placeholder:手机号] @r29，填入内容：
# 物理输入（坐标点击聚焦 -> 逐字符物理输入，高真实性）：
page.locator(node.selector).type("13800138000")
# 混合输入（JS 精准聚焦 -> 逐字符物理输入，高稳定性与高真实性兼顾）：
page.locator(node.selector).jsType("13800138000")
# JS 极速填充（JS 聚焦 -> 直接修改 value 触发 change，无视物理键盘，速度最快）：
page.locator(node.selector).jsFill("13800138000")
```

### 处理遮挡

```
# 看到 [occludedBy:@r88]，说明坐标点击会打到 r88
# 先找 r88 是什么，物理点击关闭它，再操作目标节点；
# 或者直接采用 JS 模式绕过物理坐标碰撞测试，直接操作：
page.jsClick("targetRefid")
# 或
page.locator(targetNode.selector).jsClick()
```

### 理解节点语义

- `generic "APP扫码登录"` → 虽然 role 是 generic（div），但文本说明它是"APP扫码登录"切换按钮
- `textbox [placeholder:手机号]` → 这个输入框用来填手机号
- `listitem "我要找工作" [active]` → 当前激活的 tab 是"我要找工作"
- `[class:btn-sms]` → className 包含 btn，这是个按钮，功能是发送短信

---

## 用法

```kotlin
val tree = page.getRawAxTree()

// 获取 YAML snapshot（推荐给 AI 使用）
val snapshot = tree.toYamlSnapshot()
println(snapshot)

// 原始 JSON 仍然可用（机器处理、精确坐标查询）
val json = Json { prettyPrint = true }.encodeToString(AxTreeData.serializer(), tree)
```

---

## 与原始 JSON 的对比

| | 原始 JSON | YAML Snapshot |
|--|--|--|
| token 量 | ~4000（76节点） | ~800（树形合并后） |
| 图标按钮语义 | `"text": ""` 空，AI 看不懂 | 子节点文本上浮，显示 `"APP扫码登录"` |
| placeholder | 藏在 `attributes` 里 | 直接显示 `[placeholder:手机号]` |
| 父子关系 | 无，AI 靠坐标猜 | 缩进直接表达 |
| 遮挡信息 | `"occludedBy": "r88"` | `[occludedBy:@r88]` 内联显示 |
| 机器解析 | 方便 | 不适合 |
| AI 理解 | 需要推断 | 直接读懂 |

两种格式都保留，按需使用：AI 决策用 `toYamlSnapshot()`，精确坐标查询用原始 JSON。
