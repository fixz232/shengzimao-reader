# Android 1.7.0 构建与验证记录

日期：2026-10-25。版本 `1.7.0` / `versionCode 12`。本次按用户要求修复移动端故障，增加小说和漫画的在线阅读，并生成正式 APK。

## 功能与修复

- 在线搜书（聚合与番茄共用卡片）：搜索结果直接提供「在线阅读」「加入书架」「详情 / 目录」「下载入库」。在线阅读按章联网，支持目录检索、前后章、重试和阅读进度。
- 在线搜漫：搜索结果直接提供「在线看漫」「加入书架」；目录尚未加载时先读取详情，错误显示在页面，不在主线程抛出异常。详情页保留下载导入。
- 在线条目只保存入口、目录和进度；下载完整正文 / 全部漫画图片后转换为离线模式。小说保留原条目 ID 并转换章节进度，已有离线作品不改变模式。
- 修复在线小说正文已返回但加载动画不结束的问题：先挂载列表，再恢复滚动位置，避免等待尚未创建的布局。
- 漫画源旧式规则在 Android ICU 上的未转义正则已修正；不支持的脚本或解密规则明确跳过。批量漫画下载先完成入架，再进入下载管理。
- 漫画清理 / 裁剪缓存后撤销缺页章节的已下载标记；条漫仅在用户实际滑到末页底部后自动翻章。
- 聚合下载 worker 持续领取章节，正文按目录合并；上游格式器重载配置后，在下一章解析前重新校正固定请求间隔，避免第 6 章等后续章节失败。
- 引擎的 26 处 `System.exit(int)` 调用改为可恢复异常，避免命令行错误处理直接关闭 Android 应用。引擎资源版本更新为 v14。
- 番茄并发下载的计数与进度回调一起串行，避免进度倒退；索引 / 下载记录使用独立临时文件并串行原子提交，避免并发写入时临时文件被移走。
- 修复文本流读取在 API 26–32 的兼容性、回退缓冲容量以及 UTF-8 采样截断边界；长篇分页在后台执行并支持取消。
- 在线小说备份只保存在线元数据，不误报正文缺失；书库 / 漫库提供在线、离线模式标签与筛选。

## 已完成的本地验证

- 单元测试：127 项，0 失败，0 错误；包括文本解码、番茄并发 / 取消 / 有序进度、在线书架模型和索引并发写入。
- 连接设备：realme RMX5010，Android 16；仅使用独立测试包及 QA 私有缓存，不清除正式包或正式书库。
- 真机已验证小说搜索结果按钮进入在线阅读、两章正文和下一章、阅读进度与在线备份、同条目转离线；漫画搜索结果按钮进入在线阅读、实际图片解码显示、空目录容错、全章下载、离线读取、清理缓存后状态恢复、CBZ 三图导入。
- 用户提供的 97 项源样本已在 Android ART 上解析：3 项可转换、94 项明确跳过。转换成功不代表源站当前可用，没有执行源脚本。

Debug 完整回归返回 `INSTRUMENTATION_CODE: -1`：8 章原创下载样本在源上限 4 时峰值并发 4、约 890 ms，在源上限 1 时峰值 1、约 1954 ms；目录只读取 2 次。约 110 万字符 / 600 章打开约 1535 ms，17.5 万字符无换行文本约 288 ms，均能返回测试书库。上述时间只代表受控样本，不是外站速度承诺。

Release 构建与 lint 已通过：0 error、20 warning、5 hint，提示包括目标 SDK、依赖更新、现有 API / 图标等，没有用升级无关依赖的方式扩大本轮范围。

R8 完整回归同样返回 `INSTRUMENTATION_CODE: -1`，通过小说在线读、漫画在线看、下载与离线转换、备份、源样本、CBZ、五页导航、流水线及长篇阅读。优化 QA 的 8 章样本为 4 路约 844 ms / 单路约 1903 ms；110 万字符约 1145 ms、无换行正文约 217 ms，最大主线程心跳分别为 5 / 1 ms。结果不是外站速度承诺。

