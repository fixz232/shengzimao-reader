# Android 1.4.0 本地构建与验证记录

日期：2026-10-24。本轮实现了《漫画阅读器 UI 与功能优化建议》与《在线搜漫与漫画源 UI 与功能优化建议》两份评审报告中的全部条目。

## 已执行的验证

```powershell
# JDK 21、Gradle 9.7.1、AGP 9.4.1、compileSdk 37
& '.\.tools\gradle-9.7.1\bin\gradle.bat' :app:compileDebugKotlin --console=plain
& '.\.tools\gradle-9.7.1\bin\gradle.bat' :app:testDebugUnitTest --console=plain
& '.\.tools\gradle-9.7.1\bin\gradle.bat' :app:assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease --console=plain
```

| 项目 | 结果 |
| --- | --- |
| Debug Kotlin 编译 | 通过，无错误 |
| Debug 编译警告 | `BitmapRegionDecoder.newInstance(String, boolean)` 在 API 37 上已弃用，沿用直至升级最低 API |
| 单元测试 | 60 个用例，59 个通过，1 个既有失败（见下） |
| 新增测试 `MangaLogicTest` | 8/8 通过 |
| Release 构建 | `BUILD SUCCESSFUL`，R8 与资源压缩完成 |
| 产物 | `app/build/outputs/apk/release/app-release.apk`，19.73 MB |
| 版本 | `versionName=1.4.0`，`versionCode=7` |

## 既有失败用例（与本次改动无关）

`ReleaseStructureTest.consentVersionMatchesDesktop` 断言移动端 `Consent.VERSION` 等于 `2026-10-23-v1`，但移动端自 1.3.0 起为 `2026-10-24-v2`，桌面版 `independent-reader/app/consent.cjs` 仍为 `2026-10-23-v1`。该测试文件与两端版本常量均未在本轮修改，属于此前已存在的不一致。

本轮新增了聚合搜索等在线能力，是否因此需要重新征求声明同意，需要产品判断：若要升版，应同时更新移动端与桌面端，而不是只改一端的常量去让测试变绿。

## 本轮改动清单

### 漫画阅读器

1. 沉浸式阅读：新增 `immersive` 设置，默认开启；单击中央呼出工具栏，4 秒无操作自动隐藏；返回键优先级为 目录/下载/设置/工具栏/退出。
2. 缩放重构：`MangaZoomState` 状态提升到整页，长图所有分块共享同一 `scale`/`offset`，修复放大时相邻块不跟随造成的撕裂。
3. 平移边界：`clamp()` 按缩放后尺寸计算可平移余量，修复图片可被拖出屏幕。
4. 双指锚点：改用 `rememberTransformableState` 的 centroid 重载，围绕手指落点缩放。
5. 内存缓存：`MangaBitmapCache` 按字节计量（96 MB 上限）的 LRU，替换原本无淘汰的 `produceState`。
6. 漫画设置：新增 `MangaSettings` + `MangaSettingsRepository`（DataStore `manga_preferences`，键前缀 `manga.`），15 个设置项全部接入渲染或交互。
7. 修复设置不生效：`MainActivity` 原先用 `inReader` 判断亮屏与全屏，未包含漫画页；现由 `ReaderWindowEffects` 接管漫画页的沉浸、亮屏、亮度与系统栏图标。
8. 目录：新增章节搜索、当前章高亮与自动滚动定位、缓存标记、缓存章节数汇总。
9. 进度：新增进度滑杆与翻页位置浮层。
10. 预取：临近章末预取下一章目录与前 N 张图片（`prefetchPages`）。
11. 音量键翻页与章末自动续读接入（通过 `Modifier.volumeKeyPager`）。

### 在线搜漫与源管理

