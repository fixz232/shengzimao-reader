# 漫画源格式与使用说明

适用于当前 Android 源码（基于 1.10.1），格式版本 `schemaVersion: 1`。

## 从哪里开始

1. 底部进入 **在线搜漫 → 源管理**。
2. 用文件、HTTP/HTTPS 链接或文本导入 JSON；先查看源名称、站点，再确认导入。同 `id` 的配置会替换，已有漫库保留。单次最多 500 个源，文件不超过 8 MB。
3. **链接导入要求地址直接返回 JSON。** 返回 `base64 + zlib` 压缩体的分享链接会被自动解压；但若地址跳转到无关站点、返回网页或需要登录，导入会失败并提示原因，此时请改用「粘贴」。
4. 导入后建议点该源的 **「测试」**：它会依次跑搜索、详情、图片三段规则并指出失败在哪一段。`baseUrl` 与选择器只是结构合法才会导入成功，站点改版或占位域名需要通过测试才能发现。
5. 编辑已有源请用 **「编辑」**，进入独立编辑页；`id` 只读锁定，因为它与漫库条目和图片缓存关联。如需新建源，请用「添加源」。
6. 没有配置时可以「填入模板」或「导出模板」，按自己有权访问的站点结构修改。
7. 返回在线搜漫，选择单个源或「全部源」聚合搜索，看详情与目录，再加入漫库、阅读或离线缓存。

**应用不提供内置漫画站点源。`example.invalid` 是不可用的占位域名，示例不能直接搜漫画。** 只导入可信且有权使用的配置。

### 与阅读（Legado）格式的关系

导入时会自动识别阅读书源格式并尝试转换：`bookSourceUrl`、`ruleSearchUrl`、`ruleChapterList` 等字段会映射为本应用字段，`searchKey`/`searchPage` 转为 `{query}`/`{page}`，并支持 XPath 选择器（如 `//div[@class=item]`）。

旧式配置全部保存，支持 `@js:` / `<js>`、常见 `java.get/put/getString/getStringList`、受限 Jsoup facade、JSON 及图片数组规则。脚本解释器禁止 JVM / Android / 文件类访问，并设置解释器指令、栈深、输入输出及请求数量限制；这些限制不构成对任意恶意脚本的绝对安全承诺，只导入可信源。

未知加密版本、损坏密文、尚未适配的图片解码器及关键字段缺失会默认停用，原配置仍可编辑、导出，页面显示原因。当前开发源码已适配目录快照中全部 7 份图片解密脚本（6 类算法），并支持 H001–H006 配置解封，详见 [图片解密器适配记录](MANGA_DECODERS.md) 和 [加密规则验证](MANGA_PRIVATE_RULES.md)。源测试分别显示搜索、目录和首图结果及检测时间。97 项回归样本完整保留；不能把完整导入或配置启用等同于 97 个站点均可读取。

转换映射中，`ruleSearchNoteUrl` 是搜索结果的详情链接，`ruleChapterUrl` 是详情页的目录入口，`ruleContentUrl` 才是每个目录项的章节链接。旧式 `id.foo@li`、`class.foo@tag.img@data-src`、`tag.a.0`、备用布局及前缀 `-` 倒序会转换；未提供搜索分页参数时不会擅自添加。混合原生与旧式源文件会逐项处理。

用户反馈的 yckceo `/id/69.json` 分享集合解压后含 97 项旧式配置，其中部分规则为脚本或私有加密。这不是 97 个站点均可用的承诺，本版没有联网逐站验证。

1.8.1 当时核验的 `/id/70.json` 集合有 76 项，完整保留 76 项（15 项启用、61 项停用）；`/id/71.json` 集合有 97 项，完整保留 97 项（18 项启用、79 项停用），两份样本均没有跳过项。这是旧版本统计，当前开发源码已补充图片解密及 H 配置解封，兼容范围以适配记录为准。搜索下拉中的可用数量仅统计启用项，导入总数和停用原因在源管理中可见。同一源再次导入按 id 更新，不重复累加。未识别算法和缺失字段不通过强制启用变成可读内容。

旧式规则补充支持分层选择器的 `!0` / `!-1` 排除索引、单竖线备用规则和 `#正则#替换`。YYDS 的公开 Boodo 图片懒加载及包子漫画公开网页 / 官方备用图片线路有专门适配；它们只处理公开返回的数据，不执行远程网页脚本，也不绕过访问限制。

可声明的源通过声明式引擎，其他支持的源通过受限旧式规则引擎执行，不兼容 Mihon / Tachiyomi 的 APK 扩展。

