# 独立版 0.5.2 验证记录

验证日期：2026-10-23。此记录描述维护者本次本地实测，不代表所有机器、网站或长期运行均可用。

## 环境

- Windows x64，系统版本 10.0.26200；PowerShell 7.6.5。
- 构建：Node.js 24.19.0、electron-builder 24.13.3、Electron 33.4.11。
- 测试驱动：Node.js 25.8.0；打包程序运行 Electron 33.4.11 内置运行时。
- 引擎：Temurin JDK 21.0.10；SoNovel 固定提交 `dcfc4a3f952eedd0036eb69fada01b935c06dc04`，构建前工作区干净。
- 所有界面测试串行执行，使用新建的隔离配置；不读取或修改私人书库。

## 变更和结果

| 验证 | 实测结果 |
| --- | --- |
| `npm test` | 114 / 114 通过，包含真实 Java 适配器合成站点链路 |
| `npm run test:online-ui` | 两轮各 26 项通过：零结果失败仍显示重试、点击后实际重搜、默认相关度排序、跨站合并、目录、TXT/EPUB 入库和增量追更 |
| `npm run test:about-ui` | 开发版与打包程序各 15 项通过：版本、声明、许可、来源入口、940/560 像素无横向溢出、滚动至末尾及在线声明入口 |
| `npm run test:tomato-ui` | 开发版与最终打包程序各 10 项通过：真实番茄搜索、详情、目录、页内标签、输入草稿及 560 像素布局 |
| `npm run test:packaged` | 最终打包程序 20 项通过：导入、阅读、Java 引擎、文档转换、声明记录、重启恢复进度和书签等 |
| ASAR 完整性 | 1052 个文件哈希全部通过；归档中的番茄模块与关于页源码逐字匹配当前文件 |
| 更新元数据 | `latest.yml` 的版本、安装包大小、SHA-512 与实际安装包一致 |
| 发布源码同步 | app、engine、scripts、test 的 71 个文件与待推送目录哈希一致，并核对包版本和说明文件 |

聚合显示修复针对可复现的缺陷：重试按钮原来放在结果标题中，失败且无结果或筛选后为空时入口消失；现移至搜索状态区域。单元测试覆盖无结果、有结果、筛选为空、失败批量项和运行中不显示重试，真实界面测试注入一次明确失败后点击重试，之后使用真实 Java 引擎完成搜索。

番茄详情另补一条带 id 的 JSON 脚本回归：动态正则保留换行匹配转义，JSON 在解析前不经过 HTML 文本清理，避免带引号简介或多行脚本解析失败。真实网络测试搜索「我不是戏神」，读取作者、简介、字数与完整目录，未下载整本正文。

## 安装包

- 文件：`release/圣子猫阅读器-独立版-0.5.2-安装包.exe`
- 大小：163440578 字节。
- SHA-256：`76d4beafae3f254e2667d7c876b26797c241574413f494869e6fccd448c5d6a5`
- 本次验证的是构建输出中的打包程序，未执行覆盖安装，也未在本次源码推送中创建 GitHub Release 或发布标签。

## 证据与复现

本地生成证据位于 `independent-reader/.verification/`，不上传 Git：

- `unit-0.5.2.log`
- 聚合：`online-ui-1792751948987`、`online-ui-1792752341063`
- 关于：结构复核 `about-ui-1792752240433`；打包复核 `about-ui-1792752523492`
- 关于页已目视检查的截图：`about-ui-1792752040864` 中桌面顶部、底部及 560 像素顶部。
- 番茄：开发版 `tomato-ui-1792752213006`；最终打包版 `tomato-ui-1792752552418`
- 打包：`packaged-1792752475954`；安装包校验：`artifact-0.5.2.json`

首次关于页截图运行在第四张截图发生 CDP `Page.captureScreenshot` 超时；已有截图可读，布局断言通过。随后使用 `SZM_SKIP_SCREENSHOTS=1` 完整运行开发版及打包版的 15 项结构检查，并查看已生成截图。关闭截图仅跳过取图，不跳过布局或内容断言。

```powershell
npm test
npm run test:online-ui
$env:SZM_SKIP_SCREENSHOTS = '1'
npm run test:about-ui
Remove-Item Env:SZM_SKIP_SCREENSHOTS
npm run test:tomato-ui
npm run build:installer
npm run test:packaged

# 对构建输出重跑关于页和真实番茄回归
$env:SZM_TEST_EXECUTABLE = Join-Path (Get-Location) 'release/win-unpacked/圣子猫阅读器·独立版.exe'
$env:SZM_SKIP_SCREENSHOTS = '1'
npm run test:about-ui
Remove-Item Env:SZM_SKIP_SCREENSHOTS
npm run test:tomato-ui
Remove-Item Env:SZM_TEST_EXECUTABLE
```

## 范围限制

- 第三方接口和内容可能变化，测试不证明所有作品可搜索、可下载、授权充分或长期可用。
- 没有下载整部番茄作品；真实网络回归范围是搜索、详情和目录，正文解析另用合成片段测试。
- 之前一次自动化检查出现排序控件空值，未稳定复现；本次两轮聚合流程均通过，保留失败时 DOM 与截图采集。不能把通过重跑视为已经定位了该偶发现象的根因；本次产品修复结论限于已复现的重试入口显示缺陷。
- 未重新覆盖所有 Windows 多屏、DPI、物理快捷键和长期运行场景；声明文本不构成对所有权利或具体使用行为的法律保证。
