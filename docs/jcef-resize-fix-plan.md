# JCEF 拖拽不跟手问题修复计划

> **更新（2026-07-16）**：经多轮修复与字节码分析，最终结论如下。
> OSR 模式拖拽已完全修复（Compose → AWT → `KBCefOsrComponent.reshape()` 链路畅通）。
> 非 OSR 模式拖拽不跟手是 **CEF + Core Animation 架构限制**，无法从 Java/AWT 侧修复。
> 详见下方「八、最终结论」。

## 一、问题现象

拖拽分隔栏/窗口边框时，JCEF 浏览器内容尺寸变化不跟手，存在明显延迟或卡顿。

## 二、根因分析

### 2.1 OSR 模式（默认模式，`KBrowser.useOsrMode = true`）

**KBCefOsrComponent.reshape() 的 100ms 节流逻辑本身与 IDEA 一致，不是根因。**

真正的问题在于 **Compose → Swing 的尺寸传递链路断裂**：

```
Compose Layout 修改 modifier 尺寸
  → SwingPanel 内部 JPanel.setBounds()  ← 这一步由 Compose AWT 桥接自动完成
    → KBCefOsrComponent.reshape()        ← 这一步也会被触发
      → 100ms 节流 + ResizePusher        ← 这套逻辑已存在且正确
```

但存在以下问题：

1. **Compose SwingPanel 的 AWT 桥接可能不触发 reshape**
   - Compose `SwingPanel` 在某些场景下（特别是窗口拖拽、Splitter 拖拽）可能只修改了
     外层容器的 bounds，而内层 `KBCefOsrComponent` 的 `reshape` 没有被调用。
   - IDEA 不存在此问题，因为 IDEA 的 `OnePixelSplitter` 直接操作 AWT `Splitter.doLayout()`
     → `setBounds()` → `reshape()`，全程 AWT，没有 Compose 中间层。

2. **KBCefBrowser.myComponent（外层 JPanel）没有 ComponentListener**
   - IDEA 的 `JBCefBrowser.MyPanel` 注册了 `ComponentListener`，在 `componentResized` 时
     通知 `ourOnBrowserMoveResizeCallbacks`（给 HwFacadeHelper 用）。
   - KBrowser 的 `KBCefBrowser.myComponent` **没有注册任何 ComponentListener**，
     无法感知外层容器的尺寸变化。

3. **resizeViewport() 方法无人调用**
   - `JvmWebView.resizeViewport()` 是唯一显式调用 `wasResized` 的入口，
     但全代码库无任何调用方。
   - Compose 层没有 `onGloballyPositioned`/`onSizeChanged` 等回调来触发它。

### 2.2 非 OSR 模式（窗口模式，降级场景）

1. **无任何 resize 处理**
   - 重量级 Canvas 组件由 AWT 原生窗口系统自动 resize，理论上"天然跟手"。
   - 但 `KBCefBrowser.myComponent` 没有注册 `ComponentListener`，无法感知尺寸变化。
   - `resizeViewport()` 无人调用。

2. **无 HwFacadeHelper**
   - 非 OSR 模式下重量级组件会覆盖所有轻量级组件（Z-order 问题）。
   - IDEA 用 `HwFacadeHelper` 解决，KBrowser 没有实现。
   - **本次不处理 HwFacadeHelper**（属于独立问题，影响范围大）。

## 三、IDEA 参考代码对照

| 机制 | IDEA 代码 | KBrowser 现状 |
|------|-----------|--------------|
| OSR reshape 100ms 节流 | `JBCefOsrComponent.java:164-182` | ✅ 已实现 `KBCefOsrComponent.kt:197-231` |
| 首帧快速通道 | IDEA 无（用 Alarm 批次首次立即） | ✅ 已实现 `myFirstResizeSynced` |
| ResizePusher 20ms invalidate | `JBCefOsrHandler.java:358-398` | ✅ 已实现 `KBCefOsrHandler.kt:299-356` |
| onPaint 尺寸不匹配兜底 wasResized | IDEA 无（仅 startResizePusher） | ✅ 已实现 `scheduleFallbackWasResized` |
| MyPanel ComponentListener | `JBCefBrowser.java:221-241` | ❌ **缺失** |
| Compose → AWT 尺寸同步 | IDEA 不用 Compose，全 AWT | ❌ **缺失** |
| 非 OSR resizeViewport | IDEA 不需要（原生窗口自动） | ❌ 有方法但无人调用 |
| HwFacadeHelper | `HwFacadeHelper.java` | ❌ 缺失（本次不处理） |

