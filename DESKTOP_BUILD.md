# 桌面独立版 0.4.0：Windows 源码构建

桌面工程在 `independent-reader/`。根目录的 Java 工程是保留历史的 SoNovel Fork；桌面构建使用另一个固定提交、干净工作区的上游克隆，避免将本 Fork 文档或未确认变动混入引擎。

## 准备环境

- Windows x64，Git，Node.js 24 LTS 与 npm，JDK 21（包含 javac 和 jlink），Maven 3.9。
- 将 `JAVA_HOME` 设置为 JDK 21 的安装目录，将 Maven 和 Git 加入 PATH。
- npm 依赖由 `independent-reader/package-lock.json` 锁定。不要用 Node.js 25 打包；当前打包器已对不支持的 Node 主版本拒绝构建。
- 当前源码固定 Electron 33.4.11，以保持本次已验证版本一致；升级 Electron 后需重新执行界面、原生窗口和打包回归。

以下 PowerShell 命令从仓库根目录开始。首次准备需要网络获取上游、Maven/npm 依赖与打包工具。

## 1. 准备固定引擎源码和 JAR

```powershell
git clone https://github.com/freeok/so-novel.git vendor/so-novel
git -C vendor/so-novel checkout --detach dcfc4a3f952eedd0036eb69fada01b935c06dc04
mvn -f vendor/so-novel/pom.xml -Pwindows-x64 "-Dmaven.test.skip=true" compile assembly:single
```

需要生成 `vendor/so-novel/target/app-jar-with-dependencies.jar`。直接执行 assembly 目标即可生成桌面使用的依赖 JAR，无需运行上游原生启动器的打包阶段。引擎克隆必须保持干净，`scripts/stage.cjs` 会检查其 Git 状态。

## 2. 生成最小 Java 21 运行时

```powershell
$readerModules = 'java.base,java.datatransfer,java.xml,java.prefs,java.desktop,java.logging,java.management,java.security.sasl,java.naming,java.net.http,java.security.jgss,java.transaction.xa,java.sql,java.xml.crypto,jdk.charsets,jdk.crypto.ec,jdk.crypto.mscapi,jdk.unsupported,jdk.zipfs'
& "$env:JAVA_HOME/bin/jlink.exe" --add-modules $readerModules --strip-debug --no-header-files --no-man-pages --compress=2 --output static/sonovel/runtime-minimal
$env:SZM_JAVAC = Join-Path $env:JAVA_HOME 'bin/javac.exe'
```

输出目录必须尚不存在；重复构建可复用已生成的运行时。`SZM_JAVAC` 指定编译桌面适配器所用的 JDK 21 编译器。仓库已包含 `build/icons/icon.ico` 和 `static/sonovel/rules/`，不需要其他旧阅读器代码。

## 3. 安装依赖、暂存资源、启动

```powershell
Set-Location independent-reader
npm ci
npm run stage
npm start
```

`stage` 会编译 `engine/ReaderEngine.java`，复制引擎、运行时、图标、规则、许可证及对应源码，写入构建来源和 SHA-256 信息。`resources/` 为生成内容，不提交到 Git。

## 4. 测试与生成安装包

```powershell
npm test
npm run test:reader
npm run test:normal
npm run test:welcome
npm run test:startup
npm run test:online-ui
npm run test:features
npm run build:installer
npm run test:packaged
```

界面测试会创建临时窗口，请串行运行；所有测试使用独立配置。在线链路测试使用内置 Java 引擎和合成站点，不读取私人书库。

安装包输出到 `independent-reader/release/圣子猫阅读器-独立版-0.4.0-安装包.exe`；开发运行目录为 `release/win-unpacked/`。打包过程对 ASAR 每个文件校验哈希，错误时停止生成安装包。

`VERIFICATION-0.4.0.md` 中的证据路径指维护者本次本地验证记录。`.verification/` 不公开提交，可以使用上述命令重新生成自己的记录。此源码上传不代表已经在 GitHub Releases 发布安装包。
