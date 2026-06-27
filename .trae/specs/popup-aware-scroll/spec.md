# 弹窗感知滚动 Spec

## Why
当前 `smartScrollIntoView` 用坐标范围匹配来"猜"元素属于哪个可滚动容器，在真实网站（如 Boss直聘）上会误匹配主页面的 `overflow:auto` 容器，导致错误滚动或滚动不生效。AX tree 已经有完整的 DOM 层级关系（`childIds`/`nodeId`）和弹窗标识（`role=dialog`），可以在 snapshot 阶段就确定每个元素的归属，运行时直接使用，无需猜测。

## What Changes
- 重写 `smartScrollIntoView`：基于 AX tree 的弹窗归属判断，替代坐标范围猜测
- `click(refid)` / `hover(refid)` 传入弹窗归属信息给底层滚动方法
- `verifyClickAtViewport` 修复：用 CDP 上下文验证而非浏览器页面上下文（解决 `Runtime.evaluate` vs `cefBrowser.executeJavaScript` 上下文不同步问题）
- 清理之前调试遗留的代码（`verifyClickAtViewport` 重复定义、`performClickByCoordinates` 返回值类型变更等）

## Impact
- Affected code: `WebView.jvm.kt`（smartScrollIntoView + clickByCoordinates + hoverByCoordinates）、`KBPage.kt`（click + hover + verifyClickAtViewport）、`KBWebView.kt`（performClickByCoordinates 签名）
- 不影响: 清洗算法、AX tree 数据结构、KBLocator、JS 系列操作、scroll/drag/键盘操作

## ADDED Requirements

### Requirement: 弹窗感知滚动
系统 SHALL 在 click/hover 时，基于 AX tree 的 DOM 层级关系判断目标元素是否在弹窗内，并据此选择正确的滚动策略。

#### Scenario: 目标元素在主页面（无弹窗）
- **WHEN** click/hover 一个不在任何 `role=dialog` 子树内的元素
- **THEN** 使用 `window.scrollTo` 滚动主页面

#### Scenario: 目标元素在弹窗内
- **WHEN** click/hover 一个在 `role=dialog` 子树内的元素
- **THEN** 在该弹窗内部搜索 `overflow:auto/scroll` 容器并滚动，不滚动主页面

#### Scenario: 弹窗内元素需要滚动才可见
- **WHEN** 弹窗内有可滚动容器，目标元素在容器内但被滚出可见区域
- **THEN** 滚动该容器使目标元素可见，返回更新后的视口坐标

#### Scenario: 弹窗内元素已可见
- **WHEN** 弹窗内元素在当前滚动位置已可见
- **THEN** 不执行任何滚动，直接返回当前视口坐标

#### Scenario: 主页面元素在弹窗打开时
- **WHEN** 页面有弹窗打开，但目标元素不在弹窗内（如弹窗背后的主页面元素）
- **THEN** 不滚动主页面（因为弹窗遮罩层阻止交互），返回失败或提示关闭弹窗

### Requirement: 弹窗归属判断
系统 SHALL 能从 nodeCache 中判断任意 AxNode 是否属于弹窗，以及属于哪个弹窗。

#### Scenario: 通过 childIds 递归判断
- **WHEN** 给定一个 AxNode 的 refid
- **THEN** 通过 `role=dialog` 节点的 `childIds` 递归遍历子孙，判断该 refid 是否在弹窗子树内

#### Scenario: 返回弹窗 refid
- **WHEN** 元素在弹窗内
- **THEN** 返回该弹窗的 refid，可用于后续查找弹窗内的滚动容器

## MODIFIED Requirements

### Requirement: smartScrollIntoView
原实现：遍历全页面 DOM，用坐标范围匹配可滚动容器
新实现：
1. 接收弹窗归属信息（popupRefid 或 null）
2. 如果目标不在弹窗内 → `window.scrollTo` 滚主页面
3. 如果目标在弹窗内 → 仅在该弹窗内部搜索可滚动容器
4. 不再遍历全页面 DOM，不再用坐标范围猜测

### Requirement: clickByCoordinates / hoverByCoordinates
原实现：smartScrollIntoView 只接收坐标参数
新实现：额外接收弹窗归属信息（popupRefid），传给 smartScrollIntoView

### Requirement: verifyClickAtViewport
原实现：用 `page.evaluateJavascript`（浏览器页面上下文）执行 `elementFromPoint`
新实现：用 `Runtime.evaluate`（CDP 上下文）执行 `elementFromPoint`，与 `Input.dispatchMouseEvent` 使用同一坐标系

## REMOVED Requirements

### Requirement: 坐标范围匹配容器
**Reason**: 用坐标范围猜测元素属于哪个可滚动容器，在真实网站上会误匹配
**Migration**: 替换为基于 AX tree DOM 层级关系的弹窗归属判断