## 四、修复方案

### 任务 1：KBCefBrowser.myComponent 添加 ComponentListener（OSR + 非 OSR 通用）

**文件**: `shared/src/jvmMain/kotlin/xyz/kbrowser/jcef/KBCefBrowser.kt`

**改法**: 在 `init` 块中给 `myComponent` 注册 `ComponentListener`，当 `componentResized` 时：
- OSR 模式：调用 `KBCefOsrComponent.reshape()` 的逻辑（`wasResized` + `startResizePusher`）
- 非 OSR 模式：调用 `cefBrowser.wasResized(0, 0)`

**IDEA 参考**: `JBCefBrowser.java:221-241` — `resultPanel.addComponentListener(new ComponentAdapter() { componentResized → ourOnBrowserMoveResizeCallbacks })`

**具体代码**:
```kotlin
// KBCefBrowser.kt init 块中添加
myComponent.addComponentListener(object : java.awt.event.ComponentAdapter() {
    override fun componentResized(e: java.awt.event.ComponentEvent) {
        // OSR 模式：KBCefOsrComponent.reshape() 已由 AWT 布局自动触发，
        // 这里作为兜底，确保 wasResized 被调用
        val osrComp = findOsrComponent()
        if (osrComp != null) {
            // reshape 已在 KBCefOsrComponent 中处理，这里不需要额外操作
            // 但如果 reshape 没被触发（Compose 场景），需要手动同步
        } else {
            // 非 OSR 模式：重量级组件需要显式通知 CEF
            myCefBrowser.wasResized(0, 0)
        }
    }
})
```

### 任务 2：Compose 层主动尺寸同步（核心修复）

**文件**: `shared/src/jvmMain/kotlin/xyz/kbrowser/webview/WebView.jvm.kt`

**改法**: 在 `JcefWebViewRender.render()` 中，使用 `BoxWithConstraints` 或 `onSizeChanged`
监听 Compose 尺寸变化，主动调用 `resizeViewport()` 或直接触发 CEF resize。

**IDEA 参考**: IDEA 不用 Compose，但原理相同 — Splitter 的 `setProportion` → `revalidate` →
`setBounds` → `reshape`。Compose 的等价链路是 `onSizeChanged` → `SwingUtilities.invokeLater`
→ `component.setSize()` / `wasResized()`。

**具体代码**:
```kotlin
@Composable
fun render(webView: KBWebView, modifier: Modifier) {
    val jvmWebView = webView as? JvmWebView ?: return

    // 监听 Compose 尺寸变化，主动同步给 JCEF
    var currentSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    androidx.compose.foundation.layout.Box(
        modifier = modifier.onSizeChanged { newSize ->
            if (newSize != currentSize) {
                currentSize = newSize
                jvmWebView.resizeViewport(newSize.width, newSize.height)
            }
        }
    ) {
        SwingPanel(
            factory = { jvmWebView.browser.getComponent() },
            modifier = Modifier.fillMaxSize()
        )
    }
}
```

### 任务 3：修复 resizeViewport() 方法（OSR + 非 OSR 双模式）

**文件**: `shared/src/jvmMain/kotlin/xyz/kbrowser/webview/WebView.jvm.kt`

**改法**: 当前 `resizeViewport()` 直接调用 `comp.setSize()` + `cefBrowser.wasResized()`，
没有节流保护，且对 OSR 模式不正确（OSR 的 reshape 自带节流，不应绕过）。

**IDEA 参考**:
- OSR: `JBCefOsrComponent.reshape()` — 100ms 节流 + ResizePusher
- 非 OSR: `JBCefBrowser.java:221` — ComponentListener + wasResized

