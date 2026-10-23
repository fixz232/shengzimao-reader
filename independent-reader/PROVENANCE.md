# 来源与边界

创建日期：2026-10-22。

## 新写代码

`app/`、`scripts/`、`test/` 在本工程独立编写，不从 `LevineHua/single-reader`、`chentieli/single-reader` 或旧版桌面应用复制实现。旧 JSON 的字段名仅用于数据互操作；不迁移旧书源规则和旧应用代码。

## 单独保留的第三方

- Electron、electron-updater、builder-util-runtime、Mozilla PDF.js、iconv-lite、JSZip、sax、xml2js：通过 npm 精确版本依赖，许可随依赖分发。
- SoNovel：`freeok/so-novel`，GNU AGPL v3，以其随附许可为准。新写 Java 适配器调用其核心 API，不复制旧适配器。构建时记录源码提交、工作区变更和资源 SHA-256，并随包附源码（排除 Git、构建缓存与 .env）。
- 番茄小说功能参考：`zhongbai2333/Tomato-Novel-Downloader`（HEAD `75f0dd327d7fd55ee646564a4da4d028139c591c`，MIT License）。本项目仅参考其公开搜索、详情、目录和下载流程，独立实现适配层；未复制私有 API、token、第三方地址池、验证码绕过或反爬逻辑。
- 应用图标：使用用户此前提供的猫图标，不取自 single-reader；暂存时记录哈希。

## 审计范围

构建明确排除旧版 `src/`、`dist/`、`.electron-vue/`、旧版配置、私有书库和书籍正文。对新源码进行本地旧文件整文件哈希比对只能排除逐字文件复制，不能证明所有代码来源或提供法律保证。原版致谢与待授权说明仍留在旧工程，不删除。
