# Android 1.1.1 验证记录

验证日期：2026-10-23。此记录只描述实际执行范围，不表示全部第三方书源或全部 Android 版本均可用。

## 环境与产物

- 真机：realme RMX5010，Android API 36，arm64。
- 构建：Windows、JDK 21、Gradle 9.7.1、AGP 9.4.1、compileSdk 37、build-tools 36.0.0。
- 正式包：`com.shengzimao.reader.mobile`，versionCode 3，versionName 1.1.1。
- APK SHA-256：`1f747eec2d205765fbff97ed9c33f78b9d67429d025e4716a643f653e8324818`；打包后逐项核对引擎资产与源码目录内产物一致。
- Debug QA：`com.shengzimao.reader.mobile.verification`。
- 优化 QA：`com.shengzimao.reader.mobile.verification.release`，开启 R8 和资源压缩，使用独立包名及调试签名。
- 优化 QA 额外保留仅由独立 instrumentation APK 调用的 Kotlin 测试辅助入口，见 `app/verification-rules.pro`；正式包不使用这些测试规则。引擎 dex 资产与正式包相同。
- QA 在独立测试目录生成原创两章短文，不读取或覆盖发布版书库，没有卸载或清除原应用数据。

## 通过项

| 项目 | 结果与范围 |
| --- | --- |
| 单元测试 | 24 项通过、0 失败：解析、编码、匹配、目录核对、发布结构、番茄请求参数及解析边界 |
| 构建 | `assembleDebug`、`assembleRelease`、`assembleVerification`、`assembleVerificationAndroidTest` 成功；发布关键 lint 检查通过 |
| 引擎初始化 | Debug / 优化 QA 均在真实 ART 上加载引擎，probe 返回 14 条规则 |
| HTTP 与 JavaScript 规则 | 两种 QA 包均通过本地 HTTP 合成站点与 `@js:` Rhino 搜索 |
| 详情与正文 | 两种 QA 包均读出两章目录、完整下载，并核对两章真实测试正文，拒绝把错误占位当正文 |
| TXT 入库 | 两种 QA 包均完成元数据校验、私有测试书库保存与读回 |
| EPUB | 两种 QA 包均完成原生 EPUB 导出、解析与两章正文保留检查 |
| 番茄联网 | Debug 与优化 QA 均真实搜索“我不是戏神”，核对作者“三九音域”；详情和目录返回 1928 章（仅为本次响应快照） |
| 界面 | 真机检查在线页内“聚合搜书 / 番茄小说”、底部导航、ID 搜索、详情 / 目录、官网阅读入口及切页搜索状态保留 |
| 签名 | 正式 1.1.1 与原 1.1.0 发布包证书 SHA-256 相同，可覆盖安装 |

成功的优化 QA 输出：

```text
ENGINE_OK {"version":"android","sources":14}
TOMATO_SEARCH_OK 2 results, 我不是戏神 / 三九音域
TOMATO_DETAILS_OK 1928 chapters, 都市高武 / 都市 / 穿越
AGGREGATE_HTTP_SEARCH_OK Rhino rule + local HTTP source
AGGREGATE_DETAILS_DOWNLOAD_IMPORT_OK 2 synthetic chapters, private test library
AGGREGATE_EPUB_OK native export and import preserves both chapter bodies
```

## 复现命令

以下命令从 `mobile-reader/` 执行，需配置 Gradle、Android SDK 和 adb：

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

`network true` 会访问第三方番茄公开搜索、详情和目录；省略该参数只运行本地引擎与合成 HTTP 流程。检查输出中的 `SMOKE_FAILED` 或进程错误，不能仅以 adb 命令退出码判定通过。

## 未覆盖

- 未在其他 Android 版本、ABI 或全部品牌设备逐一实测；最低安装版本 API 26 不等于已验证所有在线引擎路径在该版本可用。
- 14 条规则的 probe 表示规则加载成功，不代表逐个验证了真实第三方站点的当前可用性。聚合完整链路使用受控合成站点验证，站点规则变化仍可能造成单源失效。
- 番茄联网验证未下载真实作品全文；正文解析的单元测试使用合成公开 / 受限页面。遇到登录、付费、验证码、字体加密或不可读正文会停止，需在官网阅读。
- 未在用户原有发布版书库上执行覆盖升级或追更；只比对发布签名并使用独立 QA 目录验证入库。
- 本次不覆盖 TTS 设备差异、长时间后台任务、全部导入文档和备份边界场景。
