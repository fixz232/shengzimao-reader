# Android 1.8.1 本地构建与验证

日期：2026-10-26。使用已连接的 realme RMX5010 / Android 16，独立 QA 包；没有用正式版书库做测试。

## 本次修改

- YYDS：识别公开阅读页的图片槽位，按批调用同站懒加载接口，每批 5 张、最多 3 路；按目录顺序返回并校验完整数量。旧缓存中的加载占位图失效后重新取图，不能把占位图标记为完成下载。
- 包子：旧 App 章节映射到同站公开网页，按公开页面图片及官方脚本声明的备用 CDN 取图，保留路径和查询参数；短连接超时、成功线路复用，不固定 IP 或关闭 TLS 校验。
- 旧式漫画规则：补齐分层排除索引、单竖线备用规则及单井号替换，修复截图中的 `.pure-g!0` 选择器错误。
- 导入：70 分享集合完整保留 76 项（启用 15、停用 61）；71 完整保留 97 项（启用 18、停用 79）。无跳过项。停用源仍可管理、编辑、导出；不等于每个站点可用。
- 搜漫：范围收进限高下拉菜单，同时显示导入总数和启用 / 停用数，减少固定控件遮挡。
- 小说在线 / 离线左右滑动及两侧点按；在线章节内真实分页、跨章回到上章末页，按字符锚点恢复。横向读取不再等待未挂载的纵向列表完成布局。
- 漫画横向 / 日漫两侧点按、左右滑动与跨章，缩放时避免误翻页；自动续章只响应真实拖动，不把恢复位置时的程序滚动当成用户已读到末尾。

## 实际验证

- JUnit：151 项通过，0 失败 / 错误 / 跳过。
- Debug 与 R8 独立 QA 主流程均以 `INSTRUMENTATION_CODE: -1` 结束，日志无 `SMOKE_FAILED`。覆盖受控原创测试站点搜索、正文下载和导入、在线书架 / 阅读 / 笔记 / 独立设置、备份恢复及转离线；漫画在线图、GIF 回退、裁边、完整缓存、CBZ 导入、五个导航入口；两种小说左右翻页、漫画三种方向、滑杆 / 音量、跨章和 320dp / 1.6 倍字体长图锚点；下载断点、坏章重取和大书返回。
- YYDS《道诡异仙》所测章节「01 师傅」61 张；包子所测章节「02 好徒兒」47 张。在手机实际网络请求两源各自首 / 中 / 末图，校验字节数和解码宽高，并挂载正式 `MangaReaderScreen` 查看首图。截图为本地 QA 证据，不随安装包分发。没有逐张下载全部章节。
- Release lint：0 errors / fatal，29 warnings，5 hints。保留已有 API / 依赖等提示，没有为清零提示升级无关组件。
- R8 独立 QA 包另做 3 次冷启动：每次启动返回 `Status: ok`，等待 2 秒后进程仍存活，按本次 PID 检查 crash buffer 未见 Java / native fatal 标记。这是短时启动检查，不等同长时稳定性验证。记录见 `.tools/fix-1.8.1-r8-coldstart.json`。
- 早期回归遇到 USB 系统弹窗、无障碍缓存与重复读取误判，未计为通过；脚本已修正后重跑。一次安装后的 instrumentation 启动报告 `Process crashed`，未取得 Java 崩溃栈，该次也未计通过。以最终日志和后续独立冷启动结果为准，不由一次通过推断长期无崩溃。
- 本轮第一次 R8 回归与外部 `uiautomator dump` 检查安装界面重叠，测试进程的 `Instrumentation.finish` 出现 `Cannot call disconnect() while connecting UiAutomation`，已保存该次失败日志和栈，不计通过；最终回归期间不再运行外部无障碍抓取。

### R8 原创样本与翻页记录

```text
HORIZONTAL_NOVEL_OK kind=ONLINE left-right swipe and side taps, restored to first page
ONLINE_HORIZONTAL_CHAPTER_BOUNDARY_OK previous last page and next first page, progress persisted
MANGA_INTERACTION_OK mode=VERTICAL slider+volume+image anchor=0.14508918 restored at 320dp/fontScale=1.6
HORIZONTAL_MANGA_OK reversed=false side taps and both swipe directions
MANGA_CHAPTER_BOUNDARY_OK mode=HORIZONTAL next first page and previous last page
MANGA_INTERACTION_OK mode=HORIZONTAL slider+volume+image anchor=0.14380221 restored at 320dp/fontScale=1.6
HORIZONTAL_MANGA_OK reversed=true side taps and both swipe directions
MANGA_CHAPTER_BOUNDARY_OK mode=RTL next first page and previous last page
MANGA_INTERACTION_OK mode=RTL slider+volume+image anchor=0.14420307 restored at 320dp/fontScale=1.6
AGGREGATE_PIPELINE_OK chapters=8 sourceLimit=4 peak=4 bookRequests=2 elapsedMs=861
AGGREGATE_PIPELINE_OK chapters=8 sourceLimit=1 peak=1 bookRequests=2 elapsedMs=1908
HORIZONTAL_NOVEL_OK kind=OFFLINE left-right swipe and side taps, restored to first page
READER_LARGE_BOOK_OK kind=chapters chars=1099692 openMs=3128 maxMainHeartbeatMs=1
READER_LARGE_BOOK_OK kind=unbroken chars=175000 openMs=215 maxMainHeartbeatMs=1
```

