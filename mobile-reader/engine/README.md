# 在线引擎编译说明

`app/src/main/assets/engine/` 里的 dex 不是 Android 工程编译出来的，而是离线脚本产物。
引擎源码不在本工程内：它来自桌面版的 SoNovel 二进制（`../independent-reader/resources/sonovel/app.jar`）。

## 需要的东西

- JDK 21（`D:\java21`）：`javac`、`jar`、`javap`
- Android SDK build-tools 36.0.0：`d8.bat`
- 依赖 jar（放在 `.tools/libs/`）：`rhino-1.7.15.jar`、`okhttp-jvm-5.1.0.jar`、`okio-jvm-3.15.0.jar`、`json-20250517.jar`
- 桌面版 `app.jar`（37 MB）与 `rules/*.json`

## 步骤

### 推荐：可重复构建脚本

在手机版工程根目录执行：

```powershell
./engine/build-engine.ps1 -JavaHome D:/java21 -AndroidSdk "$env:LOCALAPPDATA/Android/Sdk"
```

脚本仅从桌面版暂存的上游 JAR 读取公开代码与版本资源，编译 `engine/src`，重建核心及适配层 dex，并输出 SHA-256。Rhino dex 沿用步骤 3 的独立产物。

原始 `com.pcdd.sonovel.utils.JsCaller` 与 `VirtualThreadLimiter` 必须从核心 JAR 排除，替换为本项目提供的同名 Java 入口：分别转发到 Rhino 桥、使用有上限的普通线程池。仅移除 V8 DLL 仍会出现 JavetEnginePool 缺失；目录解析也会调用上游虚拟线程辅助类。下载通过 `ChapterParser` 顺序读取正文，不使用依赖 Java 21 虚拟线程与终端组件的桌面 `Crawler`；EPUB 由 Android 的 `DocumentExporter` 生成。

原始 `ChapterRenderer` 也必须排除并替换：保留上游的过滤和段落格式化，直接输出正文与标题，避免即使下载 TXT 也在类初始化时加载桌面模板引擎。Android 不通过引擎渲染 EPUB/HTML 模板，EPUB 统一由宿主原有导出模块生成。

`LogUtils` 使用 Android 进程日志，避免原实现向当前目录的 `logs/` 写文件导致正文读取前就失败。这些同名 Android 适配类都在 `engine/src` 中提供源码，不保留上游同名类的第二份定义。

### 1. 裁剪并转 dex 上游引擎（原理）

从 `app.jar` 里剔除安卓用不到的内容，再把剩下的 class 交给 d8：

```
剔除：*.dll / *.exe、com/caoccao/**（Javet）、org/jline/nativ/**、
      org/fusesource/jansi/internal/native/**、data/dictionary/**、
      org/apache/pdfbox/resources/**、META-INF/versions/**、
      META-INF/native-image/**、*.kotlin_module、原始 JsCaller.class
输出：assets/engine/dex/classes.dex、classes2.dex
```

`d8 --release --min-api 26 --output <out> <裁剪后的 jar>`

> 注意：jar 里自带一份 Kotlin 2.2 标准库。Android 工程另有一份 2.4.20，
> d8 带字节码分析会碰撞；实测直接对裁剪后的 jar 做 d8 可以通过，
> 但要确保 R8 在打包时不会扫描这些 class（引擎 dex 只以 assets 形式存在，不加入源码集合）。

### 2. 编译安卓适配层

```powershell
javac -source 17 -target 17 -encoding UTF-8 `
  -cp "rhino;okhttp;okio;json;app.jar" `
  -d classes engine/src/org/shengzimao/reader/AndroidEngine.java engine/src/org/shengzimao/reader/JavaScriptBridge.java
jar --create --file wrapper.jar -C classes .
d8 --release --min-api 26 --output <out> --classpath app.jar <空 jar> wrapper.jar
```

`--classpath` 只用于解析符号，`<空 jar>` 用来占位（d8 要求至少一个 program input）。

### 3. 编译 Rhino + JsBridgeImpl

`JsBridgeImpl` 特意写成 **Java**：它只依赖 Rhino 与 `java.*`，可以独立成 dex，
不必和 App 的 Kotlin 运行时共用一个类空间。

```powershell
javac -source 17 -target 17 -encoding UTF-8 -cp rhino.jar -d classesjs engine/js/com/shengzimao/reader/engine/JsBridgeImpl.java
# 把 rhino.jar 解压到同一目录后一起打包
jar --create --file rhino-js.jar -C 解压目录 .
d8 --release --min-api 26 --output <out> rhino-js.jar   # -> assets/engine/rhino/dex/classes.dex
```

### 4. 拷贝规则

把 `../independent-reader/resources/sonovel/rules/*.json` 复制到 `assets/engine/rules/`。

## 运行时如何加载

`data/OnlineEngine.kt`：

1. 首次启动把 assets 里的 dex 与规则释放到 `filesDir/sonovel/`；
2. 用一个 `InMemoryDexClassLoader` 装 `classes.dex + classes2.dex + wrapper.dex`；
3. 核心与 Rhino 都以 Android BootClassLoader 为父加载器，隔离应用经 R8 改名后的 Kotlin/OkHttp 类；
4. 为引擎的 `JsBridge` 接口建 `java.lang.reflect.Proxy`，反射调用子 loader 里的 `JsBridgeImpl.run`；
5. 通过反射调用 `AndroidEngine.configure(workDir, rulesPath)` / `dispatch(json)`。

内存 dex 不携带 classpath 资源。宿主通过资源 ClassLoader 提供从上游 JAR 提取的 `application.properties`，并在初始化时临时设置进程内 Java 属性 `config.file` 为私有配置绝对路径（与 Android 系统属性不同），随后恢复线程 ClassLoader 和旧属性值。配置内书源路径同样使用绝对路径，所有 dispatch 均使用引擎上下文 ClassLoader。

`SourceUtils` 使用上游全局配置，不能仅配置适配层的内存对象后忽略上游初始化错误；每次初始化都做真实书源 probe。详情与下载先按 URL 选中 sourceId，避免默认 -1 找不到书源。每个下载使用独立目录，正文及元数据一起清理。API 26 多 dex 使用文件加载器，API 27+ 使用多 buffer 内存加载器。
