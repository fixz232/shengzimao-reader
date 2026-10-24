# 圣子猫阅读 · 手机版

Android 版圣子猫阅读器。与桌面版共用的理念是“打开就读”，但手机版**不包含任何隐蔽 / 伪装模式**：版面、交互与配色都按正常阅读设计。

返回 [仓库首页](../README.md)；许可、内容权利、数据与免责说明见 [项目声明](../NOTICE.md)，组件来源见 [手机版第三方说明](THIRD_PARTY_NOTICES.md)。

- 当前源码版本：**1.1.2**；安装包请查看 [本仓库 Releases](https://github.com/fixz232/shengzimao-reader/releases) 的实际附件，没有附件时按本文自行构建。覆盖升级必须沿用相同发布签名，源码版本不代表对应 APK 已发布。
- 包名：`com.shengzimao.reader.mobile`
- 最低系统：Android 8.0（API 26）；目标 API 36；编译 API 37

## 功能

### 导航

底部三个入口：**书库 / 在线搜书 / 设置**。阅读页独占全屏，不显示底部导航；从阅读页返回后回到离开时的页面。

### 阅读

- 左右三分之一点击翻页，中间区域呼出菜单；长按屏幕放置书签
- 目录（支持章节名 / 序号搜索）与书签抽屉
- 进度滑杆、上一章 / 下一章、页码与章节名页脚
- 五档底色：纸白 / 护眼 / 豆绿 / 夜间 / 纯黑（正文与底色成对定义，保证对比度）
- 字号 12–40、行距 100%–260%、页边距、字体（黑体 / 宋体 / 等宽）、加粗、首行缩进
- 翻页动画：平移 / 覆盖 / 淡入 / 仿真 / 无
- 朗读（TTS）：底部栏「朗读」，读完整页自动翻页续读；可调语速与音调
- 阅读时保持亮屏、隐藏状态栏

### 书库

- 导入 TXT / EPUB（可多选），TXT 自动识别 UTF-8 / UTF-16 / GB18030
- 网格与列表两种视图、书名 / 作者搜索、在读 / 未读 / 读完筛选、四种排序
- 收藏、置顶、标记读完、编辑书名 / 作者 / 简介、移除
- 导出为 TXT 或 EPUB（EPUB 按识别出的章节分文件并生成目录）
- 首次启动内置一本《欢迎使用圣子猫阅读》，七章读完即可上手

### 在线搜书与追更

在线能力移植自桌面版：把 SoNovel 引擎预编译成 dex 随 APK 分发，运行时用 `InMemoryDexClassLoader` 加载，适配层是 `engine/src/org/shengzimao/reader/AndroidEngine.java`（桌面版 `ReaderEngine.java` 的安卓版本）。

- 首次使用需滚动读完声明并勾选确认；确认状态只存本机，不进备份
- 按「书名 作者」多关键词搜索；同书多书源聚合，可切换书源、查看详情与在线目录
- 相关性过滤与排序在本地完成（与桌面版 `search-matching.cjs` 同一套打分规则）
- 下载入库支持 TXT / EPUB；批量下载一次最多 30 本，严格串行
- 入库后自动记录原站地址与在线目录清单并加入追更
- 追更只追加新增章节：源站目录变短、旧章节标题或链接变化时停止并保留原文
- `@js:` 书源规则由 Rhino 执行（上游用 Javet，安卓无 V8 原生库）
- 「在线搜书」页面内提供 **聚合搜书 / 番茄小说** 两个标签，切换保留各自搜索结果
- 番茄页同步桌面版公开搜索接口，支持书名、作者、书籍 ID 与官网链接；可看详情、目录、打开官网
- 番茄公开且可读的正文可下载入库并追更，支持与桌面版相同的已知公开网页字体字符映射；未知字体、缺字、登录、付费或验证要求会停止，不保证目录中所有章节都能下载
- 单本下载最多并发读取 4 章，按原目录顺序拼接；聚合下载遵守书源配置的更低并发和请求间隔，番茄请求至少间隔 120 ms；取消或正文缺失时整次任务失败，不把目录或只有标题的章节入库
- 番茄功能独立于 SoNovel，引擎加载失败不影响番茄搜索；错误信息与重新搜索入口会直接显示
- 搜索框支持键盘搜索键，历史关键词在窄屏换行，运行任务可取消

旧版如果已入库“只有目录”的空书，请先备份，再从书库移除该空书后重新下载。相同在线 URL 的已有书按原逻辑去重，升级不会自动覆盖用户正文。

### 数据

- 全部数据在应用私有目录，不申请存储权限
- 导出 / 恢复 `.szmbak` 备份（zip，结构与桌面版一致，可与桌面版互相恢复）
- 阅读统计：今日 / 近七天 / 累计时长，书库在读与读完数量

## 构建

```powershell
# 环境：JDK 17+、Android SDK（platform 37、build-tools 36）、已加入 PATH 的 Gradle 9.7.1
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
gradle :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。

- AGP 9.4.1 + Gradle 9.7.1（AGP 9 自带 Kotlin 支持，不需要 `org.jetbrains.kotlin.android` 插件）
- 工程路径含中文，`gradle.properties` 里设置了 `android.overridePathCheck=true`
- `compileSdk = 37`：Compose 1.12.x 要求，若改回 36 需要同步降级 Compose
- 签名使用本机 `keystore.properties`；发布密钥和签名口令不纳入源码分发。覆盖升级必须沿用同一发布密钥。
- Debug 包名为 `com.shengzimao.reader.mobile.verification`，可与发布版并存，测试不读写发布版书库。
- 源码不携带 Gradle 安装目录、Android SDK 或发布密钥；需先配置本机开发环境。没有签名配置时生成未签名发布包。

### 在线引擎资产

`app/src/main/assets/engine/` 下的文件都是**预编译产物**，不参与 Kotlin 编译与 R8：

| 文件 | 来源 |
| --- | --- |
| `dex/classes.dex`、`dex/classes2.dex` | 上游 SoNovel `app.jar`（剔除 Windows 原生库、Javet、jline/jansi native、HanLP 词典后，由 d8 转 dex） |
| `dex/wrapper.dex` | `engine/src/org/shengzimao/reader/AndroidEngine.java` 编译后 d8 |
| `rhino/dex/classes.dex` | Rhino 1.7.15 + `engine/js/.../JsBridgeImpl.java` |
| `rules/*.json` | 桌面版书源规则 |
| `application.properties` | 上游 JAR 中的公开版本资源；通过资源 ClassLoader 提供给引擎 |

重新生成时见 `engine/README.md` 的步骤。

## 目录结构

```
app/src/main/java/com/shengzimao/reader/mobile/
├── MainActivity.kt            单 Activity + 三级页面（书库 / 阅读 / 在线）
├── ReaderApp.kt               应用级容器、内置欢迎书
├── data/                      书库、导入导出、设置、在线数据与引擎宿主
├── reader/                    分页器、阅读画布、TTS
└── ui/                        各页面与对话框
```

## 已知限制

- 已同步 Windows 0.5.4 的已知公开网页字体还原；尚未移植公开文集备用来源。未知字体、缺字或访问限制会停止，搜索或目录可见不保证整本书可下载
- 只支持 TXT 与 EPUB；PDF / DOCX / Markdown 转换未移植（桌面版有）
- 在线引擎加载失败时，本地阅读不受影响，在线页会给出提示
- 追更依赖源站目录未发生重排；源站改版后需重新从原站入库
- 发布验证范围见 [VERIFICATION.md](VERIFICATION.md)；未覆盖全部系统版本、全部第三方书源或 TTS 设备差异
- 兼容 HTTP 书源已配置 `networkSecurityConfig`，这些站点的流量不加密；HTTPS 仍校验系统证书
- 备份只携带书籍与进度，阅读设置需在本机重新调整

## 版本记录

### 1.1.2

- 修复普通书源详情与下载触发桌面封面补全时的 Java 21 虚拟线程崩溃；Android 保留源站封面，不再请求跨站封面排名或依赖 AWT
- 构建阶段替换上游间接调用的虚拟线程工厂及 executor close，覆盖书源可用性检查等遗漏路径
- 番茄正文按精确容器与成对标签提取，保留嵌套段落，排除标题、目录链接及导航，拒绝把空正文或仅标题入库
- 同步桌面已知公开网页字体映射，保留 AGPL-3.0 来源、许可与致谢；未知字体及缺字仍停止
- 聚合与番茄单本下载增加最多 4 章的有界并发，按目录顺序保存；批量书籍仍逐本处理

### 1.1.1

- 修复引擎内存加载丢失 classpath 配置资源及规则路径错误；补充具体错误诊断
- 隔离引擎与应用经 R8 优化后的运行时依赖，避免发布包类名冲突
- 将上游 Javet 脚本入口替换为 Rhino 桥；下载改用 Android 可用的顺序章节解析，不调用桌面虚拟线程和终端组件
- 修复下载文件与元数据任务目录不一致，以及 EPUB 被直接当 UTF-8 文本读取的问题
- 在线搜书内新增番茄小说标签，同步桌面版搜索、详情、目录、公开正文入库与追更流程
- 完善错误展示、重试、取消、窄屏历史记录、关于页与第三方声明

### 1.1.0（重发）

- 改为底部导航：书库 / 在线搜书 / 设置
- 在线搜书页补充「书源与网络」说明与「检查服务」入口，避免用户误以为没有该功能

### 1.1.0

- 新增朗读（TTS）与 TXT / EPUB 导出
- 新增在线搜书、下载入库与追更（SoNovel 引擎移植为安卓 dex）
- 新增 `.szmbak` 备份导出与恢复
- 修复：补上 INTERNET 权限（此前在线功能会全部失败）、在线任务并发写坏正文、书库索引非原子写、
  备份 id 路径穿越、分页在非 UI 线程调用 TextMeasurer、在线页返回键直接退出应用等

## 协议、声明、免责与致谢

项目按 GNU AGPL-3.0 分发，详见 [LICENSE](LICENSE)。修改和再分发时应遵守适用许可，保留版权、许可、修改及来源说明；第三方组件仍按各自许可使用。源码仓库为 `https://github.com/fixz232/shengzimao-reader`。

本软件提供阅读工具与公开网页适配，不提供任何作品授权。作品版权归原作者和其他权利人；公开可访问不表示允许转载、传播或商业使用。请仅使用有权访问、下载及处理的内容，遵守适用法律、平台条款和权利人的要求，优先支持正版。

番茄小说页面由本项目独立实现，与番茄小说及其运营方无隶属、合作或授权关系；相关名称与标识归各权利人。仅对已识别的公开网页字体执行字符映射，未知字体或缺字会停止；不绕过登录、付费、验证码等访问限制，不携带第三方私有令牌、代理地址池或破解服务。

搜索关键词和网络请求会直接发往第三方站点，第三方可能记录 IP 地址及访问行为。书库、阅读进度与声明确认保存在本机；本项目不设置书库上传服务。应用卸载会清除私有数据，升级前建议自行备份。

软件在适用法律及开源许可允许的范围内按现状提供，不承诺第三方内容的授权状态、准确性、完整性、安全性或持续可用性。网站规则变化、网络故障或用户操作可能造成访问失败、目录不一致或数据损失；上述说明不排除依法不可排除的权利和责任。

感谢 SoNovel（`freeok/so-novel`，AGPL-3.0）、[Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader)（`zhongbai2333/Tomato-Novel-Downloader`，参考提交 `75f0dd327d7fd55ee646564a4da4d028139c591c`，MIT）、Rhino、AndroidX / Jetpack Compose、Kotlin、kotlinx.coroutines、OkHttp、Okio、Hutool、jsoup 及相关开源贡献者。番茄功能参考公开接口和流程，未引入其私有服务。第三方清单、来源及许可边界见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，项目致谢见 [仓库首页](../README.md#致谢)。

已知公开网页字体字符数据来自 `ying-ck/fanqienovel-downloader` 的 `src/charset.json`（提交 `4cbb46e9038f2d714407745898380cf02c629deb`，AGPL-3.0），与桌面版同源；许可全文见 `notices/fanqienovel-downloader-LICENSE.txt`。此数据不适用上述 MIT 许可。