12. 聚合搜索：`ALL_SOURCES` 模式，每批 4 个源并发，结果合并后复用 `OnlineMatcher` 打分排序；`MangaSourceHit` 记录每个源的命中与失败，支持按源筛选。
13. 搜索历史：持久化到 `comics/search-history.json`，最多 12 条。
14. 结果卡片：显示简介，标记「已在漫库」，勾选批量缓存，长按快速加入。
15. 搜索框移出 `LazyColumn`，固定在顶栏下方。
16. 源编辑：新增 `editingSourceId` 与独立编辑页 `MangaSourceEditor`，`saveSourceEdit` 校验 `edited.id == editingId`，阻止改 ID 静默分裂数据。
17. 源测试：`MangaEngine.test` 依次执行搜索、详情、图片三段规则并定位失败环节。
18. 错误拆分：`searchError` / `detailError` / `sourceError` 替代全局 `error`。
19. 重入保护：`saving` 标志阻止 `keep()` 连点重复写库。
20. 导入改底部弹窗；新增缓存管理入口与导出全部源。

### 数据与引擎

21. 进度合并写入：`stagePosition` + `flushProgress` + `ProgressFlusher`，1.2 秒合并一次，替代每次翻页重写整个 `library.json`。
22. 缓存管理：`cacheUsage` / `clearImageCache` / `trimImageCache`（按最后修改时间 LRU 淘汰）。
23. 并发下沉：`MangaEngine` 内 `Semaphore(3)`，不再只在 `download` 的 `chunked(3)` 生效。
24. 锁释放：`imageLocks.remove(file.name, lock)`。
25. 重试：网络异常与 5xx 退避 400 ms 重试一次。
26. 源解析缓存：`sourceCache` + `invalidateSources()`，避免每张图全量解析源 JSON。

## 修复：无法导入漫画源（现场反馈）

**现象**：在「源管理 → 添加源 → 链接」填入 `https://eo.com/yiciyuan/tuyuans/json/id/66.json` 后，界面显示：

```
Value ... of type java.lang.String cannot be converted to JSONObject
```

**根因**：该地址返回 `301 Moved Permanently`，`Location: https://x.com/rundns/yiciyuan/tuyuans/json/id/66.json`。引擎以 `followRedirects(false)` 手动跟随跳转，最终拿到的是 HTML（或超时），却被直接交给 `JSONObject(text)`。该异常文案由 Android 平台内建的 org.json 抛出，未做翻译，用户无法判断是链接错了还是格式不对。

注意：单元测试运行在 JVM 的 `org.json:json:20250517` 上，它的报错文案（`A JSONObject text must begin with '{'`）与 Android 平台实现不同，所以早期新增的测试没能复现这条现场文案——这也是最初判断被误导的原因。

**修复**：

1. `MangaSource.importText` 在解析前做形态检查：空内容、HTML、非 `{`/`[` 开头分别给出可操作的中文提示；数组元素不是 JSON 对象时指明是第几项；单项解析失败时带上序号与原因。
2. `MangaEngine.importUrl` 记录跳转链，跳转到不同域名时直接告诉用户「该地址被跳转到了 x.com（原域名 eo.com）」，并建议改用「粘贴」导入。
3. 导入弹窗的错误改为 `errorContainer` 区块承载，链接模式下额外提供「改用「粘贴」导入」一键切换。
4. 顺带修正 `trim().removePrefix("\uFEFF")` 的顺序问题：BOM 后跟空白时会漏删 BOM，改为先删 BOM 再 trim。

**新增测试**：`MangaSourceImportTest`（10 项）与 `MangaImportRedirectDiagnosisTest`（4 项），覆盖单对象 / 数组 / `sources` 包装、HTML 响应、字符串数组、缺字段、BOM 与空白前缀。

**验证**：74 个单元测试，73 通过，仅剩 `consentVersionMatchesDesktop` 这一既有失败；Release 构建成功。

**对用户的实际建议**：该链接本身不可用（已跳转到 x.com），换用直接返回 JSON 的地址，或把 JSON 内容复制后用「粘贴」导入。

## 未执行

- 未做真机运行测试。本轮只完成编译、单元测试与打包，行为验证需要真机。
- 未重新生成 `assets/engine` 下的预编译 dex（本轮未改动 `engine/` 下的 Java 源码）。
- README 已更新至 1.4.0；`MANGA_SOURCES.md` 中「图片最多 3 路并发」的表述仍准确（现在由引擎全局保证）。
