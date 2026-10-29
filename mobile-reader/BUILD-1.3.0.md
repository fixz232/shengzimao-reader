# Android 1.3.0 本地构建交付记录

日期：2026-10-24。本轮按用户要求暂停测试，以下仅记录源码改动、编译、打包及安装包静态核对，不表示运行验证通过。

## 交付内容

- 底部五个入口：书库、在线搜书、漫库、在线搜漫、设置。
- 在线搜书新增下载管理，聚合与番茄下载进入持久化队列；漫画离线缓存使用同一队列。提供进度、状态筛选、取消、重试、记录清理、打开阅读。
- 漫库支持 CBZ / ZIP / 多张图片导入、自然排序、搜索、在读 / 收藏 / 离线筛选、改名与移除。
- 在线搜漫支持原生 JSON 格式的 HTML/CSS 或简单 JSON 路径源，文件 / 链接 / 文本导入及预览确认、编辑、启停、删除、导出；不内置真实漫画源。
- 漫画阅读支持条漫、横向及右到左翻页、目录、章节切换、页数、进度、缩放及长图分块解码；图片最多 3 路并发缓存。
- 保留本轮早前的小说修复：排版移出主线程，长文本切片硬上限；聚合下载共用目录快照，有序滚动窗口补充请求，提供章数进度。实际提速与打开书籍退出问题尚未完成本轮真机复测。
- 关于、在线使用声明、README、漫画源协议及 jsoup MIT 许可已补齐。

## 已执行的构建

环境为 Windows / PowerShell、JDK 21、Gradle 9.7.1、AGP 9.4.1、compileSdk 37、build-tools 36.0.0。

```powershell
# 本地 Gradle 安装目录不随源码分发；其他环境使用等价 Gradle 9.7.1 命令。
& '.\.tools\gradle-9.7.1\bin\gradle.bat' :app:compileDebugKotlin --console=plain
& '.\engine\build-engine.ps1'
& '.\.tools\gradle-9.7.1\bin\gradle.bat' :app:assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease --console=plain
```

Debug Kotlin 编译通过；最新改动通过 Release Kotlin / Java 编译、R8 与资源压缩，并成功生成 APK（`BUILD SUCCESSFUL`，2m 43s）。在线引擎的 Java 适配器已重新编译为 dex。现存 API 弃用及中文工程路径警告不影响本次构建，未在本轮处理无关迁移。

## 安装包静态核对

| 项目 | 结果 |
| --- | --- |
| 文件 | `dist/圣子猫阅读-手机版-1.3.0.apk` |
| 包名 | `com.shengzimao.reader.mobile` |
| 版本 | `versionName=1.3.0`，`versionCode=6` |
| 最低 / 目标 API | 26 / 36 |
| 大小 | 20,563,486 字节 |
| APK SHA-256 | `c4b564b26daa317e4de74506b8a53419e8d75e662f2c4a0ec3dffab6bf142296` |
| 签名核对 | `apksigner verify` 通过，与本地 1.2.0 APK 相同证书 |
| 证书 SHA-256 | `f1b4fc6177f52213f22c19efa885caf6315ad6dd05cbb87c18a35af4c52670b7` |

通过 `aapt dump badging` 读取实际 APK 的包名和版本。APK 内以下资产与源码目录逐字节 SHA-256 一致：

| 资产 | SHA-256 |
| --- | --- |
| `engine/dex/classes.dex` | `b095e7196077fc589b1c9773464eb5a13558ee90f693864b00a3094a75011b9b` |
| `engine/dex/classes2.dex` | `8e5271530f3301d4a4e86ab6fadb4a5d5cea1c0bfafa4622e782e82edec8e550` |
| `engine/dex/wrapper.dex` | `890bd903bcd7d4290a52eb46edaf59e8b8048f03876692b663e2800c1206c624` |
| `engine/rhino/dex/classes.dex` | `a86afdf6deea1e47fcca06bb54827c56d35328233b6473c8b92c10ebe4bbeb3f` |
| `notices/jsoup-LICENSE.txt` | `1dcd34a384e31707af7678fd60bda296d03b4f9bf6be36a7d9d2e6c33c8b0860` |
| `notices/fanqienovel-downloader-LICENSE.txt` | `9b4ddf5ce0dcb81471cd84f33a1e2472655b9e7542f0725f5ed6a9fbe703d416` |

## 本轮未执行

- 没有因本轮新功能运行单元测试、instrumentation、真机安装或 UI 测试；Release lint 检查在构建命令中显式排除。
- 没有实测第三方漫画源或整本漫画离线缓存，没有新的小说整本测速，原打开书籍退出问题未完成修复后的真机复测。
- 没有安装或清除用户正式应用，没有修改手机上的正式书库；没有上传本轮代码或发布 GitHub Release。
- [VERIFICATION.md](VERIFICATION.md) 是 1.1.2 历史验证，不能作为本版结果。

## 使用边界

下载队列仅在应用运行期间处理。进程退出后未完成项标为中断，需手动重试；小说从头下载，漫画复用已缓存图片。现有小说 `.szmbak` 不含漫库、漫画源、图片缓存及下载记录；源应单独导出，本地漫画保留原件。自定义源格式及访问、大小、兼容性限制见 [MANGA_SOURCES.md](MANGA_SOURCES.md)。
