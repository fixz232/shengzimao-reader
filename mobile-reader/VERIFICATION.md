# Android 1.1.2 验证记录

验证日期：2026-10-24。以下只描述实际执行范围，不表示全部第三方书源、作品或 Android 版本均可用。

## 环境与产物

- 真机：realme RMX5010，Android API 36，arm64；Windows / PowerShell、JDK 21、Gradle 9.7.1、AGP 9.4.1、compileSdk 37、build-tools 36.0.0。
- 正式包：`com.shengzimao.reader.mobile`，versionCode 4，versionName 1.1.2；交付文件 `dist/圣子猫阅读-手机版-1.1.2.apk`。
- APK SHA-256：`d9477e98bf2c3fac48d3bdd3a203403e693f4dc5366f7724c84c1fc2fe3d9573`。
- 发布证书 SHA-256：`f1b4fc6177f52213f22c19efa885caf6315ad6dd05cbb87c18a35af4c52670b7`，与原 1.1.1 发布包相同，版本递增。
- 已逐项核对正式 APK 的四个引擎 dex、application.properties 与字体数据许可资产，均与源码目录内产物的 SHA-256 相同。
- 核心 dex SHA-256：`b095e7196077fc589b1c9773464eb5a13558ee90f693864b00a3094a75011b9b`、`8e5271530f3301d4a4e86ab6fadb4a5d5cea1c0bfafa4622e782e82edec8e550`；wrapper dex：`30ae22d48d69e936f7fbf5b22918744649305fab1151bf050c6b00702c45751c`。
- Debug QA：`com.shengzimao.reader.mobile.verification`；优化 QA：`com.shengzimao.reader.mobile.verification.release`，后者开启 R8 与资源压缩。
- 优化 QA 仅额外保留独立 instrumentation APK 使用的 Kotlin 测试入口，见 `app/verification-rules.pro`。正式包不使用这些测试规则；引擎 dex 与正式包相同。
- QA 只使用独立包名与私有测试目录，没有卸载、清除或读写用户正式应用书库。真实作品正文仅短暂写入 QA 缓存并清理，不随源码或日志分发。

## 根因与回归覆盖

1. 普通 `needProxy=false` 书源的 BookParser 会调用桌面 CoverUpdater，直接使用 Android 不支持的虚拟线程工厂及 AWT。1.1.1 合成规则的 `needProxy=true` 漏测此路径；本次改为 false，验证真实详情、TXT、EPUB 与入库。
2. Android 替换 CoverUpdater 为保留源站封面，并在构建时替换上游间接 executor 工厂及 close 调用。最终重写日志匹配 AggregatedSearchAction 和 SourceUtils，共 4 处调用。
3. 番茄旧解析会先取外层 article 或误匹配 `muye-reader-content-16`，嵌套 div 又被非平衡正则截断。新解析按精确 class token、成对标签取正文，排除标题、目录链接及导航；支持与桌面同源的已知公开网页字体，未知字体或缺字仍拒绝。
4. 上游随机间隔不接受最小值等于最大值。Android 章节解析在套用书源配置后规范化固定区间，覆盖 0 ms 正常下载与连续章节追更；失败任务仍不会入库。

## 通过项

| 项目 | 实测结果 |
| --- | --- |
| 单元测试 | 30 项，0 失败 / 0 错误；含解析、匹配、编码、目录核对、嵌套正文、目录与标题拒绝、已知 / 未知字体、乱序响应、并发上限、递增进度、整次失败及取消 |
| 构建 | assembleDebug、assembleDebugAndroidTest、assembleRelease、assembleVerification、assembleVerificationAndroidTest 成功；发布关键 lint 检查通过 |
| ART / 引擎 | Debug 与优化 QA 均加载 14 条规则，完成 Rhino 脚本搜索及普通书源详情，不再发生本次虚拟线程报错 |
| 聚合下载 | 两种 QA 均从本地 HTTP 原创站点并发下载两章；请求设置乱序延迟，保存后仍按目录排序，峰值并发 2（配置上限 4） |
| TXT / EPUB / 入库 | 两种 QA 均核对两章完整原创正文、元数据与私有测试书库读回，EPUB 导出再解析仍保留正文 |
| 聚合追更 / 失败 | 连续两章的固定 0 ms 配置可用；只有章节标题的响应使整次任务失败，失败任务目录被清理 |
| 番茄真实联网 | 两种 QA 均搜索“我不是戏神”、核对作者“三九音域”、读取详情与 1928 章目录（仅为当次响应快照） |
| 番茄真实正文 | 两种 QA 均下载前三章并读回 QA 私有缓存，合计 7182 字符、3 个章节；Debug 541 ms，优化 QA 468 ms；没有把目录或标题当正文 |
| 签名 / 资产 | 正式包与 1.1.1 同一证书，包名保持一致、版本递增；正式 APK 引擎及许可资产与源码产物一致 |