### R8 真实源记录

```text
MANGA_LIVE_IMAGE_OK source=yyds index=0 bytes=262900 width=900 height=2799
MANGA_LIVE_IMAGE_OK source=yyds index=30 bytes=91978 width=900 height=1222
MANGA_LIVE_IMAGE_OK source=yyds index=60 bytes=160478 width=900 height=2211
MANGA_LIVE_READER_UI_OK source=yyds image displayed in production MangaReaderScreen
MANGA_LIVE_IMAGE_OK source=baozi index=0 bytes=438426 width=1200 height=3657
MANGA_LIVE_IMAGE_OK source=baozi index=23 bytes=292555 width=1200 height=2110
MANGA_LIVE_IMAGE_OK source=baozi index=46 bytes=246867 width=1200 height=3060
MANGA_LIVE_READER_UI_OK source=baozi image displayed in production MangaReaderScreen
```

性能数字来自原创受控样本，不代表第三方站点整书下载耗时承诺。

## 正式 APK

| 项目 | 核对结果 |
| --- | --- |
| 文件 | `dist/圣子猫阅读-手机版-1.8.1.apk` |
| 包名 / 版本 | `com.shengzimao.reader.mobile` / 1.8.1 / 14 |
| 最低 / 目标 API | 26 / 36 |
| 字节数 | 22135250 |
| APK SHA-256 | `52f7042b4424c505b41e0164c425a733e483b0ad2a0acdcffe20b3c4fb645b00` |
| 发布证书 SHA-256 | `f1b4fc6177f52213f22c19efa885caf6315ad6dd05cbb87c18a35af4c52670b7` |
| 发布签名 | apksigner verify 通过，与既有正式版相同证书 |
| 引擎 / 许可 assets | 18 项逐字节 SHA-256 与源码匹配 |
| 发布隔离 | 非 debuggable，不含检查的 QA 标记、测试源 70 / 71 / legado 样本或签名配置 |

同目录提供 `.apk.sha256`。构建自动使用本机已有签名配置，没有在记录中读取或暴露密钥及密码。未覆盖安装或改动正式版书库。

## 复现与证据

JDK 21、Gradle 9.7.1、AGP 9.4.1、Compose 编译插件 2.4.20、compileSdk 37 / targetSdk 36。

```powershell
$env:JAVA_HOME='D:\java21'
& '.tools/gradle-9.7.1/bin/gradle.bat' --offline '-Pkotlin.internal.collectFUSMetrics=false' :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
& '.tools/gradle-9.7.1/bin/gradle.bat' --offline --no-parallel --max-workers=4 '-Pkotlin.internal.collectFUSMetrics=false' '-PonlineTestBuildType=verification' :app:assembleVerification :app:assembleVerificationAndroidTest :app:assembleRelease :app:lintRelease
adb shell am instrument -w -r com.shengzimao.reader.mobile.verification.test/com.shengzimao.reader.mobile.OnlineSmokeRunner
adb shell am instrument -w -r com.shengzimao.reader.mobile.verification.release.test/com.shengzimao.reader.mobile.OnlineSmokeRunner
adb shell am instrument -w -r -e suite manga-live com.shengzimao.reader.mobile.verification.release.test/com.shengzimao.reader.mobile.OnlineSmokeRunner
```

命令行关闭的是 Kotlin 本次构建的 FUS 指标收集，避免本机大量历史指标文件拖慢构建收尾，不改变 App 功能或全局用户设置。QA 变体和 Release 均开启 R8，QA 仅保留跨 APK 测试入口，二者不完全同字节。真实源诊断使用测试 assets，正式包不内置这些漫画源。

本地日志（不随源码发布）：`fix-1.8.1-debug-final4.log`、`fix-1.8.1-r8-final.log`、`fix-1.8.1-r8-live-final.log`、`fix-1.8.1-final-release-build3.log`、`fix-1.8.1-apksigner.log`、`fix-1.8.1-badging.log`、`release-1.8.1-static-check.json`，均位于 `.tools/`。

## 使用边界

- 只验证上述真实作品的首章样本；97 项完整导入不代表 97 个站点或全部章节均能读取。私有加密、专用扩展、登录 / 付费 / 验证限制仍需在原站处理，不通过强制启用绕过。
- 在线条目按章联网；完整下载 / 本地导入才有离线保障。临时预取不算完成下载，清理临时缓存保留主动下载。
- 没有穷举全部 Android 版本、设备、第三方源、图片格式、TTS、TalkBack、横屏 / 大字组合或长时后台运行。外站改版或节点不可达仍可能需要更新规则。
- 本轮只更新本地源码、文档并生成 APK，没有推送 GitHub 或发布远程 Release。