测试逻辑位于 `app/src/qa/java/`，仅编入 Debug / verification；页面与回归代码一起优化，只保留三个跨 APK 测试入口，正式 Release 不包含该测试目录。回归中发现并修正了独立测试组件调用已被 R8 特化 / 移除的方法时触发的 `NoSuchMethodError`；该问题通过调整 QA 入口解决，没有放宽正式包的压缩规则。

完成 instrumentation 后另行普通冷启动优化 QA：约 252 ms，书库及五个底部入口正常显示，进程保持运行，当前 PID 的 crash buffer 未发现 `FATAL EXCEPTION`。Debug 普通冷启动约 1003 ms，也已查看真机截图。没有把 instrumentation 自动结束进程当成普通启动验证。

最终日志保存在本地 `.tools/repair-1.7.0-device-debug.log`、`.tools/repair-1.7.0-device-r8.log`、`.tools/repair-1.7.0-final-build.log` 和 `.tools/repair-1.7.0-cold-start.json`。

## 正式 APK 核对

| 项目 | 结果 |
| --- | --- |
| 文件 | `dist/圣子猫阅读-手机版-1.7.0.apk` |
| 包名 / 版本 | `com.shengzimao.reader.mobile` / 1.7.0 / 12 |
| 最低 / 目标 API | 26 / 36 |
| 文件大小 | 20,789,126 字节 |
| APK SHA-256 | `1c4d5c47921da4780c3362b0095bd9a7c8273289ce80011fbca26f37b10d8994` |
| 发布证书 SHA-256 | `f1b4fc6177f52213f22c19efa885caf6315ad6dd05cbb87c18a35af4c52670b7` |
| 签名 | apksigner verify 通过，与既有正式版本同一发布证书 |
| 引擎 / 许可资产 | APK 内 15 项均与源码目录逐字节 SHA-256 相同 |
| wrapper.dex SHA-256 | `b300839128687415ddfc94923d0db8f80323e88aac3cccb164a5ce5965b2e199` |

APK 同目录提供 `.apk.sha256`；完整资产清单保存在本地 `.tools/release-1.7.0-static-check.json`。

## 构建命令

使用 JDK 21、Gradle 9.7.1、AGP 9.4.1、compileSdk 37；最低 API 26、目标 API 36。

```powershell
& '.\engine\build-engine.ps1'
& '.\.tools\gradle-9.7.1\bin\gradle.bat' :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --console=plain
& '.\.tools\gradle-9.7.1\bin\gradle.bat' -PonlineTestBuildType=verification :app:assembleVerification :app:assembleVerificationAndroidTest :app:assembleRelease :app:lintRelease --console=plain
```

Debug 测试包名 `com.shengzimao.reader.mobile.verification`；R8 回归包名 `com.shengzimao.reader.mobile.verification.release`。二者的桌面名称与正式版区分，均使用独立数据目录。

## 验证边界

- 主流程的确定性网络回归使用本机原创 HTTP 小说 / 漫画样本，便于确认正文、图片、请求顺序和并发上限；不据此保证每个外站或用户导入的每个源可用。
- 已修复能定位的异常路径。用户反馈那次退出未捕获到对应的新 Java 崩溃栈，不能断言每一种闪退原因都已覆盖；本轮发现的 QA 跨 APK 接口异常已修复并通过整套复测。测试组件结束后会退出测试进程，完成回归后已另行普通启动测试版。
- 在线阅读需要源站及网络可用；不绕过登录、付费、验证码或私有加密。漫画图片会产生临时缓存；清理后需要重新联网。
- 下载速度受源站延迟、并发上限及网络影响，不承诺固定提速倍数。下载队列不承诺应用进程结束后仍在后台继续。
- 小说 `.szmbak` 不包含漫画源、漫库和漫画图片；协议与内容权利说明见 [README](README.md)、[漫画源说明](MANGA_SOURCES.md) 和 [项目声明](../NOTICE.md)。
- 本轮不推送 GitHub，不发布线上 Release，不安装或覆盖正式包。
