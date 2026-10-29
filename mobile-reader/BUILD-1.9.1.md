# Android 1.9.1 修复说明

日期：2026-10-26
基线：1.9.0（versionCode 15）→ 1.9.1（versionCode 16）
产物：`dist/圣子猫阅读-手机版-1.9.1.apk`（21.1 MB）

本版全部是 1.9.0 的回归修复，由真机截图暴露。

---

## 修复项

### 1. 书架分区条被顶进状态栏（截图可见）

**现象**：截图中「全部 / 小说 / 漫画」分段条与系统状态栏（时间、电量、信号）完全重叠，还和某个悬浮窗图标打架。

**原因**：`MainScaffold` 把 `contentWindowInsets` 清零（`WindowInsets(0,0,0,0)`），而我 1.9.0 新增的分区条放在 `Column` 最顶部却没有任何 inset 处理。

**修复**（`ui/MainScaffold.kt`）：
- 分区条新增 `topInset` 参数：漫画分区处自己 `statusBarsPadding()`
- 小说分区把分区条改为通过 `sectionBar` 参数下传给 `LibraryScreen`，渲染在它的 `TopAppBar` **下方**
- `LibraryScreen` 的 `TopAppBar` 显式设 `windowInsets = WindowInsets(0,0,0,0)`，由外层统一负责留白，避免双倍 padding

**结构修正后的视觉顺序**：状态栏 → 标题栏（圣子猫阅读）→ 分区条 → 继续阅读 → 筛选 → 书列表。

### 2. 在线搜漫找不到入口（用户反馈）

**现象**：合并成 3 tab 后，「在线搜漫」没有任何可达路径。

**原因**：1.9.0 把「在线搜漫」塞进「发现」tab，但「发现」默认渲染的是 `OnlineScreen`（在线搜书），没有任何 UI 让用户切到 `MangaOnlineScreen`。

**修复**（`ui/MainScaffold.kt`）：新增 `DiscoverSectionBar`，在「发现」页顶部提供「在线搜书 / 在线搜漫」分段切换。

**同时修复的连带问题**：两个子页的 `TopAppBar` 都加 `windowInsets = WindowInsets(0,0,0,0)`，状态栏留白统一由分区条负责，避免双倍留白。

### 3. 书架筛选 chip 仍然占满一行

**现象**：截图中 6 个筛选 chip（全部/在线/本地/在读/未读/读完）常驻一行，横向滚动且没有滚动暗示，与 1.9.0「首屏减负」的目标相悖。

**修复**（`ui/LibraryScreen.kt`）：
- 默认只显示一行「筛选：<当前项>」+ 展开/收起按钮
- 展开后用 `FlowRow` 换行展示全部 chip（不再横向滚动，窄屏也不会藏项目）

### 4. 从「发现」进入在线搜漫，取消声明会跳到书架

**现象**：未同意使用声明时，在线搜漫会显示同意页，取消后 `navigator.openComics()` 会把人带到书架的漫画分区——从「发现」进来的用户被莫名换了 tab。

**修复**（`ui/MangaScreens.kt`）：取消回调改为 `navigator.openDiscover()`，回到发现页。

---

## 验证

```text
:app:compileReleaseKotlin            BUILD SUCCESSFUL
:app:compileDebugKotlin（含 src/qa）  BUILD SUCCESSFUL
:app:testDebugUnitTest               BUILD SUCCESSFUL
:app:assembleRelease                 BUILD SUCCESSFUL
aapt2 dump badging  versionCode=16 versionName=1.9.1 label=圣子猫阅读
```

产物 sha256 见同名 `.sha256` 文件。

---

## 关于你问的「在线搜漫去哪了」

不是被删了，是 1.9.0 的信息架构调整导致它**失去了入口**：

- 1.8.1：底部第 4 个 tab「在线搜漫」，一眼可见
- 1.9.0：合并进「发现」，但只实现了「在线搜书」，漏了切到搜漫的路径 ← 这是引入的回归
- 1.9.1：发现页顶部加「在线搜书 / 在线搜漫」分段切换，恢复可达

如果你更习惯原来的样子（底部直接有「漫库」和「在线搜漫」两个入口），也可以改回 5 tab——告诉我即可回退这一部分。

---

## 待确认（需要在真机上看）

1. 分区条与状态栏现在应该不再重叠，但**截图里那个圆形悬浮窗**（顶部的黑色/白色圆点）不是本应用的内容，请确认是你系统上的其他 App 悬浮窗或录屏工具。
2. 修复后书架首屏的可见行数是否满意。
3. 「发现」页顶部分段切换是否符合预期位置。
