# Android 1.6.1 本地构建交付记录

日期：2026-10-24。按用户要求暂停测试，本轮仅执行源码修正、编译、构建与 APK 静态核对。

## 本轮改动

- 聚合搜书：固定 worker 持续领取下一章，不再等待目录队头才补任务；按章暂存、按目录合并、及时释放章节正文。
- 聚合下载使用设置中的 1–8 路并发（默认 4），服从书源更低并发上限与原请求间隔；共享连接池，每个 worker 重用配置及解析器。
- 下载队列的进度按时间节流；保留番茄已有的并发实现，修正其 UI 更新位置。
- 漫画源：修正 ruleSearchNoteUrl、ruleChapterUrl、ruleContentUrl 的映射；转换旧式 id/class/tag 选择器链、备用规则、倒序目录与图片属性，XPath 在当前列表项内读取。
- 漫画下载：3 个 worker 持续补位，网络/5xx 退避重试一次；固定条带锁避免同图临时文件竞争。未完整缓存的章节重新读取图片地址，完整下载后才标记缓存。
- 解码：支持 base64 + zlib/gzip；文件、网络响应及解压结果均设 8 MB 上限，最多 500 项；混合原生与旧式源逐项处理。
- 依赖关键脚本、加密、解码器及不支持分页规则的源明确跳过，不删除关键逻辑后冒充可用源。

## 来源核对

用户提供的 `https://www.yckceo.com/yiciyuan/tuyuans/json/id/69.json` 返回 base64(zlib(JSON))；原始响应 158,697 字节，解压后为 97 项旧式配置。只读取和分析配置，没有执行源脚本，没有逐站验证在线状态。

原始响应 SHA-256：`63c7dfc880fa798a4f3d30f62e4122393f8abba1d1d67f642de0bb7ec88a2869`。

## 构建与环境

Windows / PowerShell、Temurin JDK 21.0.10、Gradle 9.7.1、AGP 9.4.1、compileSdk 37、build-tools 36.0.0。

Debug Kotlin 编译、引擎 Java 编译与 dex 转换、Release Kotlin/Java 编译、R8、资源压缩及 assembleRelease 均成功。最后一次引擎源码小修后单独重编 wrapper.dex，再生成 Release APK。Release 构建输出 `BUILD SUCCESSFUL in 1m 52s`。

```powershell
& '.\.tools\gradle-9.7.1\bin\gradle.bat' :app:compileDebugKotlin --console=plain
& '.\engine\build-engine.ps1'
& '.\.tools\gradle-9.7.1\bin\gradle.bat' :app:assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease --console=plain
```

Release lint 已显式排除；本轮没有运行单元测试、instrumentation、真机安装或 UI 测试。

## APK 静态核对

| 项目 | 结果 |
| --- | --- |
| 文件 | `dist/圣子猫阅读-手机版-1.6.1.apk` |
| 包名 | `com.shengzimao.reader.mobile` |
| 版本 | versionName 1.6.1 / versionCode 11 |
| 最低 / 目标 API | 26 / 36 |
| 大小 | 20,748,190 字节 |
| APK SHA-256 | `c4dc94f45886aeb2894f80a77cc37aff08406079a6529e6b215ac89281aa4c06` |
| 发布证书 SHA-256 | `f1b4fc6177f52213f22c19efa885caf6315ad6dd05cbb87c18a35af4c52670b7` |
| 签名核对 | apksigner verify 通过，与构建前保留的 1.6.0 正式包相同 |
| 资产核对 | APK 内 15 个 engine/notices 资产与源码逐字节 SHA-256 相同 |

最终入口源码 SHA-256：`b7096f7912de89db7ce1589f862834b8bc1f507a24652eb2be35988318f59e00`。
最终 wrapper.dex SHA-256：`508fb46367805479b6f2d5f31e4253348c2d9c3121209a3477ca39fb38dbf76b`。
完整资产哈希保存在本地 `.tools/release-1.6.1-static-check.json`。

## 未验证与使用边界

- 没有实测整本小说/漫画的耗时，不能承诺固定提速倍数；此前打开书籍退出的问题也未做本轮真机复测。
- 没有验证集合中的 97 个站点；声明式转换成功不等于源站可用，脚本或加密项会跳过。
- 没有安装、卸载、清除应用或操作手机正式书库；没有推送 GitHub 或发布 Release。
- 安装后应重新导入用户提供的源链接，使旧版错误转换的配置得到更新；未完整缓存章节会重新读取图片地址。
- 仅处理公开且用户有权访问的内容，不绕过登录、付费或验证；队列不承诺进程退出后后台继续。
- 漫库、漫画源、图片缓存及下载记录不包含在现有小说 `.szmbak` 备份内。源协议及限制见 [MANGA_SOURCES.md](MANGA_SOURCES.md)。
