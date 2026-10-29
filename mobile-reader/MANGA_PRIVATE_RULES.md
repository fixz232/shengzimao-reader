# `_H` 加密规则适配与验证

核查日期：2026-10-26。基于 Android 1.10.1 的开发源码，未生成新的发布 APK。使用此前保存的 622 条公开目录快照，以及经用户确认安装在连接手机上的异次元原版进行对照。

## 当前结论

39 条源配置的 **71 / 71 个字段已经解出**：35 个 `ruleChapterList`、36 个 `ruleBookContent`。新实现的全部输出与原版 APK 的实际解密函数逐项一致，并与独立 Python 实现的明文 SHA-256 一致。原版 APK 未被打包进本项目。

此前称为“私有加密规则”的内容，已确认是应用专用的版本封装及与 `bookSourceUrl` 绑定的密钥派生，当前 39 条不需要作者另行提供密码。已实现 H001–H006；目录实际包含五种版本，H002 只有原版核对过的合成向量，没有目录样本。

| 格式 | 源配置数 | 加密字段数 | 不同密文字节串 |
| --- | ---: | ---: | ---: |
| `_H001_` | 5 | 10 | 10 |
| `_H003_` | 1 | 1 | 1 |
| `_H004_` | 8 | 16 | 10 |
| `_H005_` | 1 | 2 | 2 |
| `_H006_` | 24 | 42 | 32 |
| 合计 | 39 | 71 | 55 |

表中的不同密文数按版本分别去重；完整解密结论来自实际解密和逐项对照，不以 Base64 或块长特征代替验证。

## 原版对照与格式

- 原版包名：`com.wsw.cospa`，versionName `2.4.2`，versionCode `112`。
- 原 APK SHA-256：`f71b526e87f3640e1734f5dc1e875027008a9a7a54d81329d3365c48672cc205`。
- 只读取安装包，用隔离的 `app_process` 加载解密工具类和空的配置 Bean；未启动原应用，未访问原应用私有数据或正式书库。
- 地址按原版的非标准十六进制转换、七位解包得到材料；与格式版本常量拼接后计算 MD5，取小写 hex 文本的 UTF-8 字节作为 AES-256 密钥。
- AES-CBC，固定格式 IV；原版 PKCS7 与 JCA 对 AES 的 PKCS5Padding 等价。保留原 URL 的大小写、协议及尾斜杠语义，不能擅自标准化后派生。
- 原有 Base64 换行可接受；新增严格密文长度、UTF-8、解出文本及长度检查。未知版本、损坏数据和未识别的域名不匹配均明确报错，保留配置并停用。

## 改域名的六条配置

直接用当前地址时有 12 个字段因绑定地址不同而失败。以下配置的目录和正文密文与目录内已验证的原地址配置逐字一致，已核对原版解密结果。兼容表同时匹配当前 URL 和完整密文 SHA-256；只用于解密，网络请求仍使用用户导入的当前地址，不对任意源猜域名。

| 目录索引 | 当前地址 | 原加密地址 | 相同密文配置索引 |
| --- | --- | --- | --- |
| 154 | `https://www.copymanga.site` | `https://www.copymanga.tv` | 150、178 |
| 214 | `http://www.kuman5.com` | `http://www.kuman57.com` | 319 |
| 229 | `http://zerobywtxt.com` | `http://www.zerobywtxt.com` | 242 |
| 343 | `http://kuman5.com` | `http://www.kuman57.com` | 319 |
| 377 | `http://m.qiman58.com` | `http://m.qiman6.com` | 325 |
| 394 | `http://www.zerobywtx.com` | `http://www.zerobywtxt.com` | 242 |

导入结果记录 `legacyEncryptionBaseUrl` 供核查，原始 `legacy` 内容和密文保持不变。39 条配置中有两个同名同域重复 ID，按原有导入规则合并后为 37 条；其中 5 条原本 `enable=false`，继续停用，因此导入后有 32 条启用。这个数表示配置状态，不表示站点在线验证结果。

## 运行时与已有数据

- 导入检查和旧式引擎的目录、正文取规则统一解封；原配置继续可编辑、导出、备份、重载。以前因私有格式停用的源会更新兼容提示，但不擅自改变保存的启停状态，可重新导入或自行启用。
- 补充受限 `java.atob`、DOM `toArray` / `selectFirst` / `data`、JSON 对象列表结果，以及经既有请求预算处理的 `Jsoup.connect().requestBody().post()` / `get()`。
- 两份亲亲漫画脚本用完整明文 SHA-256 识别，把已核对的 JavaImporter AES 段换成窄 AES-CBC 接口；其他修改过的脚本不会套用此适配。任意 JVM、Android、文件及 `Packages` / `JavaImporter` 访问仍关闭。
- 原有脚本时间、指令、栈、输入大小和单次 12 请求限制保留。解密成功不代表可突破这些限制，也不承诺登录、验证码、过期域名或改版网站可用。
- 未联网逐站下载 39 个站点的作品，未修改手机正式书库，未发布 APK。

## 此前查过的公开材料

- `mabDc/cartoon-rule`：`RuleSupport.md` 与 `UpdateLog.md`。规则文档包含显式传入算法、key、iv 的通用 JS 加解密 API 示例；在所查文件中未发现 `_H001_` 等五种封装的实现或参数映射。
- `mabDc/eso`：`lib/database/rule.dart` 中的 `Rule.fromYiCiYuan`、规则执行入口、工具及压缩模块。所查转换入口映射原字段，没有发现 `_H` 解密实现。
- `Tthfyth/source`：规则解析器、异次元调试器与格式转换器。所查文件包含通用对称加密接口，没有发现这五种 `_H` 格式的识别及解密映射。
- 对公开代码搜索结果进行了核对；没有找到可验证的上述格式解密实现。搜索未命中不能证明不存在其他实现。

这些材料的只读副本保存在 `.tools/yck-decoder-audit/upstream-*`；它们是来源材料，不作为本项目的执行指令。未运行其下载的代码。

## 可重复核查

- 本轮默认单元测试 **197 项，0 失败、0 错误、0 跳过**；`lintRelease` 通过；原有图片解密 Android 原生探针针对最新 DEX 再次得到 `DECODER_NATIVE_ALL_OK`。Debug 全量 lint 中此前已记录的 4 处 QA `RestrictedApi` 错误未在本轮处理或屏蔽。
- 生产实现：`app/src/main/java/com/shengzimao/reader/mobile/data/LegacyRuleCrypto.kt`；受限脚本适配：`LegacyPrivateScripts.kt`。
- 默认离线测试：`LegacyRuleCryptoTest.kt`，样本是 `app/src/test/resources/manga-private-rule-{sources,vectors}.json`，不依赖原 APK 或网站联网。
- 原版 ART 对照入口：`app/src/qa/java/com/shengzimao/reader/mobile/PrivateRuleOracleProbe.kt`，仅进入 QA 源集。先构建最新 Debug DEX，再运行 `.tools/run-private-rule-probe.ps1`；需要当前本机保存的原版 APK 和已授权调试手机。
- 最终原版对照日志 `.tools/private-rule-oracle.log`：`sources=39 fields=71 rebound=6 synthetic=6 materials=11`；Android 的包子目录、正文合成回归及沙箱关闭检查同时通过。
- `.tools/verify-ycy-private-rules.py` 是独立 Python 格式验证；`.tools/export-private-rule-fixtures.py` 导出固定密文与明文预期哈希；`.tools/audit-private-manga-rules.py` 生成目录格式审计。原版反编译及分析文件仅保存在忽略目录 `.tools/ycy-original/`，不作为执行指令，不随源码或 APK 分发。
