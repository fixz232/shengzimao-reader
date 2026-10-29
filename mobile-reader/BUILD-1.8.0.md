# Android 1.8.0 构建与验证记录

日期：2026-10-25。版本 `1.8.0` / `versionCode 13`。本轮落实阅读器评审中的阅读、下载与 UI 改进，修复源导入不完整及在线漫画图片格式错误。

## 主要修改

- 漫画源完整保存：97 项真实集合样本全部保留，3 项声明式、94 项旧式；79 项因私有加密、专用解码或关键规则缺失默认停用，界面显示原因。常见旧式脚本使用受限 Rhino 解释器，原始 JSON 可编辑和导出。修复 Android ICU 的插值正则及片段根节点 `@href` 提取。
- 图片解码：区域解码失败后采用有像素上限的整图回退，GIF 真机解码通过；错误中文提示并支持图片级重试。系统本身不支持的格式仍可能需要换源或格式适配。
- 漫画阅读：三方向滑杆 / 音量键实际跳页；等待尾图占位展开后再定位，修复横向长图监听旧恢复状态的问题。图内比例与稳定图片引用可恢复；补齐页面书签、缩略图、双页、裁边、作品独立偏好及 ZIP 文件夹卷章。
- 小说阅读：精简顶栏、底栏换行、目标章节预览与返回跳转前位置、校验分页缓存、正文无障碍语义、听书定时、作品独立排版、标签及笔记导出。
- 在线小说：共用阅读菜单，增加当前章搜索 / 高亮、听书、设置、书签 / 段落笔记、会话预取、文字锚点、换源章节校准和目录更新。在线转离线按章节 URL / 正文映射位置、书签和笔记，原在线记录仍保留。
- 下载：暂停 / 继续、提前排序、新任务 Wi-Fi 策略、进度持久化、章节 SHA-256 断点、空间预检、前台 dataSync 服务及通知。修复 Android 不支持的文件 API，适配层使用兼容的文件、集合和 Unicode 空白操作。通知定期采样，持续更新时不会一直停留在准备状态。
- 漫库：继续阅读、紧凑统计、网格 / 紧凑列表、未读 / 区间下载、更新检查；主动下载与临时缓存分离，删除作品清理独占下载，保留其他作品共享图片。
- 备份：保留旧小说 ZIP 结构，扩展设置、漫画目录 / 进度 / 书签 / 去凭据字段的源，图片可选并显示体积。漫画恢复先验证后提交，失败回滚新增图片和漫画索引；合并时保留已有作品。
- 文档与许可：同步 README、源格式、声明与验证入口，补充 Rhino 1.7.15 许可 / NOTICE 原文并打入 APK。

## 已通过验证

- 单元测试：143 项，0 失败、0 错误、0 跳过；覆盖完整源配置往返、规则执行限制、片段目录链接、设置 / 标签、分页缓存、备份验证、下载控制及在线笔记转离线。
- 真机：realme RMX5010，Android 16 / API 36。仅安装独立 Debug / R8 QA 包，不覆盖、读取或清理正式包书库。
- Debug 与 R8 完整 instrumentation 均返回 `INSTRUMENTATION_CODE: -1`：原创 HTTP 小说搜索、两章在线读、书签 / 设置面板、TXT / EPUB、追更、标题空正文拒绝、同条目转离线与书签迁移；97 项源解析、旧式规则全链、在线看漫、GIF 回退 / 裁边、漫画下载、清临时缓存后离线、CBZ 和含图片备份恢复、五个导航入口均通过。
- Debug 首次执行被手机系统中断，exit-info 记录 `OTHER KILLS BY SYSTEM` / `Cached(nirvana)[(instrumentation)]`；回到 QA 前台后完整重跑通过。中断日志保留为 `.tools/upgrade-1.8.0-device-debug-final4-system-interrupted.log`，系统退出记录见 `upgrade-1.8.0-device-debug-system-exit.log`，不把中断那次计为通过。
- 三种漫画方向分别通过滑杆跳转、音量键前后页、长图滑动后退出 / 重新打开；在 320dp 内容宽度、fontScale 1.6 下恢复相近内容。已查看该约束下的实际截图，底栏主要动作可换行显示。这不是全设备 / 全字体 / TalkBack 手工穷举。
- 下载原创 8 章样本验证有界并发及持续补位、正文有序；重复下载已校验断点为 0 次正文请求，破坏 1 章只重新请求该章。
- 大书与无换行正文实际排版、主线程心跳及中途返回测试通过；具体耗时见日志摘录。
- Release / verification 构建与 lint 通过：0 errors, 29 warnings, 5 hints。保留与目标 SDK、依赖 / 图标 / 现有 API 等有关的提示，未为清零警告升级无关依赖。
- 回归结束后对 R8 QA 包执行普通冷启动，`am start -W` 返回 COLD / ok；首页五个导航标签齐全，当前进程存活，当前 PID 的 crash buffer 无崩溃记录。这是本次短时冷启动结果，不代表长时运行保证。

### Debug 受控样本

- `AGGREGATE_PIPELINE_OK chapters=8 sourceLimit=4 peak=4 bookRequests=2 elapsedMs=908`
- `AGGREGATE_PIPELINE_OK chapters=8 sourceLimit=1 peak=1 bookRequests=2 elapsedMs=2019`
- `READER_LARGE_BOOK_OK kind=chapters chars=1099692 openMs=207 maxMainHeartbeatMs=0`
- `READER_LARGE_BOOK_OK kind=unbroken chars=175000 openMs=120 maxMainHeartbeatMs=6`

