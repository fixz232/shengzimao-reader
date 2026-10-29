# 来源与致谢

感谢以下开源项目的作者与贡献者，以及提供测试、反馈、猫图标和改进建议的用户。圣子猫阅读器由独立维护者维护，不是下列项目或平台的官方发行版。

## 在线检索、下载与内容处理

| 项目 | 在本项目中的作用 |
| --- | --- |
| [freeok/SoNovel](https://github.com/freeok/so-novel) | 聚合搜书、目录解析与导出所用在线引擎；保留其作者、GNU AGPL v3 许可与对应源码 |
| **[zhongbai2333/Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader)** | 番茄公开搜索、详情、目录和下载流程的参考；参考版本为 MIT，本项目独立实现适配层 |
| [ying-ck/fanqienovel-downloader](https://github.com/ying-ck/fanqienovel-downloader) | Windows 已验证网页字体的字符翻译数据，AGPL-3.0；与上一项 MIT 项目分别记录 |
| [weiyinfu/MaoZeDongAnthology](https://github.com/weiyinfu/MaoZeDongAnthology) | Windows 在用户请求时访问的《毛泽东选集》独立公开版本备用来源，不随安装包预置正文 |

参考来源并不意味着接入上游全部能力或未公开服务。固定提交、使用范围及许可原文入口见 [第三方声明](THIRD_PARTY_NOTICES.md)。

## 阅读产品思路与历史贡献

- [LevineHua/single-reader](https://github.com/LevineHua/single-reader)：感谢其单行阅读器设计、紧凑窗口、伪装显示和基本阅读交互的探索；早期派生工程保留原作者来源信息。
- [chentieli/single-reader（epub 分支）](https://github.com/chentieli/single-reader/tree/epub)：感谢其 EPUB 导入、章节导航、设置与稳定性相关工作；历史贡献署名继续保留。

当前 `independent-reader/` 未导入或打包上述项目的旧 Vue／Electron 实现，产品思路参考与历史代码派生需区分。旧派生代码截至 2026-10-22 的待授权记录和申请链接仍保留在 [历史来源说明](THIRD_PARTY_NOTICES.md#历史-single-reader-来源与待授权记录)，致谢不代替授权。

## 框架、库与基础工具

- Electron、Node.js：桌面运行时、窗口与系统集成。
- electron-builder、electron-updater、builder-util-runtime：安装包构建、版本检查、下载更新与校验。
- Mozilla PDF.js、iconv-lite、JSZip、sax-js、xml2js：文档文字提取、编码转换、EPUB／DOCX 和 XML 处理。
- Android、AndroidX／Jetpack Compose、Kotlin、kotlinx.coroutines、OkHttp、Okio：移动版 UI、语言、并发与网络能力。
- jsoup（Jonathan Hedley，MIT）：Android 1.3.0 自定义漫画源的 HTML/CSS 解析；许可原文随源码及 APK 保留。漫画规则由用户自行导入，不使用 Mihon / Tachiyomi APK 扩展代码。
- Rhino、OpenJDK、Maven、Hutool、jsoup 以及其他依赖：书源脚本、引擎运行与构建基础。

此处仅列出主要项目，不是完整依赖清单。详细许可入口见 [第三方声明](THIRD_PARTY_NOTICES.md) 和 [Android 第三方说明](mobile-reader/THIRD_PARTY_NOTICES.md)。

## 作品、图标与用户反馈

感谢创作作品的作者与其他权利人；请支持原作者与正版平台。感谢提供猫图标、报告问题、参与测试与提出建议的用户。

致谢只记录贡献、参考和来源，**不代表获得额外的代码、素材、内容或商标授权，不代表合作或第三方背书**。软件许可、作品权利、数据与使用风险分别见 [LICENSE](LICENSE)、[NOTICE.md](NOTICE.md) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
