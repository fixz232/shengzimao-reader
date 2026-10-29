# 漫画站点适配维护

## 使用

入口：**在线搜漫 → 漫画源管理 → 站点适配**。

第一次需要安装含适配引擎的应用版本。此后，已有引擎支持的搜索、目录、图片提取规则、请求头和公开线路可以通过 JSON 包更新，无需每次重装 APK。新增解密算法、图片格式或引擎 API 仍需更新应用。

1. 从 HTTPS 链接、本地文件或粘贴导入；也可选「内置修复包」。
2. 预览来源、版本、域名、源 ID 和线路，确认后安装。先验证受影响的现有源，再原子保存。
3. 原始源不被覆盖，名称、ID、启停状态、漫库和下载文件保留；规则在运行时叠加。
4. 在源管理点「检测全部源」或「重测未通过」。每完成一源立即显示并保存结果，实际下载和解码图片，有目录不等于可读。
5. 同 ID 包更新需要增加 `version`。支持检查更新、停用、移除和回退至上一版，重启后保留状态。

没有预设远端仓库、静默推送或包签名系统。只安装信任的规则。`updateUrl` 指定维护地址；未指定时，可使用 HTTPS 导入原链接检查更新。本地文件和内置包没有自动生成的公网地址。

## 本次兼容包

[`app/src/main/assets/manga-adapters/public-compatibility.json`](app/src/main/assets/manga-adapters/public-compatibility.json) 是适配包，不是完整漫源，不会新增站点或启用停用源。

- 再漫画：修正作品 ID 组合出空格、详情无目录的问题。
- 腾讯漫画：请求原站公开桌面章节页，提取 `DATA` 与受限 nonce 表达式，不执行整页脚本；原站 `canRead=false` 时停止并提示访问要求。
- 新漫画 1.0.0 图片封装由本轮原生引擎处理：仅剥离已确认格式的 80 字节头，再实际解码。这属于首次引擎更新，不是靠 JSON 增加新算法。

维护者可把包放到自己控制的 HTTPS 地址，设置 `updateUrl`，每次变更递增版本。本次没有代用户发布公网仓库。

## 协议 v1

```json
{
  "kind": "szm.manga-adapters",
  "schemaVersion": 1,
  "minEngineVersion": 1,
  "id": "my-site-fixes",
  "name": "我的站点修复",
  "version": 1,
  "updateUrl": "https://example.org/adapters.json",
  "adapters": [{
    "id": "chapter-fix",
    "hosts": ["example.org"],
    "sourceIds": ["optional-existing-source-id"],
    "imageHosts": ["cdn.example.org", "cdn2.example.org"],
    "resourceHosts": ["api.example.org"],
    "patch": {
      "reader": {"pages": ".chapter-pages img", "image": "@data-src"},
      "compatibilityNote": "修正章节图片提取"
    },
    "overrideReader": true,
    "imageHeaders": {"Referer": "{chapterUrl}"},
    "requestRoutes": [{"fromHost": "example.org", "pathPrefix": "/old-api/", "toBaseUrl": "https://api.example.org/v2/"}],
    "imageMirrors": [{"fromHost": "cdn.example.org", "pathPrefix": "/comic/", "toBaseUrl": "https://cdn2.example.org/public/"}]
  }]
}
```

`example.org` 是协议占位示例，不是可用站点。

