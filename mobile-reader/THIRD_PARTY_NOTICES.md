# 第三方来源与许可说明

此文件补充许可与来源说明，不替代各组件原始许可。项目整体许可见 LICENSE；第三方组件保留各自的版权及许可义务。

| 组件或参考项目 | 来源 | 许可及使用说明 |
| --- | --- | --- |
| SoNovel | https://github.com/freeok/so-novel | AGPL-3.0，以随附许可为准；来自桌面版暂存的 JAR，Android 适配源码位于 engine/src，保留上游许可与声明 |
| Tomato-Novel-Downloader | https://github.com/zhongbai2333/Tomato-Novel-Downloader | MIT；参考提交 75f0dd327d7fd55ee646564a4da4d028139c591c 的公开接口和流程，Android 适配独立实现 |
| Rhino 1.7.15 | https://github.com/mozilla/rhino | MPL-2.0；解释执行书源脚本，不使用 V8 原生库 |
| AndroidX / Jetpack Compose | https://android.googlesource.com/platform/frameworks/support | Apache-2.0；按依赖所附声明为准 |
| Kotlin / kotlinx.coroutines | https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0；按依赖所附声明为准 |
| OkHttp / Okio | https://github.com/square/okhttp / https://github.com/square/okio | Apache-2.0；直接网络请求及引擎依赖 |
| Hutool / jsoup / SoNovel 间接依赖 | 上游 SoNovel 随附 JAR | 保留上游及各依赖原始许可；本表不宣称间接依赖均适用同一许可 |

上游 SoNovel 许可全文及免责声明分别保存在 notices/SONOVEL-LICENSE、notices/SONOVEL-DISCLAIMER.md。番茄参考项目 MIT 许可保留在 notices/TOMATO-MIT-LICENSE。未在本项目重新授权第三方内容。

发布源码时必须排除 keystore/、keystore.properties、local.properties、.tools/、.gradle/、build/、app/build/、私人书库和 .env 等本机或私有文件。发布 APK 时需同时提供对应版本源码及必要的构建说明。
