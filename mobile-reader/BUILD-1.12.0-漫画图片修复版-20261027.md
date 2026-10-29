# 1.12.0 漫画图片修复版正式 APK

日期：2026-10-27；保留版本 1.12.0 / versionCode 22。

- 正式包：dist/圣子猫阅读-手机版-1.12.0-漫画图片修复版-20261027.apk
- 对应源码：dist/圣子猫阅读-1.12.0-漫画图片修复版-20261027-源码.zip
- APK 大小：22,548,162 字节。
- APK SHA-256：96b9bf0b685c05b640398d502b0ef67dbe7cf9aff722a7e82ca491f00eea74d1
- 签名 SHA-256：f1b4fc6177f52213f22c19efa885caf6315ad6dd05cbb87c18a35af4c52670b7；与此前综合修复版一致。
- 包名 com.shengzimao.reader.mobile，最低 Android 8.0/API 26，targetSdk 36，compileSdk 37。
- R8 与资源压缩开启，非 debuggable，无 QA/全量审计入口。

正式 Release APK 已生成并通过签名、16 KiB 对齐和 ZIP CRC 校验；包名与原签名保持一致，非 Debug，不包含诊断入口。313 项单元测试通过，Release lint 0 错误/35 警告。最终 R8 独立包的四组真机回归通过，其中真实网络下载/解码 18 张抽样图，6 次生产阅读页恢复显示通过。没有覆盖安装正式版或修改正式书库。

## 本轮内容

修复逐图请求头和空格地址、Go 旧章节接口、S007 规则解封，以及包子 APP 二维码/其他作品图片误当正文；包含前轮未发布的目录请求头持久化、当前 G 接口、图片线路、缓存恢复、同章分页和错误状态修复。全量范围与失败明细见 reports/yck-all-sources-2026-10-27/REPORT.md。全量抽查不等于所有外站都已恢复，不按目录成功推断正文成功。

## 构建

JDK 21、Gradle 9.7.1、Android SDK API 37，工程目录 mobile-reader。标准依赖可从 Maven 获取；APK 所需的引擎 dex/资源已随源码提供。

```powershell
gradle :app:testDebugUnitTest :app:assembleRelease :app:lintRelease --max-workers=2 --console=plain
```

正式签名需使用持有者自己的 keystore.properties/密钥；密钥不分发。没有签名配置时可构建 Debug 验证包或自行签署输出，不能用不同签名覆盖已有正式版。

本机实际 Debug/单元构建使用临时审计 sourceSet init；正式/R8 构建不加载它。正式日志 .tools/yck-all-audit-20261027/notice-final-release-r8-build.log，验签/对齐/清单日志保存在同目录。前次并发分析出现 lint 内部 FIR 异常，最终源码固定后重新执行完整 lint 成功，没有关闭检查。

## 对应源码

源码归档包含 Android 主程序、QA 与单元测试、引擎适配源码/构建工具、许可、构建说明和本次逐源报告。独立保留 SoNovel 1.11.0 官方源码归档（提交 6026c5b6be4faa299b3c36f98d1f7328d9853793）及原引擎 JAR，供重建引擎时核对；具体步骤见 engine/README.md。

不包含发布密钥、keystore.properties、local.properties、.env、私人书库、.tools/缓存或手机导出的私人数据。未上传 GitHub，也未把正式包安装覆盖用户书库。