## 容器与通用字段

支持一个源对象、源对象数组，或 `{"sources": [源对象, ...]}`。单次最多 500 个源，文件 / 链接响应及解压输出不超过 8 MB；同一文件不得重复 `id`。启用、编辑、移除、导出均在源管理内操作。

| 字段 | 含义 |
| --- | --- |
| `schemaVersion` | 格式版本，目前仅支持 `1`，省略时按 `1` |
| `id` | 建议显式提供；1–80 个字母、数字、点、下划线或连字符。作品和图片缓存按此关联，更新规则时保持稳定 |
| `name` | 1–80 字的显示名称 |
| `baseUrl` | 绝对 HTTP/HTTPS 站点地址；不接受内嵌账号密码 |
| `enabled` | 是否启用，默认 `true` |
| `headers` | 可选请求头对象，只支持 `User-Agent`、`Referer`、`Accept`、`Accept-Language` |
| `search` / `detail` / `reader` | 搜索、详情目录、章节图片规则，三者都必须提供 |

每组规则的 `type` 可为 `html`（默认）或 `json`。搜索使用 GET，`search.url` 的 `{query}` 替换为 UTF-8 URL 编码关键词，`{page}` 从 `1` 开始。搜索翻页由用户点「读取下一页」，不会无限翻页。返回的详情、章节及图片地址可为绝对或相对地址；相对地址按当前请求页面解析。站点重定向最多 4 次。

未配置 `Referer` 时使用 `baseUrl`。请求头会用于该源的页面及图片；不要填写口令或令牌，声明式源不支持 Cookie、Authorization、POST；旧式脚本兼容常见 GET / POST，但不携带 Cookie 或 Authorization，不提供登录、验证码或付费绕过。启用 HTTP 的源时，流量不加密。

旧式 `ruleSearchUrl` 的 `URL@key=value&other=value` 按表单 POST 发送，`@` 后的参数不属于 URL 路径；`@Header:{...}` 请求头声明单独处理。未指定表单类型时使用 `application/x-www-form-urlencoded; charset=UTF-8`，`searchKey` 按 UTF-8 编码，普通 GET 规则保持原语义。

## HTML / CSS 示例

选择器按源站的实际 HTML 填写；下面的类名也是示例。采用 jsoup CSS 选择器，尾部 `@属性名` 表示取属性，省略则取文本。`@href`、`@src` 等表示当前列表元素自身的属性。字段取第一个匹配元素，列表字段取全部匹配元素。`a||b` 按顺序选择第一个非空规则，列表支持 `a&&b` 合并去重；只在引号、括号和 XPath 谓词外拆分运算符。XPath 在当前列表项内相对读取。

```json
{
  "schemaVersion": 1,
  "id": "my-manga-source",
  "name": "我的漫画源",
  "baseUrl": "https://example.invalid",
  "headers": {"Referer": "https://example.invalid/"},
  "search": {
    "type": "html",
    "url": "/search?q={query}&page={page}",
    "items": ".manga-item",
    "title": ".title",
    "link": "a@href",
    "cover": "img@data-src",
    "author": ".author"
  },
  "detail": {
    "type": "html",
    "title": "h1",
    "cover": ".cover img@src",
    "intro": ".intro",
    "chapters": ".chapters a",
    "chapterTitle": "",
    "chapterUrl": "@href",
    "reverse": false
  },
  "reader": {
    "type": "html",
    "pages": ".reader img",
    "image": "@data-src"
  }
}
```

| 规则 | 必填字段 | 可选字段及默认值 |
| --- | --- | --- |
| `search` | `url`、`items`、`title`、`link` | `cover`、`author`；省略不提取 |
| `detail` | `chapters` | `title`、`author`、`cover`、`intro`；`tocUrl` 可指定详情中的目录入口链接选择器；`chapterTitle` 为空时取当前元素文本；`chapterUrl` 默认 `@href`；`reverse` 默认 `false` |
| `reader` | `pages` | `image` 默认 `@src`；该字段为空时对 HTML 图片依次尝试 `data-src`、`data-original`、`data-lazy-src`、`src`；`nextPage`、`maxPages` 见下 |

搜索列表每次最多读取 300 个结果；目录最多 30000 章，按章节 URL 去重；`reverse: true` 用于倒序目录。只有公开目录页不代表该站的图片也能访问。

对于单章分多网页的站点，设置例如 `"nextPage": "a.next@href", "maxPages": 5`。不配置 `nextPage` 时默认只请求 1 页；配置后默认最多 50 页，`maxPages` 可设 1–50。链接为空时正常结束；循环链接或达到页数上限但仍有下一页时会报错，避免把不完整章节标记为已缓存。图片按首次出现顺序去重。不要把下一章链接误配成下一页。

