# Android 1.9.0 构建说明（UI 大升级）

日期：2026-10-26
基线：1.8.1（versionCode 14）→ 1.9.0（versionCode 15）
产物：`dist/圣子猫阅读-手机版-1.9.0.apk`（21.1 MB，sha256 见同名 `.sha256`）

本次按《手机版-UI大升级方案.md》实施 P0–P5。所有阶段均通过增量构建验证；未在真机运行，交互类结论需上机复核。

---

## 一、P0 止血

| 改动 | 位置 | 说明 |
| --- | --- | --- |
| 阅读进度补 `ON_STOP` 保存 | `ui/ReaderScreen.kt` | 原先只在离开阅读页时保存，切后台被系统回收会丢整次进度。现在与 20 秒阅读时长心跳同一可靠性标准 |
| 分页改用实测视口 | `ui/ReaderScreen.kt` | 原用 `screenHeightDp - 22.dp`（魔数）。改为 `BoxWithConstraints` 实测正文可用区并回填给分页；关掉「隐藏状态栏」、三键导航、分屏/折叠屏不再出现末行被裁或页脚被压 |
| 正文避让系统栏 | `ui/ReaderScreen.kt` | 非全屏时补 `statusBarsPadding()` + `navigationBarsPadding()` |
| 目录提到一级入口 | `ui/ReaderBars.kt` | 顶栏新增目录图标（复用已 import 未用的 `ListAlt`），从「更多」二级菜单移出 |

## 二、P1 设计系统（三套可切换风格）

新增 `ui/theme/Tokens.kt`：

- `AppThemeStyle`：墨水纸感 / Material 表达 / 极简黑白
- `AppTokens`（圆角、间距、层级、字阶、图标线宽、动效开关）
- `LocalAppTokens` 供全局读取，组件只读 token，不写死数值

三套风格是**三份常量**，不是三套组件：

| 风格 | 圆角（卡片/封面） | 层级表达 | 字阶 |
| --- | --- | --- | --- |
| 墨水纸感（默认） | 12 / 8 dp | 描边，无投影 | 偏紧，正文 16sp |
| Material 表达 | 20 / 14 dp | 容器色 + 高度 | 更大更重 |
| 极简黑白 | 2 / 2 dp | 纯描边 | 收敛中性 |

配套：

- `Theme.kt` 按风格选色板（新增 `MonoLightScheme` / `MonoDarkScheme` 去色板），并把原 `AppTypography` 合并进 token
- 新增 `ui/theme/StylePreview.kt`：用该风格自己的 token 渲染缩略卡片
- 设置页新增「外观风格」分区，带实时预览与「切换」
- 持久化：`ReaderSettings.appThemeStyle`（DataStore key `appThemeStyle`），默认 `INK_PAPER`，老用户升级后外观不变
- **移除** `statusBarColor` / `navigationBarColor` 赋值：targetSdk ≥ 35 后已被系统忽略，保留会误导后来的人

## 三、P2 导航与信息架构

底部 5 tab → **3 tab**：

| 旧 | 新 |
| --- | --- |
| 书库 / 在线搜书 / 漫库 / 在线搜漫 / 设置 | **书架**（小说+漫画同库） / **发现**（聚合搜书、番茄、在线搜漫） / **我的** |

- `Navigator.kt` 重写：新增 tab 历史（返回键回退到上一个 tab，而非一律回书架）；`Screen` 枚举保持不变，全部既有调用点零改动
- 书架内新增「全部 / 小说 / 漫画」分段切换（`LibrarySection`）
- 书架首屏减负：
  - 顶栏 5 个图标 → 搜索 + 「⋮」溢出菜单（视图切换、排序、在线搜书、追更、关于）
  - 统计头 → 「继续阅读」横向卡片（回到上次在读）
  - 追更面板 → 一行可点摘要，展开才看批量进度
  - 排序选中态由「✓ 」字符前缀改为 `trailingIcon`
