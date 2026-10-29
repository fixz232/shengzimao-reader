# 来源与边界

创建日期：2026-10-22。

说明更新：2026-10-23。项目级许可与使用边界见 [NOTICE.md](../NOTICE.md)，组件、参考流程及历史待授权记录见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。本文件记录实现来源，不构成全部代码或素材权利已清除的保证。

## 新写代码

`app/`、`scripts/`、`test/` 在本工程独立编写，不从 `LevineHua/single-reader`、`chentieli/single-reader` 或旧版桌面应用复制实现。旧 JSON 的字段名仅用于数据互操作；不迁移旧书源规则和旧应用代码。

## 单独保留的第三方

- Electron、electron-updater、builder-util-runtime、Mozilla PDF.js、iconv-lite、JSZip、sax、xml2js：通过 npm 精确版本依赖，许可随依赖分发。
- SoNovel：`freeok/so-novel`，GNU AGPL v3，以其随附许可为准。新写 Java 适配器调用其核心 API，不复制旧适配器。构建时记录源码提交、工作区变更和资源 SHA-256，并随包附源码（排除 Git、构建缓存与 .env）。
- 番茄小说功能参考：[zhongbai2333/Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader)（参考提交 `75f0dd327d7fd55ee646564a4da4d028139c591c`，[MIT License](https://github.com/zhongbai2333/Tomato-Novel-Downloader/blob/75f0dd327d7fd55ee646564a4da4d028139c591c/LICENSE)）。本项目仅参考其公开搜索、详情、目录和下载流程，独立实现适配层；未复制私有 API、token、第三方地址池、验证码绕过或反爬逻辑。此来源不包含下文单独记录的 AGPL 字体数据。
- 应用图标：使用用户此前提供的猫图标，不取自 single-reader；暂存时记录哈希。

## 审计范围

构建明确排除旧版 `src/`、`dist/`、`.electron-vue/`、旧版配置、私有书库和书籍正文。对新源码进行本地旧文件整文件哈希比对只能排除逐字文件复制，不能证明所有代码来源或提供法律保证。原版致谢与待授权说明仍留在旧工程，不删除。

## 0.5.4 下载来源补充

- 网页字体翻译数据来自 `ying-ck/fanqienovel-downloader` 的 `src/charset.json`，提交 `4cbb46e9038f2d714407745898380cf02c629deb`，AGPL-3.0；仅保留与公开字体 `DNMrHsV173Pd4pgy` / `dc027189e0ba4cd` 对应的一组字符数据，不复制下载器实现。来源和字体哈希见 `app/tomato-font.json`。此数据随本项目的 AGPL 许可分发；原许可全文见 `app/notices/fanqienovel-downloader-LICENSE.txt`。
- 公开文集备用来源为 `weiyinfu/MaoZeDongAnthology`，目录与正文固定到提交 `cf86018096980815a5b885dd9d7211da29fbf287`。连接 `raw.githubusercontent.com` 及同一提交文件的 `cdn.jsdelivr.net`。作品正文由用户请求时取得，不随安装包预置；作品权利不因本软件开源而改变。备用版本与番茄排版不同，不复制番茄版权页，不请求被锁定的番茄正文。