## JSON 路径示例

响应根支持 JSON 对象或数组。路径相对于当前对象；支持 `data.items`、`data.items[0].name`、可选 `$` 前缀及数组 `[*]` 投影，不支持递归查找、过滤器、表达式或完整 JSONPath。

假设搜索响应是 `{"data":{"items":[{"name":"示例作品","url":"/comic/1","cover":"/cover.jpg"}]}}`，详情为 `{"name":"示例作品","chapters":[{"title":"第一话","url":"/chapter/1"}]}`，章节为 `{"images":["https://example.invalid/1.jpg"]}`，对应配置：

```json
{
  "schemaVersion": 1,
  "id": "my-json-source",
  "name": "JSON 漫画源示例",
  "baseUrl": "https://example.invalid",
  "search": {
    "type": "json", "url": "/api/search?q={query}&page={page}",
    "items": "data.items", "title": "name", "link": "url", "cover": "cover"
  },
  "detail": {
    "type": "json", "title": "name", "chapters": "chapters",
    "chapterTitle": "title", "chapterUrl": "url"
  },
  "reader": {"type": "json", "pages": "images", "image": ""}
}
```

图片列表若为对象数组，例如 `{"images":[{"url":"/1.jpg"}]}`，将 `reader.image` 改成 `"url"`。详情或阅读接口可直接作为搜索结果、章节的 URL；不支持额外的脚本、解密或 API 地址转换。需要浏览器 JavaScript 生成的内容无法直接解析。

## 阅读、缓存和本地漫画

- 漫库可导入 CBZ、ZIP 或多张图片；压缩包图片按完整文件名自然顺序排列，多张单独图片合为一册。暂不支持 CBR / RAR。
- 单次最多 5000 个选中文件；每个压缩包最多 5000 张图片、10000 个条目，解压总量不超过 512 MB，单图不超过 24 MB。图片能否解码取决于设备系统。
- 在线每章最多 5000 张图片；页面响应不超过 8 MB，单图不超过 24 MB。图片缓存全局最多 3 路并发，任一图片完成立即领取下一张，章节依次处理；已保存图片会复用。网络错误或 HTTP 5xx 仅重试一次，4xx 不盲目重试。再次下载未完整缓存章节时重新解析图片地址，避免一直使用失效链接；失败提示包含章节与页号。
- 阅读支持条漫、横向翻页、日漫从右向左翻页、章节目录、页数与进度、双指缩放及双击缩放。长图拆块解码，避免一次加载整张长图；同一张长图的所有分块共享缩放状态，放大时不会错位。
- 漫库「缓存管理」可查看在线图片缓存占用、全部清理，或按容量上限淘汰最久未用的图片。移出在线漫画不会自动删除共享缓存，请在缓存管理中清理。
- 只有完整缓存的章节才标记「已缓存」。成功缓存的章节可离线读；删除或停用源后，未缓存的章节与图片无法读取。
- 「下载管理」统一显示小说和漫画，支持排队、进度、失败原因、取消、重试及打开阅读。多本串行；进程退出时不承诺后台继续，重启后未完成项显示中断，需手动重试。支持暂停 / 继续和验证断点；系统结束后由用户主动继续，不承诺无限后台运行。
- 移出本地漫画会删除应用内的导入副本，原始文件不动；在线漫画移出后保留共享图片缓存。卸载 / 清除应用数据会删除私有目录。
- 阅读 `.szmbak` 备份包含漫画目录、进度、页面书签、设置及去除凭据字段的源，主动下载 / 本地图片可选；默认不包含图片。临时缓存与下载队列不导出。仍应保留原始文件。

## 来源与权利

源规则只描述解析方式，不提供作品授权；漫画正文、封面、名称和商标的权利归相应权利人。请遵守来源网站条款并支持正版，不把公开可访问等同于可以再传播或商业使用。

搜索词、页面和图片请求会直接发送到所选源站及其链接指向的第三方，第三方可能记录 IP 与访问行为。旧式 JavaScript 在受限解释器中执行，可向源站请求数据，不读取手机文件、登录凭据或绕过访问控制。站点变更、源规则错误、网络故障和图片格式差异都可能导致失败，不保证任何源长期可用。

项目许可及免责见 [手机版 README](README.md#协议声明免责与致谢)、[项目声明](../NOTICE.md)；jsoup 许可见 [第三方说明](THIRD_PARTY_NOTICES.md)。
