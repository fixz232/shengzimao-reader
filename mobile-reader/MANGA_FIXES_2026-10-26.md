# 漫源交互与请求兼容修复记录

日期：2026-10-26。当前源码版本：1.10.1 / code 18。本轮更新独立测试包，未发布正式 APK、未推送仓库。

## 已修复

- **搜索范围长按失效**：原来的外层长按与内层 `DropdownMenuItem` 点击处理嵌套，内部控件消费触摸事件。现改为单一 `combinedClickable` 行：单击切源，长按进入对应源详情。右侧另有明确的详情箭头和可访问名称。
- **源详情异步状态串页**：打开另一源详情时取消上一浏览任务；返回结果和失败处理前检查协程是否仍有效，避免已取消请求回写新页面。
- **旧式请求声明**：兼容 JSON POST、动态 URL 返回的 `@Header`、旧式 `{Cookie=articlevisited=1}` 映射、GBK 搜索词以及 `{,_psearchPage}` 可选分页。脚本里的字符串、正则和注释不会被当成独立请求头声明；JSON/GraphQL 正文不参与 URL 分页替换。
- **旧式脚本 POST**：补充受限 `java.post(...).body()` 文本响应接口，并保留脚本时间、指令、请求次数与正文大小限制。未开放 JVM、文件系统或任意 Java 类。
- **请求头兼容及隔离**：统一导入和实际请求的白名单，支持 `region`、`webp`、`Cookie`、`Origin` 等声明。拒绝 Host、连接控制头和非法控制字符。源全局 Cookie 仅用于该源同源请求；跨域、协议或端口变化的重定向不携带 Cookie，后续跳回也不会重新加上。
- **包子空目录说明**：原站明确返回空目录/屏蔽提示时展示原站原因，而不是统一报规则错误。没有绕过站点屏蔽或补造章节。

## 验证结果

| 验证 | 结果 |
| --- | --- |
| 全量单元测试（开启本地目录快照附加检查） | **209 项，0 失败、0 错误、0 跳过** |
| Release lint | **0 Error、0 Fatal；30 条 Warning** |
| 622 份配置静态转换检查 | 618 份可保留/解析，4 份地址无效；25 份图片解码配置均被识别，且保持原始启停意图 |
| Android 原生旧式请求回归 | Cookie 映射、GBK、JSON POST、动态及后缀请求头、`java.post().body()` 均通过 |
| 搜索范围真机触摸回归 | 三个源含长名称；长按进入正确详情且不切源，单击切源，箭头入口、系统返回、工具栏返回及全部源选项均通过 |
| 真机完整合成站点回归 | 在线小说/漫画阅读、下载与断点复用、离线缓存、CBZ 导入、EPUB、备份恢复、导航、左右/从右向左翻页、跨章节、阅读位置恢复及大书分页均通过 |
| 界面检查 | 已查看下拉菜单和源详情真机截图；长名称最多两行，详情按钮独立可点 |

真机为当前连接的 Android 16 / API 36 手机。测试使用独立包 `com.shengzimao.reader.mobile.verification`、独立临时书库和原创合成内容；正式包经系统信息确认仍为 1.10.0 / code 17。本轮没有覆盖正式版或读取其书库。

完整目录附加测试原来仍断言图片解码源启用数为 24；核对发现 25 份原配置均显式启用，现有 H 规则适配也已覆盖此前受限的一份。已改为 25 并逐源核对原始启停状态与实际解码器，未为通过统计而修改源配置或自动启用用户保存的源。

## 证据

- `app/src/qa/java/com/shengzimao/reader/mobile/MangaSourcePickerSmoke.kt`：实际页面的触摸与 Android 请求兼容回归。
- `app/src/test/java/com/shengzimao/reader/mobile/LegacySearchPostTest.kt`、`MangaRequestCompatibilityTest.kt`：URL、正文、头部、重定向与边界回归。
- `reports/manga-source-fix-2026-10-26/verification.json`：验证摘要、日志位置及源码哈希。
- 本机日志：`.tools/manga-source-fix-unit-and-apk.log`、`.tools/manga-source-fix-final-lint.log`、`.tools/source-rules-device.log`、`.tools/source-picker-device-final.log`、`.tools/manga-source-fix-mobile-regression.log`。
- 本机界面截图：`.tools/source-picker-menu.png`、`.tools/source-picker-details.png`。

## 验证边界

本轮修复的是已确认的程序交互和请求兼容缺口。请求兼容真机回归及完整阅读回归使用固定合成响应/本地 HTTP 服务，**不能等同于所有第三方站点在线可读**。未在修改后重新逐站联网检测全部 622 份配置；此前逐站实测仍见 `MANGA_LIVE_AUDIT_2026-10-26.md`，其基线未被覆盖。

原站屏蔽、失效地址、缺失规则、DNS/TLS 故障、验证码和访问限制不能由这些改动保证消失。仍需分别核验具体源与作品；本记录不作“绝无 bug”或“全部漫源可读”的承诺。未更改证书校验、登录流程或手机安全设置。
