# 独立版 0.5.3 下载修复验证

验证日期：2026-10-23（Asia/Shanghai）。

## 结论与范围

【事实】修复本地下载流程中的详情失败连带阻断目录、正文容器误匹配、下载函数提前返回、入库错误误报成功及临时文件残留。公开明文样本的 TXT / EPUB 下载通过真实书库 worker 入库，重载后书名、作者和章节目录正确。

【事实】用户截图中的《毛泽东选集（第二卷）》，书籍 ID `7293805084226358313`，搜索可用，目录返回 42 章。实测 `/page/7293805084226358313` 和首章 `/reader/7327864897750961162` 当前均返回 HTTP 404。修复版保留搜索信息、展示目录，并在正文失败提示旁提供「搜其他书源」，点击后实际启动聚合同名搜索。

本次没有从番茄下载该书整本正文，不能声明该书已经可以从番茄直接下载。换源入口也不保证其他站点一定有匹配作品；实际可用性取决于目标书源。

## 环境

- Windows 11 专业版 x64，版本 `10.0.26200`；PowerShell `7.6.5`。
- 测试驱动：Node.js `25.8.0`。
- 构建：Node.js `24.19.0`、electron-builder `24.13.3`、Electron `33.4.11`。
- Java 适配器：现有 JDK 21、SoNovel 源码；构建前上游工作区干净。
- 所有下载、书库和界面检查使用临时或 `.verification/` 下的隔离配置，不读取私人书库。

## 修复点

- 详情页 404 时继续请求公开目录，保留搜索结果的作者、简介等信息；其他网络错误仍报告失败，不以旧详情冒充新结果。
- 正文选择器按完整 class 匹配，避开 `muye-reader-content-16` 字号容器，平衡嵌套元素并复用现有 HTML 文字解析器保留各段。
- 下载等待抓取及导入完成后返回，前端显示实际结果。仅标题、空正文、不可独立保存的专用字体编码和受限章节不会作为完整作品入库。
- 入库元数据带入真实书名，避免 TXT 的临时 UUID 被用作书库标题；保留作者与在线章节目录。
- 取消信号传给导入 worker；取消后不继续落库；文件写入、导入异常均清理任务临时文件。
- 入库失败保留实际错误消息，重复导入使用单独的跳过提示。
- 404 / 字体编码失败显示换源入口，搜索新书时清除旧目录筛选。

## 验证结果

| 检查 | 实测结果 |
| --- | --- |
| `node --test test/*.test.cjs` | 125 / 125 通过 |
| 新增下载回归 | 10 项，包括 TXT / EPUB 真实 worker 入库、持久化重载、404 容错、503 不吞错、锁章、空正文、字体编码、入库失败清理、取消晚到响应 |
| 番茄 UI 开发版阶段检查 | 17 项通过 |
| 最终打包程序番茄 UI | 17 项通过，真实搜索、截图书籍 404、42 章目录、换源及 560px 无横向溢出 |
| 最终打包程序通用回归 | 20 项通过，包含导入、阅读、Java 引擎、转换及重启保留进度书签 |
| ASAR | 1052 个文件完整性校验通过 |
| 应用源码与随包对应源码 | app / engine 的 32 个文件与归档及随包源码一致 |
| 安装包元数据 | 版本、大小与 SHA-512 均与 `latest.yml` 一致 |
| 最终桌面截图 | 最终 0.5.3 打包程序，错误说明、换源按钮及 42 章目录可见；已目视检查，未捕获异常为 0 |

截图完整回归的一次运行在窄窗口的 `Page.captureScreenshot` 超时，其他结构断言已通过。随后用 `SZM_SKIP_SCREENSHOTS=1` 完整执行全部 17 项，并另行启动最终打包程序保存单张桌面截图。跳过截图不跳过布局、行为或错误检查。单张截图脚本首次启动时读取阅读窗口过早，修正等待页面就绪后成功。

## 安装包

- 路径：`release/圣子猫阅读器-独立版-0.5.3-安装包.exe`
- 大小：`163444326` 字节。
- SHA-256：`239a0f9fb87d3f3754723355a9ca0ad4cb3a97271a5b5f1e143c09de3bac12e7`
- 本次运行的是构建输出中的打包程序；没有覆盖安装用户当前版本，没有推送源码或发布 GitHub Release。

## 证据

以下路径相对 `independent-reader/.verification/`：

- `unit-0.5.3.log`：125 项回归。
- `build-0.5.3.log`：构建和 ASAR 检查。
- `tomato-ui-1792755168741/report.json`：开发版阶段 17 项。
- `tomato-ui-1792755479597/report.json`：最终打包版 17 项。
- `packaged-1792755487293/report.json`：最终打包版 20 项。
- `download-visual-0.5.3/download-desktop.png`：最终桌面截图。
- `download-visual-0.5.3/report.json`：最终截图状态。
- `release-integrity-0.5.3.json`、`artifact-0.5.3.json`：源码、安装包及更新元数据校验。

## 复现

```powershell
node --test test/*.test.cjs
$env:SZM_SKIP_SCREENSHOTS = '1'
node scripts/tomato-ui-smoke.cjs
$env:SZM_TEST_EXECUTABLE = Join-Path (Get-Location) 'release/win-unpacked/圣子猫阅读器·独立版.exe'
node scripts/tomato-ui-smoke.cjs
node scripts/packaged-smoke.cjs
node .verification/verify-release.cjs
```

构建请使用 Node.js 24 LTS 运行 `scripts/build.cjs`。本次不证明所有番茄作品或其他书源长期可下载，也未验证覆盖安装。