## 提速测量

同一个 OkHttp 拦截器为 8 章原创正文设置每章 300 / 700 ms 延迟。旧流程基线逐章请求，保留旧版额外 200 ms 间隔；新流程最多并发 4 章、启动请求至少间隔 120 ms，按目录顺序合并。

```text
TOMATO_DOWNLOAD_BENCHMARK chapters=8 serialMs=4722 parallelMs=1420 peak=4
```

此次受控测量约 3.3 倍，不是对真实网站的固定速度承诺。真实网络样本只有 3 章，不能据此推算整本书耗时。

优化 QA 的关键输出：

```text
ENGINE_OK {"version":"android","sources":14}
TOMATO_SEARCH_OK 3 results, 我不是戏神 / 三九音域
TOMATO_DETAILS_OK 1928 chapters, 都市高武 / 都市 / 穿越
TOMATO_PUBLIC_BODY_IMPORT_OK 3 chapters, 7182 chars, 468 ms; QA directory only
AGGREGATE_HTTP_SEARCH_OK Rhino rule + local HTTP source
AGGREGATE_COVER_ANDROID_OK needProxy=false details uses source cover without virtual threads or AWT
AGGREGATE_DETAILS_DOWNLOAD_IMPORT_OK 2 synthetic chapters, private test library
AGGREGATE_EPUB_OK native export and import preserves both chapter bodies
AGGREGATE_UPDATE_OK fixed zero interval remains valid across chapter parsing
AGGREGATE_BODY_VALIDATION_OK title-only failure rejects whole job; peak=2
```

## 复现命令

从 mobile-reader/ 执行，先按 engine/README.md 重建引擎；使用已配置的 Gradle、Android SDK 与 adb：

```powershell
gradle :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e network true com.shengzimao.reader.mobile.verification.test/com.shengzimao.reader.mobile.OnlineSmokeRunner

gradle :app:assembleRelease :app:assembleVerification :app:assembleVerificationAndroidTest -PonlineTestBuildType=verification
adb install -r app/build/outputs/apk/verification/app-verification.apk
adb install -r app/build/outputs/apk/androidTest/verification/app-verification-androidTest.apk
adb shell am instrument -w -e network true com.shengzimao.reader.mobile.verification.release.test/com.shengzimao.reader.mobile.OnlineSmokeRunner
```

`network true` 访问番茄公开搜索、详情、目录与前三章正文；省略时只运行本地引擎和原创 HTTP 流程。必须检查 `SMOKE_FAILED` / 进程错误，不能只看 adb 退出码。单测使用默认 debug testBuildType，应与 verification 命令分开运行。

## 未覆盖与边界

- 未验证整部真实作品、全部第三方书源、其他 Android 系统 / ABI、长时间后台运行或持续限流行为。14 条 probe 仅代表加载成功。
- 没有在用户正式书库执行覆盖安装或追更；仅核对签名与版本，并用独立 QA 目录验证存取。
- 登录、付费、验证码、未知字体、缺字或不可读正文会停止。公开可见不表示获得转载授权。
- Android 已同步已知公开网页字体数据，没有同步桌面的公开文集备用来源。
- 此次主要验证下载、正文解析与并发，没有重新全面验证阅读 UI、TTS、备份和所有文档导入边界。
- 旧版已入库的“只有目录”文件不会自动覆盖。先备份，再从书库移除该空书后重新下载；相同在线 URL 的已有书仍按原逻辑去重，避免覆盖用户正文。