| 字段 | 说明 |
| --- | --- |
| `id` / `version` | 同包 ID 不变，版本为递增正整数；同版本不同内容或降级被拒绝，旧版恢复用回退。 |
| `hosts` | 根据源 `baseUrl` 精确匹配域名，不接受通配符、端口和路径。 |
| `sourceIds` | 可选，进一步限定既有源 ID，避免改到同域其他规则。 |
| `imageHosts` / `resourceHosts` | 声明图片 / API 域名；线路两端必须在声明范围内。 |
| `patch` | 深度合并；允许 `headers`、`search`、`detail`、`reader`、`legacy`、`discover`、`legacyEncryptionBaseUrl`、`compatibilityNote`。`null` 删除可选字段；完整源验证失败则不安装。 |
| `overrideReader` | 用包内提取规则代替固定站点读图适配器时设为 `true`；搜索和目录补丁不需要它。 |
| `imageHeaders` | 仅声明的站点 / 图片域名生效；支持 `{chapterUrl}`、`{sourceUrl}`、`{imageUrl}`。 |
| `requestRoutes` | 替换匹配域名和路径前缀，保留剩余路径、查询和片段；目标不能自带查询或片段。 |
| `imageMirrors` | 增加已核验的候选图片线路，不随机猜域名。 |

CSS / JSON 规则见 [MANGA_SOURCES.md](MANGA_SOURCES.md)。`legacy` 沿用现有受限 Rhino 环境及请求、指令预算，不能访问 JVM、Android 或手机文件；不支持远端 Kotlin、DEX、动态库或任意 Java 导入。

直接声明的请求头不允许 Cookie / Authorization。也不要把凭据放入规则文本；包不是会话备份。跨域改线和跳转移除敏感请求头，HTTPS 导入不接受降级 HTTP。

多个包按安装顺序叠加，后安装字段优先；更新不改变顺序。建议每个站点由一个维护包负责，冲突时停用其中一个。单包最大 2 MiB、1–100 项规则，最多安装 32 包。单项最多各 12 条请求线路 / 图片线路。当前和上一版共用原子存储，合计最大 8 MiB；损坏文件保留并报错，不静默覆盖。

## 缓存、检测和迁移

- 适配指纹进入有效规则版本，旧检测会显示需复测；原始源不变，移除包恢复原规则。
- 安装包不删除下载文件。未完成缓存按新规则解析；完整下载仍匹配且文件存在时保留离线读取，显式刷新章节再获取新地址。
- 停用源在隔离副本中检测，不改变启停或正式漫库。首、中、尾章首末图片抽样不等于全站作品和章节通过。
- 适配包独立保存在漫库目录 `adapter-packs.json`；现有漫库备份不导出适配包。设备迁移需另存并重新导入适配 JSON。
- 维护记录区分桌面与 Android 实际解码。本轮未真机验证；范围与逐源结果见 [本轮报告](reports/site-adapters-2026-10-28/REPORT.md)。

## 原生引擎与复测

固定适配器仍在 `data/MangaSiteAdapters.kt`：`GmhSiteAdapter` 负责既有 Go 公开接口，`BaoziSiteAdapter` 负责包子公开页面、Referer、已知线路及提示图拒绝。队列、超时、真实解码、缓存隔离和过期重试继续由共享引擎负责。

新增原生适配器按具体站点和 URL 结构匹配，返回完整有序图片列表，明确拒绝循环、访问验证、提示图或超限。不得把部分页、其他作品当完整正文。使用原创 HTTP / 图片夹具验证请求链路，再分别记录本机、R8 和真机覆盖。

桌面复测示例（不连接手机，Pillow 仅位于测试工具，不进入 APK）：

```powershell
$env:JAVA_HOME = 'D:/java21'
& '.tools/gradle-9.7.1/bin/gradle.bat' -I '.tools/adapter-audit/run.init.gradle' :app:mangaAdapterAudit '-PauditCatalog=.tools/manga-live-audit/phone-sources-20261027.json' '-PauditOutput=.tools/adapter-audit/my-run' --offline --max-workers=2 --console=plain
```

把待测包放到输出目录 `adapter-packs/` 后运行。每源有时间、请求预算，保存错误阶段、HTTP 轨迹、实际图片 SHA-256 和尺寸。只获得 URL 不算通过。DNS、TLS、停服、地区限制、登录、验证码、下架、付费和限流均按实际情况展示，通用适配器不能保证消除这些外部限制。