**具体代码**:
```kotlin
fun resizeViewport(width: Int, height: Int) {
    if (isDestroyed.get()) return
    SwingUtilities.invokeLater {
        if (isDestroyed.get()) return@invokeLater

        val osrComp = browser.getComponent().components
            .filterIsInstance<KBCefOsrComponent>()
            .firstOrNull()

        if (osrComp != null) {
            // OSR 模式：通过 setSize 触发 KBCefOsrComponent.reshape()，
            // reshape 内部有 100ms 节流 + ResizePusher，不需要手动 wasResized
            if (osrComp.width != width || osrComp.height != height) {
                osrComp.setSize(width, height)
            }
        } else {
            // 非 OSR 模式：重量级组件需要显式 wasResized
            val comp = cefBrowser.uiComponent ?: return@invokeLater
            if (comp.width != width || comp.height != height) {
                comp.setSize(width, height)
                cefBrowser.wasResized(0, 0)
            }
        }
    }
}
```

### 任务 4：非 OSR 模式添加 ComponentListener 兜底

**文件**: `shared/src/jvmMain/kotlin/xyz/kbrowser/jcef/KBCefBrowser.kt`

**改法**: 在 `KBCefBrowser.init` 中，当检测到非 OSR 模式时，给重量级 UI 组件注册
`ComponentListener`，在 `componentResized` 时调用 `wasResized`。

**IDEA 参考**: `JBCefBrowser.java:221-241`

**具体代码**:
```kotlin
// KBCefBrowser.kt init 块中，在 myComponent.add(uiComp, BorderLayout.CENTER) 之后
if (!isOffScreenRendering) {
    uiComp.addComponentListener(object : java.awt.event.ComponentAdapter() {
        override fun componentResized(e: java.awt.event.ComponentEvent) {
            myCefBrowser.wasResized(0, 0)
        }
    })
}
```

### 任务 5（可选优化）：OSR reshape 节流策略微调

**文件**: `shared/src/jvmMain/kotlin/xyz/kbrowser/jcef/KBCefOsrComponent.kt`

**现状**: 当前 reshape 在快速拖拽时，100ms 内的连续 reshape 全部被节流，只有 alarm 到期
才执行一次 `wasResized`。这意味着拖拽过程中每 100ms 才有一次 CEF 尺寸更新。

**IDEA 做法**: IDEA 的 `JBCefOsrComponent.reshape()` 逻辑相同（100ms 防抖），但 IDEA 的
`ResizePusher` 在 `wasResized` 后以 20ms 间隔持续 `invalidate`，保证 CEF 在 2 秒窗口内
不断追上组件真实尺寸。KBrowser 已有 ResizePusher，所以理论上应该跟手。

**如果仍不跟手，可能原因**:
1. Compose SwingPanel 没有把尺寸变化传递到 KBCefOsrComponent（任务 2 解决）
2. ResizePusher 的 `browser.invalidate()` 没有触发 `getViewRect` 重新取尺寸
3. `onPaint` 的尺寸比对逻辑有精度问题（double 比较）

**暂不修改此文件**，先完成任务 1-4 后测试。如果仍不跟手再回来调整。

## 五、执行顺序

1. ✅ 任务 2 — Compose 层主动尺寸同步（最核心，解决 Compose → AWT 链路断裂）
2. ✅ 任务 3 — 修复 resizeViewport() 方法（OSR/非 OSR 双模式正确处理）
3. ✅ 任务 4 — 非 OSR 模式 ComponentListener 兜底
4. ✅ 任务 1 — KBCefBrowser.myComponent ComponentListener（辅助兜底）
5. ⏳ 任务 5 — OSR reshape 微调（视测试结果决定）

## 六、不处理项

- **HwFacadeHelper**：非 OSR 模式下重量级组件覆盖轻量级组件的 Z-order 问题。
  这是独立问题，影响范围大，需要单独规划。
- **headless 模式 JFrame resize 联动**：当前 headless 模式使用固定尺寸 JFrame，
  不涉及拖拽场景。

## 七、验证方法

1. 启动 KBrowser，打开一个网页
2. 拖拽窗口边框，观察浏览器内容是否实时跟随尺寸变化
3. 如果有 Splitter 分隔栏，拖拽分隔栏，观察预览区是否跟手程度
4. 切换 OSR/非 OSR 模式（`KBrowser.useOsrMode`），分别测试
5. 在 Retina 屏幕上测试 DPI 缩放是否正确

