# 第三方来源与许可说明

此文件补充许可与来源说明，不替代各组件原始许可。项目整体许可见 LICENSE；第三方组件保留各自的版权及许可义务。

| 组件或参考项目 | 来源 | 许可及使用说明 |
| --- | --- | --- |
| edge-tts（协议与请求令牌适配） | https://github.com/rany2/edge-tts/tree/bd44c1d1aaebdc1db7f47456ee12431b42ec7cac | LGPL-3.0；`reader/EdgeSpeechToken.kt` 从该提交 `src/edge_tts/drm.py` 的 v2 请求令牌算法移植，保留来源及许可。其余播放与缓存逻辑在现有 Android 听书模块内实现，未打包 Python 运行时。许可全文位于 notices/EDGE-TTS-LICENSE.txt，并随 APK assets 分发。在线音色由微软 Read Aloud 提供，不随工具许可转授服务或声线权利；该消费端接口的可用性可能改变。 |
| SoNovel | https://github.com/freeok/so-novel | AGPL-3.0，以随附许可为准；来自桌面版暂存的 JAR，Android 适配源码位于 engine/src，保留上游许可与声明 |
| Venera（流程参考） | https://github.com/venera-app/venera/tree/a0eba914f4c2a84ac1bc925adec2baabe920b9be | 参照该提交的导入、搜索、章节加载、图片请求配置、缓存和失败恢复分层；GPL-3.0。本轮独立编写 Kotlin/Compose 实现，没有复制或打包其 Dart、Rust、JavaScript 源码或资源，也没有接入其插件运行时 |
| Mihon（流程参考） | https://github.com/mihonapp/mihon/tree/e588e04359fe1058206a2cbac1b01018ad787359 | Apache-2.0；参照扩展协议分离、逐源搜索、章节/图片加载分层、图片队列/下载/失败状态和缓存校验。独立改进现有 Kotlin 引擎，没有复制或打包其扩展加载器、阅读器代码或资源；未宣称兼容其 APK 扩展 |
| Tomato-Novel-Downloader | https://github.com/zhongbai2333/Tomato-Novel-Downloader | MIT；参考提交 75f0dd327d7fd55ee646564a4da4d028139c591c 的公开接口和流程，Android 适配独立实现 |
| fanqienovel-downloader 字符数据 | https://github.com/ying-ck/fanqienovel-downloader/blob/4cbb46e9038f2d714407745898380cf02c629deb/src/charset.json | AGPL-3.0；仅用于识别的公开网页字体，移植自桌面同源数据，见 `data/TomatoFont.kt`；全文 `notices/fanqienovel-downloader-LICENSE.txt` 随源码及 APK assets 分发 |
| ASM 9.9（仅引擎构建工具） | https://asm.ow2.io/ | BSD-3-Clause；使用本机 Gradle 分发中的 ASM，替换上游不受 Android 支持的 executor 调用；不随 APK 作为依赖分发 |
| Rhino 1.7.15 | https://github.com/mozilla/rhino | MPL-2.0；解释执行小说书源及受限旧式漫画规则，不使用 V8 原生库；直接依赖原文保留于 notices/RHINO-LICENSE.txt 与 RHINO-NOTICE.txt，并随 APK assets 分发 |
| AndroidX / Jetpack Compose | https://android.googlesource.com/platform/frameworks/support | Apache-2.0；按依赖所附声明为准 |
| Kotlin / kotlinx.coroutines | https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0；按依赖所附声明为准 |
| OkHttp / Okio | https://github.com/square/okhttp / https://github.com/square/okio | Apache-2.0；直接网络请求及引擎依赖 |
| jsoup 1.17.2（漫画解析直接依赖） | https://jsoup.org/ / https://github.com/jhy/jsoup | MIT；Copyright (c) 2009-2023 Jonathan Hedley。原文从该版本 JAR 的 META-INF/LICENSE 保留至 `notices/jsoup-LICENSE.txt`，同时随 APK assets 分发 |
| Hutool / jsoup / SoNovel 间接依赖 | 上游 SoNovel 随附 JAR | 保留上游及各依赖原始许可；本表不宣称间接依赖均适用同一许可 |

上游 SoNovel 许可全文及免责声明分别保存在 notices/SONOVEL-LICENSE、notices/SONOVEL-DISCLAIMER.md。番茄参考项目 MIT 许可保留在 notices/TOMATO-MIT-LICENSE。未在本项目重新授权第三方内容。

漫画源是用户导入的声明式或受限旧式规则配置，不内置第三方漫画站点或内容；不使用 Mihon / Tachiyomi 的扩展代码。规则、漫画图片和封面保留各自权利边界，不能从工具的开源许可推导内容授权。

1.13.0 的分级图片队列、过期链接恢复、缓存上下文隔离、实际耗时预取、可读性抽样、单页调整、更新中心及下载恢复均在现有 Kotlin / Compose 工程内独立实现。Mihon / Venera 仍仅为上述固定提交的流程参考，本轮没有新增复制代码、资源或第三方运行时。站点适配器只使用已支持源的公开接口；需要登录或站点验证时保留失败状态，不以缓存命中或目录成功代替真实图片解码结果。

发布源码时必须排除 keystore/、keystore.properties、local.properties、.tools/、.gradle/、build/、app/build/、私人书库和 .env 等本机或私有文件。发布 APK 时需同时提供对应版本源码及必要的构建说明。