- 设置页新增「漫画阅读」分区（原先 19 项漫画设置**只能**在漫画阅读页里改）；小说同名四项加「（小说）」后缀
- 删掉设置页重复的书库分区（`gridView`/排序已在书架溢出菜单）

## 四、P3 阅读器统一

- **漫画亮度复位修复**（`ui/MangaSettingsSheet.kt`）：原 `onDispose` 回写进入时的亮度快照，且 effect key 含 `brightness`，导致拖动滑杆「闪一下」、退出时覆盖用户中途改过的亮度。改为：亮度只由 `LaunchedEffect` 写入，`onDispose` 统一复位成 `BRIGHTNESS_OVERRIDE_NONE`
- **漫画缩放后翻页解锁**（`ui/MangaReaderScreen.kt`、`ui/MangaImages.kt`）：原 `turnPage` 在 `zoomed` 时直接 return，音量键/滑动/侧点全部静默失效。新增下行复位通道 `resetSignal`，翻页前先解锁缩放

## 五、P4 手势与手感

- 滚动判定竖直阈值 `0.004f` → `0.020f`，并按系统 `touchSlop` 兜底（`reader/Gestures.kt`）。原先约 10px 的抖动就会被判成滚动并吞掉点按，表现为「点中间呼出菜单」时灵时不灵
- 长按阈值由写死 420ms 改为 `viewConfiguration.longPressTimeoutMillis`
- 边缘手势带 16%（约 58dp）→ 5%（约 18dp），避开 Android 系统返回手势热区，且不再把正常左右滑动翻页整段吞掉
- 新增单测 `tiny vertical jitter is not treated as scroll` 覆盖新阈值

## 六、P5 收尾

- IME：目录搜索、全文搜索、在线详情三个含输入框的 `ModalBottomSheet` 加 `contentWindowInsets = { WindowInsets.ime }`
- 无障碍：
  - `AppBadge` 前景色按容器亮度选择（原写死白色，浅色底上看不清）
  - `AppInlineNotice` 关闭按钮 30dp → 48dp
  - 阅读主题色卡 `Row` → `FlowRow`（6 个 62dp 在 360dp 屏必然溢出）
  - 主题色卡改为色块本身可点（原只有下方小字按钮可点），并补 `selected` 语义
- QA 同步：`MobileFeatureSmoke.kt` 的导航断言改为 3 tab + 书架分区 + 发现子页，覆盖新旧全部入口

---

## 验证记录

```text
:app:compileReleaseKotlin            BUILD SUCCESSFUL
:app:compileDebugKotlin（含 src/qa）  BUILD SUCCESSFUL
:app:testDebugUnitTest               BUILD SUCCESSFUL（含新增手势回归）
:app:assembleRelease                 BUILD SUCCESSFUL
aapt2 dump badging  versionCode=15 versionName=1.9.0 label=圣子猫阅读
```

产物 sha256：

```text
c13248acb68b868043e94c5f5de6944f50533785fbdf0aee46777b309c9ced25
```

---

## 未包含（需要真机或后续版本）

以下条目在方案里列了但本次**未实施**，原因是需要真机验证或改动面过大：

1. **在线/离线合并为单一 `UnifiedReader`**：核查后确认两者已共用 `ReaderSurface` + `Paginator`，剩余差异在数据源与排版范围。本次先修了三处真实缺陷（亮度、缩放、手势），完整抽象 `ReaderSource` 接口属于结构性重构，建议单独版本做。
2. **翻页动画双页缓冲**：`ReaderSurface` 只有一个内容 Box，「覆盖」与「平移」观感相同。需要引入前后页双缓冲，属于核心绘制改动，风险高，建议先补齐真机动效基线。
3. **章节标题层级、页脚参与排版**：需要扩展 `PageFrame` 结构与分页测量，会改变既有分页结果的签名（缓存失效一次）。
4. **设置拆 5 子页**：本次补了漫画分区并按主题分组，未做完整的二级页面路由。
5. **书架多选批量操作**：漫库已有，小说书架尚未迁移。

以上 1–3 建议在真机上跑过 1.9.0 后再排期。
