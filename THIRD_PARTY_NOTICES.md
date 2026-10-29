# 第三方来源与许可说明

更新日期：2026-10-23。本文记录当前使用的组件、参考来源与历史待授权边界，不替代原始许可，也不宣称所有素材或内容已获统一授权。

## 适用范围

当前 Windows 应用位于 [independent-reader/](independent-reader/README.md)，Android 应用位于 [mobile-reader/](mobile-reader/README.md)。新写桌面代码标注 `AGPL-3.0-only`；根目录 [LICENSE](LICENSE)、各平台随附许可与组件自身许可应分别阅读。

**SoNovel 的 AGPL 许可不自动授予无关 single-reader 代码、图标或作品内容的权利。** 致谢、功能参考和公开仓库均不能代替授权。

## SoNovel 在线引擎

- 上游：[freeok/so-novel](https://github.com/freeok/so-novel)。
- 固定源码提交：`dcfc4a3f952eedd0036eb69fada01b935c06dc04`。
- 许可：随附的 GNU Affero General Public License, Version 3；不在此额外断言原作者授予了后续版本许可。
- 本地许可：[static/sonovel/LICENSE](static/sonovel/LICENSE)。
- 原始声明：[static/sonovel/DISCLAIMER.md](static/sonovel/DISCLAIMER.md)。

桌面 Java 适配器位于 `independent-reader/engine/`。构建指南将固定源码置于 `vendor/so-novel/`；打包时保留上游许可，并将该对应源码放入 `resources/source/so-novel/`，排除 Git 元数据和生成缓存。桌面适配与打包来源记录见 [PROVENANCE.md](independent-reader/PROVENANCE.md)。

Android 以 dex 加载 SoNovel，并使用 Rhino 桥适配脚本；构建与移植源码见 [mobile-reader/engine/README.md](mobile-reader/engine/README.md)。手机版分别保留 [SoNovel 许可](mobile-reader/notices/SONOVEL-LICENSE) 与 [原始声明](mobile-reader/notices/SONOVEL-DISCLAIMER.md)。

## 番茄小说相关来源

### Tomato-Novel-Downloader：MIT 流程参考

- 开源地址：**[zhongbai2333/Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader)**。
- 参考提交：`75f0dd327d7fd55ee646564a4da4d028139c591c`。
- 许可：[该提交的 MIT License](https://github.com/zhongbai2333/Tomato-Novel-Downloader/blob/75f0dd327d7fd55ee646564a4da4d028139c591c/LICENSE)。
- 版权声明：`Copyright (c) 2025 zhongbai2333`；[本仓库保留的 MIT 原文](mobile-reader/notices/TOMATO-MIT-LICENSE)。

Windows 与 Android 的番茄适配均由本项目独立实现，参考其公开搜索、详情、目录和下载流程。未接入该参考项目未公开的服务、私有令牌、第三方地址池、验证码绕过或反爬实现；其功能清单不能被直接当作本项目能力清单。

MIT 的授予范围及保留版权与许可声明要求以原文为准。本段不授予番茄网站、小说、封面、名称或商标的额外权利，也不表示获得平台或参考项目背书。

### fanqienovel-downloader：AGPL 字体数据

这是与上一项**不同的来源项目**，不能统一标注成 MIT。

- 上游：[ying-ck/fanqienovel-downloader](https://github.com/ying-ck/fanqienovel-downloader)。
- 数据来源：[`src/charset.json`](https://github.com/ying-ck/fanqienovel-downloader/blob/4cbb46e9038f2d714407745898380cf02c629deb/src/charset.json)。
- 固定提交：`4cbb46e9038f2d714407745898380cf02c629deb`。
- 许可：AGPL-3.0；[随附原文](independent-reader/app/notices/fanqienovel-downloader-LICENSE.txt)。
- 使用位置：`independent-reader/app/tomato-font.json`，仅保留已验证字体 `DNMrHsV173Pd4pgy` / `dc027189e0ba4cd` 对应的一组字符数据。
- 本项目的桌面解码逻辑在 `app/tomato-font.cjs` 独立实现，不复制该下载器实现。Android 自 1.1.2 起移植同源字符数据，见 `mobile-reader/app/src/main/java/com/shengzimao/reader/mobile/data/TomatoFont.kt`，保留 AGPL-3.0 来源及许可。

### MaoZeDongAnthology：用户请求时访问的备用内容来源

- 来源：[weiyinfu/MaoZeDongAnthology](https://github.com/weiyinfu/MaoZeDongAnthology)。
- 固定提交：`cf86018096980815a5b885dd9d7211da29fbf287`。
- 用途：Windows 对《毛泽东选集》精确核对书名、作者与分卷后的独立公开版本备用来源。
- 正文不随安装包预置；用户请求时从固定提交获取，注明实际来源和版本差异，不冒充番茄版，也不启用番茄追更。
- 连接目标：`raw.githubusercontent.com` 与相同提交文件的 `cdn.jsdelivr.net`。

此项是内容来源记录，**不是以 AGPL 或 MIT 重新许可这些作品**。仓库公开可见或本软件可以取得内容，不等于取得任意复制、再分发或商业使用作品的授权。

## 框架、库与运行时

以下为主要组件导航，**不是全部直接及间接依赖的穷尽清单**。具体版本与许可应以锁定依赖、上游文件和所用构建产物随附文本为准。

| 组件 | 用途与许可入口 |
| --- | --- |
| [Electron](https://github.com/electron/electron) | Windows 桌面运行时；保留自身及所带 Chromium、Node.js 等组件声明 |
| [electron-builder、electron-updater、builder-util-runtime](https://github.com/electron-userland/electron-builder) | 安装包、更新与校验；以对应包随附许可为准 |
| [Mozilla PDF.js](https://github.com/mozilla/pdf.js) | PDF 文字提取；以 `pdfjs-dist` 包随附许可及第三方声明为准 |
| [iconv-lite](https://github.com/ashtuchkin/iconv-lite) | 字符编码转换 |
| [JSZip](https://github.com/Stuk/jszip)、[sax-js](https://github.com/isaacs/sax-js)、[xml2js](https://github.com/Leonidas-from-XIV/node-xml2js) | ZIP 容器及 XML 读写 |
| [Rhino](https://github.com/mozilla/rhino) | Android 书源脚本桥；MPL-2.0，保留适用声明与源码义务 |
| AndroidX／Jetpack Compose、Kotlin、kotlinx.coroutines、OkHttp、Okio | Android UI、语言与网络；平台清单见下方文档 |
| [jsoup 1.17.2](https://jsoup.org/) | Android 1.3.0 漫画 HTML 解析直接依赖；MIT，原文见 `mobile-reader/notices/jsoup-LICENSE.txt` 并随 APK 分发 |
| OpenJDK、Maven 及 Java 间接依赖 | 引擎构建与运行；按各自随附文本保留许可，Java 运行时的 `legal/` 不应移除 |

Windows npm 精确依赖以 [package-lock.json](independent-reader/package-lock.json) 为准；Java 依赖以固定 SoNovel 源码的 `pom.xml` 为准；Android 详见 [THIRD_PARTY_NOTICES.md](mobile-reader/THIRD_PARTY_NOTICES.md) 与工程构建配置。分发运行时或依赖时，应同时保留其实际附带的版权、许可及第三方通知。

## 图标、书籍与其他素材

应用猫图标由用户提供，桌面构建记录其哈希；该记录不构成向公众授予额外素材权利的证明。另行使用和再分发前，请确认适用权利范围。

小说正文、封面、网站内容、名称和商标归各自权利人，未因本项目开源而统一转为 AGPL 或 MIT。示例、截图和备份也可能包含个人信息或作品内容，不应作为“开源即任意传播”的依据。

## 历史 single-reader 来源与待授权记录

以下记录针对**早期基于 single-reader 的桌面派生工程**，不是对当前独立工程来源的改写；保留这些记录不表示授权问题已经解决。

- 原项目：[LevineHua/single-reader](https://github.com/LevineHua/single-reader)，历史项目元数据署名为 LevineHua。
- EPUB 相关来源：[chentieli/single-reader 的 epub 分支](https://github.com/chentieli/single-reader/tree/epub)，历史 README 保留了 chentieli 的贡献署名。
- 截至 **2026-10-22 的历史核查**，未找到这些旧来源明确的开源许可证。
- 历史授权申请：[LevineHua/single-reader#8](https://github.com/LevineHua/single-reader/issues/8)。本文没有把该申请视为已获批准，也没有重新判断其当前状态。

旧派生代码的修改、公开分发或重新许可仍需确认受影响权利人的授权；在确认前，相关旧桌面代码的公开上传保持待授权状态。已有 AGPL 文本、元数据和致谢不能用来证明其权利人已授权。

当前 `independent-reader/` 不导入或打包旧 Vue／Electron 实现，仅参考单行阅读、EPUB 等产品思路，具体边界见 [PROVENANCE.md](independent-reader/PROVENANCE.md)。整文件哈希核对不能证明所有来源合法，也不构成法律意义上的洁净室认证。

## 再分发检查范围

再分发前应核对所包含的具体组件与素材，保留适用署名、许可、修改和来源说明，并按适用许可证提供对应源码与必要构建信息。不得将发布密钥、账号令牌、个人书库或未确认授权的旧代码混入公开源码或安装包。

本文记录已知来源与历史授权状态，不构成全部再分发权利已清除的保证。项目使用边界、数据说明与免责见 [NOTICE.md](NOTICE.md)。