## 八、最终结论

### 8.1 OSR 模式 — 已修复 ✅

修复后的尺寸传递链路：

```
Compose Layout 尺寸变化
  → SwingPanel 内部 JPanel.setBounds()
    → KBCefOsrComponent.reshape()           ← AWT 布局自动触发
      → 100ms 节流 + ResizePusher            ← 已有逻辑，正确工作
        → CefBrowser.wasResized()            ← 同步调用，通知 CEF 重新渲染
```

关键修复点：
- `WebView.jvm.kt` 的 `resizeViewport()` 在 OSR 分支通过 `osrComp.setSize()` 触发 `reshape()`
- `KBCefOsrComponent.reshape()` 内置 100ms 节流 + `ResizePusher`（20ms 间隔持续 invalidate）
- `onPaint` 中 `scheduleFallbackWasResized` 作为尺寸不匹配兜底
- 首帧快速通道 `myFirstResizeSynced` 跳过首次节流，立即 `wasResized`

### 8.2 非 OSR 模式 live-resize — 不可修复 ❌（CEF 架构限制）

经字节码分析确认，非 OSR 模式拖拽不跟手的根因是：

1. **`CefBrowserWr.wasResized()` 在 macOS 窗口模式下是 no-op**
   - 原生方法 `N_WasResized` 只有 OSR + Win/Linux 分支，macOS 直接 fall through
   - 调用 `wasResized()` 对 macOS 窗口模式没有任何效果

2. **NSView 尺寸更新依赖 `doUpdate()` → `N_UpdateUI()`**
   - `doUpdate()` 只在 `CefBrowserWr$3.paint()` 和 `CefBrowserWr$5.ancestorResized` 中调用
   - 这两处都通过 `EventQueue.invokeLater` 异步执行

3. **macOS live-resize 期间 EventQueue 不处理**
   - macOS 的 live-resize（拖拽窗口边框）期间，AWT EventQueue 被阻塞
   - `paint()` 和 `ancestorResized` 都不会执行
   - 即使 NSView frame 已设置，CEF 的 Core Animation compositor 也不会提交新帧

4. **`paintImmediately()` 也无效**
   - 尝试过强制 `paintImmediately()`，`doUpdate()` 确实执行了
   - 但 CEF 的 Core Animation compositor 在 live-resize 期间不提交新帧

**结论**：非 OSR 模式 live-resize 不跟随是 CEF + Core Animation 的架构限制。
CEF 使用 Core Animation 进行硬件合成，在 macOS live-resize 期间 CA 不提交帧。
这是 CEF 层面的问题，无法从 Java/AWT 侧绕过。

### 8.3 清理工作

- 移除了 `KBCefBrowser.kt` 中的 `ComponentListener`（非 OSR 兜底无效）
- 移除了 `WebView.jvm.kt` `resizeViewport()` 中的非 OSR 分支（`wasResized` 是 no-op）
- 移除了 `KBCefBrowser.kt` 中的 `reshape`/`doLayout` override、`paintImmediately` 等诊断代码
- 代码恢复到干净状态，仅保留 IME 委托 + 焦点处理

### 8.4 OSR 性能说明

OSR 模式每帧需要 GPU → CPU → GPU 像素往返：
- CEF 渲染到 GPU 纹理 → `glReadPixels` 读回 CPU 内存 → Java `Image` → AWT 重新上传 GPU 绘制
- JetBrains Remote 模式 + SharedMemory 可缓解但无法消除此开销
- `shared_texture_enabled`（CEF M125+）可避免像素往返，但 JCEF Java API 未暴露此能力

非 OSR 是 KBrowser 支持的渲染模式，适用于不需要叠加 Compose UI、追求极限渲染性能的场景（已在生产中使用，显示、加载与交互均正常）。其唯一不可修复的局限是 macOS 上的 live-resize：拖拽窗口/分隔条边框时内容不实时更新，松开后才刷新——这是 CEF + Core Animation 的架构限制，不影响非 OSR 的正常使用。OSR 则是默认模式，叠加 UI 与 live-resize 均已修复。
