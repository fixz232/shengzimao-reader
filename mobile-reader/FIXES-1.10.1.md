# 1.10.1 源码修复记录

日期：2026-10-26。基于用户现有 1.10.0 源码；版本元数据为 1.10.1 / 18。源码修复阶段按要求暂不打包；随后经用户授权生成正式 APK，见 [构建与校验记录](BUILD-1.10.1.md)。

## 漫画源 934 搜索 404

- 来源：用户提供的 `https://www.yckceo.com/yiciyuan/tuyuan/json/id/934.json`，单个「直连韩漫网」旧式源。
- 已复现：旧逻辑把 `index.php@keyboard=...` 整体当作 URL，GET 返回 404；按源规则向 `index.php` 发送表单 POST 后，站点正常跳转到搜索结果并返回 200。
- 修复：分离 `URL@key=value` 的地址与请求体，按 UTF-8 表单发送；`@Header:` 保持独立，普通 GET、URL 脚本和请求头中的 `@` 不误拆。
- 转换器不再把表单 POST 源转成普通 GET 源；保留旧式规则和源自带 User-Agent，源 ID 沿用原逻辑。
- 真实网络验证通过应用 `MangaEngine.importUrl` 和 `MangaEngine.search` 执行：导入 1 个源，搜索「魔咒」返回 1 条匹配结果。使用应用原默认 User-Agent 的表单请求也返回 200。

## 同轮保留的 TLS 兼容修复

- 漫源链接导入遇到 TLS 协议错误时，自动进行一次 TLS 1.2 / HTTP/1.1 兼容重试。
- 保留系统证书、主机名校验及代理配置；证书失败不作为协议问题重试，不自动将 HTTPS 改成 HTTP。
- 持续失败给出中文提示，可直接切换到文件或粘贴导入。
- 已有注入故障的单元测试通过；尚未在先前截图中出错的另一台手机复测，不能由这些测试认定其网络问题已消失。

## 验证

- Windows / PowerShell；JDK 21；Gradle 9.7.1；源码验证阶段未生成或安装 APK，后续打包状态见上述构建记录。
- JUnit：167 项，失败 0、错误 0、跳过 0。
- 新增 4 项 POST 回归：请求地址与表单分离、编码与结果解析、请求头与页码表达式、普通 GET 及 `@` 不误识别。
- 真实网络探针：`MANGA_934_LIVE_SEARCH_OK imported=1 results=1 matchedKeyword=true legacyPost=true`。
- 实际命令（无 APK 打包任务）：

```powershell
$env:JAVA_HOME='D:\java21'
& '.tools/gradle-9.7.1/bin/gradle.bat' --offline --no-parallel --max-workers=4 '-Pkotlin.internal.collectFUSMetrics=false' --init-script '.tools/qa-934.init.gradle' :app:testDebugUnitTest :app:manga934Live
```

日志：`.tools/fix-934-source-tests.log`；手动网络探针仅位于测试源码，不进入正式安装包。没有安装或覆盖手机 App、改动正式书库、推送 GitHub。

此次仅验证搜索问题，没有据此声明该源全部目录、漫画图片或章节下载均已验证。手机当前已安装的 App 不会因本地源码修改而自动更新。