### R8 受控样本

- `AGGREGATE_PIPELINE_OK chapters=8 sourceLimit=4 peak=4 bookRequests=2 elapsedMs=861`
- `AGGREGATE_PIPELINE_OK chapters=8 sourceLimit=1 peak=1 bookRequests=2 elapsedMs=1901`
- `READER_LARGE_BOOK_OK kind=chapters chars=1099692 openMs=1347 maxMainHeartbeatMs=2`
- `READER_LARGE_BOOK_OK kind=unbroken chars=175000 openMs=261 maxMainHeartbeatMs=1`

以上是本地原创样本的性能，不代表任何第三方站点或整本书的下载速度承诺。

## 正式 APK 核对

| 项目 | 结果 |
| --- | --- |
| 交付文件 | `dist/圣子猫阅读-手机版-1.8.0.apk` |
| 包名 / 版本 | `com.shengzimao.reader.mobile` / 1.8.0 / 13 |
| 最低 / 目标 API | 26 / 36 |
| 文件大小 | 22,070,226 字节 |
| APK SHA-256 | `7c38abca7cae6f4ed764cfce3fdded424c42333c9bdf852bb34056b032546978` |
| 发布证书 SHA-256 | `f1b4fc6177f52213f22c19efa885caf6315ad6dd05cbb87c18a35af4c52670b7` |
| 签名 | apksigner verify 通过，与既有正式版同一发布证书 |
| 引擎 / 许可资产 | 18 项全部与源码 assets 逐字节 SHA-256 相同 |
| wrapper.dex | `e1e39cf3a4bc30576a66720c95dd21725dc4f42a85e5e68e01c823c1620975ca` |
| QA 代码 | 正式 APK 不含所检查的 QA 日志标记或测试源样本 |

同目录附 `.apk.sha256`。构建工具自动使用本机已有签名配置，记录不含密钥、签名密码或私人数据。

## 复现与本地证据

使用 JDK 21、Gradle 9.7.1、AGP 9.4.1、compileSdk 37、build-tools 36.0.0。

```powershell
& '.\engine\build-engine.ps1'
& '.\.tools\gradle-9.7.1\bin\gradle.bat' :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
& '.\.tools\gradle-9.7.1\bin\gradle.bat' -PonlineTestBuildType=verification :app:assembleVerification :app:assembleVerificationAndroidTest :app:assembleRelease :app:lintRelease
# 独立 QA 包安装后，各自运行对应的 instrumentation 组件；正式包不用于回归。
adb shell am instrument -w -r com.shengzimao.reader.mobile.verification.test/com.shengzimao.reader.mobile.OnlineSmokeRunner
adb shell am instrument -w -r com.shengzimao.reader.mobile.verification.release.test/com.shengzimao.reader.mobile.OnlineSmokeRunner
```

QA 原创样本源码位于 `app/src/qa/java/` 及 `app/src/androidTest/`，只编入独立测试变体。正式 Release 没有这些样本。QA 保留少量跨 APK 入口以及所需 Kotlin 测试调用；它与正式版均开启 R8，但两者不是字节完全相同的 APK。

本地日志（不随源码发布）：`.tools/upgrade-1.8.0-pass20.log`、`upgrade-1.8.0-final-build2.log`、`upgrade-1.8.0-device-debug-final4.log`、`upgrade-1.8.0-device-r8-final.log`、`upgrade-1.8.0-apksigner.log`、`release-1.8.0-static-check.json`。
冷启动证据：`.tools/upgrade-1.8.0-cold-start.log`、`upgrade-1.8.0-cold-start-crash.log`；窄屏漫画 R8 布局预览为 `qa-1.8.0-manga-320dp-r8.png`（查看前已裁去系统通知区域）。

## 使用与验证边界

- 配置完整保存不等于 97 个源站全部可读。本轮不逐站联网验证，也不实现私有解密、专用扩展或登录 / 付费 / 验证码绕过。复杂脚本及动态请求规则仍可能需要单独适配，只应导入可信源；解释器限制不等于对任意恶意脚本的绝对安全保证。
- 在线条目按章联网；完整下载的章节及本地导入才有长期离线保障。会话预取和临时缓存不能当作完成下载。
- 系统结束进程后，任务保留断点，回到下载管理主动继续；服务为 START_NOT_STICKY。未完成所有厂商省电、通知权限、断网 / 切网、强制结束及重启设备情境的真机穷举，不承诺无限后台保活。
- 默认备份不含漫画图片。本地漫画恢复目录后需重新导入原始文件；无图时明确提示。旧桌面只恢复其支持的小说部分。小说与漫画分步合并，不承诺跨两个书库和设置的全局数据库事务。
- 未真机覆盖最低 API 26、所有图片格式 / 漫画源、TTS 引擎及 TalkBack、所有横屏 / 大字体组合。双页、页面书签和搜索高亮等已实现，但不能把本次主流程测试当作每个交互的手工穷举。
- 无法匹配到本次下载章节的旧源笔记不强行定位，原记录保留在备份元数据中。
- 本轮只生成本地 APK 和源码修改，没有推送 GitHub 或发布远程 Release。
