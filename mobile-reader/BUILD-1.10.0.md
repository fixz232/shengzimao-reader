# Android 1.10.0 漫源详情 / 榜单分类

日期：2026-10-26
基线：1.9.1（versionCode 16）→ 1.10.0（versionCode 17）
产物：`dist/圣子猫阅读-手机版-1.10.0.apk`（22.2 MB）

---

## 需求

> 「搜索范围」选择器里有很多漫源，长按任意一个进入对应漫源详情页面，里面可以看漫画排行榜、分类。

## 关键约束（先说清楚）

漫画源规则原本只有 `search` / `detail` / `reader` 三段，**引擎没有「无关键词浏览」的能力**。
所以「排行榜 / 分类」不能凭空捏造——那会变成假功能。实现采用两层：

1. **有 `discover` 规则的源**：展示源自己声明的榜单与分类，是真实抓取。
2. **没有该规则的源**：展示「分类关键词搜索」作为降级，并在界面与文案上
   **明确标注这不是源站官方榜单**。

这样既有可用的浏览入口，也不会欺骗用户。

---

## 新增

### 1. `discover` 规则（可选增强）

新文件 `data/MangaDiscover.kt`：

- `MangaDiscover.from(source)` 解析规则的 `discover` 段
- 支持分组（排行榜 / 分类…）、每组的条目、翻页后缀 `{page}`
- `items` / `title` / `link` 复用 search 段的解析器，**不引入第二套解析逻辑**

**容错策略**（这是「功能没有 bug」的关键）：配置写坏绝不能让源不可用。

| 情况 | 行为 |
| --- | --- |
| 无 `discover` 段 | 返回 null，走关键词降级 |
| 缺 items/title/link | 返回 null，走降级 |
| groups 为空 | 返回 null，走降级 |
| 单条目 name/url 为空 | 只丢该条目，其余保留 |
| 解析异常 | 捕获，返回 null |

### 2. 引擎 `browse()`

`data/MangaEngine.kt` 新增：

```kotlin
suspend fun browse(source, discover, entry, page = 1): List<MangaBook>
```

复用 `items()` 解析器，URL 支持 `{page}` 替换，结果按 URL 去重，单次上限 120 条。

### 3. 控制器状态与方法

`ui/MangaController.kt`：

- `MangaUiState` 新增 `browseSourceId / browseHasDiscover / browseGroups / browseEntry / browseResults / browseLoading / browseError / browsePage`
- `openBrowse(sourceId)` / `closeBrowse()` / `browse(entryName, more)`
- 独立 `browseJob`，切换条目时取消上一次请求，避免竞态

### 4. 漫源详情页 UI

新文件 `ui/MangaSourceBrowserScreen.kt`：

```
←  YYDS                         ↻
   已启用
┌────────────────────────────────┐
│ [Y] YYDS                       │
│     https://...                │
│ [已启用] [旧式规则] [无榜单]    │
│ ⚠ 该源没有配置 discover 榜单…   │
└────────────────────────────────┘

排行榜
( 人气榜 ) ( 新作榜 ) ( 热门榜 )

分类
( 热血 ) ( 恋爱 ) ( 搞笑 ) …

┌─────┐ ┌─────┐ ┌─────┐
│封面 │ │封面 │ │封面 │
└─────┘ └─────┘ └─────┘
  书名     书名     书名
```

- 状态齐全：加载（带条目名）、失败（可重试）、未选择、空结果
- 封面复用漫库的 `MangaCover`，不另造一套加载逻辑
- 点击作品先入详情，再让用户决定在线阅读或加入漫库
- 有 `discover` 规则时提供「加载第 N 页」

### 5. 长按入口

`ui/MangaScreens.kt` 的 `MangaSearchSourcePicker`：

- 单击：切换搜索范围（原有行为不变）
- **长按：进入该源详情页**
- 每个源右侧加了 `ChevronRight` 图标作为可进入的暗示

### 6. 顺带修复

- 「发现」页顶部分段条与子页 TopAppBar 标题重复（截图里「在线搜书 / 在线搜漫」压着内容）。
  现在子页 TopAppBar 只保留操作按钮，标题由分段条表达。

---

## 验证

```text
:app:compileReleaseKotlin            BUILD SUCCESSFUL
:app:compileDebugKotlin（含 src/qa）  BUILD SUCCESSFUL
:app:testDebugUnitTest               BUILD SUCCESSFUL
:app:assembleRelease                 BUILD SUCCESSFUL
aapt2 dump badging  versionCode=17 versionName=1.10.0
```

新增单测 `MangaDiscoverTest`（5 条）：

- 正常解析分组与翻页后缀
- 无 discover 段返回 null
- 缺必需规则返回 null
- 空 name/url 的条目被丢弃、其余保留
- 降级关键词非空且不重复

---

## 需要你知道的

1. **现有源大概率都没有 `discover` 规则**，所以你实测时长按进去看到的是
   「分类关键词搜索」+ 明确标注。想要真实榜单，需要给源补 `discover` 段，
   格式见 [MANGA_DISCOVER.md](MANGA_DISCOVER.md)。
2. **旧式（legacy）源**走脚本引擎，`discover` 对其不生效，会显示「无榜单」。
3. 我**没有内置任何漫画源**，也不会内置——这涉及内容版权。
4. 截图里那个后台飘着的代码浮层（`CourseMapper` 之类）不是本应用画的，请自行确认来源。

## 建议的真机验证步骤

1. 进入「发现 → 在线搜漫」
2. 点「搜索范围：YYDS」展开下拉
3. **长按** YYDS → 应进入漫源详情页
4. 点一个分类 → 应显示加载，然后出现封面网格或明确的空结果/错误
5. 返回键 → 回到在线搜漫（不是跳到书架）
