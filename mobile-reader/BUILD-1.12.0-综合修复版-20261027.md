# 1.12.0 综合修复版正式 APK

打包日期：2026-10-27。基于本轮完成回归的修复源码构建，保留版本号 1.12.0 / versionCode 22。

- 文件：`dist/圣子猫阅读-手机版-1.12.0-综合修复版-20261027.apk`
- 包名：`com.shengzimao.reader.mobile`，正式 release 变体，启用 R8 和资源压缩。
- 文件大小：22,535,862 字节（约 22.5 MB / 21.5 MiB）。
- 支持 Android 8.0 / API 26 及以上；targetSdk 36。
- APK SHA-256：`517d68252d42edd65e3aedce88313676a0b67f4a7a23f6e3b4be8018037c4cba`。
- 签名证书 SHA-256：`f1b4fc6177f52213f22c19efa885caf6315ad6dd05cbb87c18a35af4c52670b7`，已与上一份 1.12.0 听书增强版正式 APK 核对一致。

本包包含漫源入口断连后的公开直链恢复、无目录条目的同源版本查找与直接阅读、小说翻页、听书定时与音色、漫画下载并发、字体导入修复。详细修复及当前源码的验证证据见 `reports/MOBILE_FIXES_2026-10-27.md`。

构建与检查：

- Windows / PowerShell，JDK 21，Gradle 9.7.1，执行 `:app:assembleRelease --offline --max-workers=2 --console=plain` 成功。
- `apksigner verify --verbose --print-certs` 成功，APK Signature Scheme v2 有效。
- `aapt dump badging` 确认正式包名、版本、SDK 与应用名称。
- `zipalign -c -P 16 -v 4` 成功；ZIP 126 个条目的 CRC 检查成功。
- 分发副本与构建输出的 SHA-256 一致，并已生成同名 `.apk.sha256` 文件。
- 打包前当前源码的 302 项单元测试、12 组 Debug 真机回归和 7 组 R8 独立包回归通过；本次未将正式 APK 安装到用户正式书库上。

构建日志：`.tools/mobile-fixes-release-package-20261027.log`。保留已有 dist 产物，未覆盖旧 APK；未上传 GitHub。
