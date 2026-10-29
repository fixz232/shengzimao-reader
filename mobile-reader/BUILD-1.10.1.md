# Android 1.10.1 正式 APK 本地构建记录

日期：2026-10-26。基于现有 1.10.0 源码及本轮修复；经用户授权打包，未安装到手机、未发布 GitHub。

## 正式产物

- 路径：`dist/圣子猫阅读-手机版-1.10.1.apk`
- 包名：`com.shengzimao.reader.mobile`；版本：1.10.1 / versionCode 18。
- 大小：22209158 字节；最低 Android 8.0 / API 26，目标 API 36。
- APK SHA-256：`6fb965392aa134392e6a55221c75810bff8eaf9da4c4340f3beb23d99ddb5225`。
- 签名证书 SHA-256：`f1b4fc6177f52213f22c19efa885caf6315ad6dd05cbb87c18a35af4c52670b7`；apksigner 校验通过，与既有正式版使用相同证书。
- 同目录提供 `.apk.sha256` 校验文件。

## 已核对

- 正式 Release / R8 构建及 lint 成功；APK 非 debuggable。
- 167 项单元测试通过，失败 / 错误 / 跳过均为 0。
- Release lint 无 Error / Fatal；保留 29 条 Warning、5 条 Hint。
- APK 主 dex 包含 `legacySearchPost`、`MangaImportTls` 与兼容重试文案，确认交付包包含两处修复。
- 检查的 QA 日志标记、测试探针、漫画源测试样本及签名配置未进入正式包；18 项引擎 / 许可 assets 与源码逐项 SHA-256 匹配。

## 本轮修复及范围

- 漫源 934：正确发送 `URL@key=value` 表单 POST，保留请求头与 User-Agent；真实链接导入及搜索「魔咒」已在 Windows / JDK 21 下通过应用引擎验证，返回 1 条匹配结果。
- TLS 导入：协议故障时安全重试一次 TLS 1.2 / HTTP/1.1，保留系统证书及主机名校验；6 项针对性单元测试通过。
- 尚未在先前 TLS 报错的另一台手机安装复测，不能据此宣称其问题已彻底解决；此次也未穷举第三方漫画源全部章节或下载。
- 源码修复细节见 [FIXES-1.10.1.md](FIXES-1.10.1.md)。

## 构建命令和证据

Windows / PowerShell、JDK 21、Gradle 9.7.1、compileSdk 37。

```powershell
$env:JAVA_HOME='D:\java21'
& '.tools/gradle-9.7.1/bin/gradle.bat' --offline --no-parallel --max-workers=4 '-Pkotlin.internal.collectFUSMetrics=false' :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
```

本地证据：`.tools/release-1.10.1-build.log`、`release-1.10.1-signing.log`、`release-1.10.1-badging.log`、`release-1.10.1-check.json`。
