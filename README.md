# 圣子猫阅读器

> 基于 [freeok/so-novel](https://github.com/freeok/so-novel) 的独立 Fork，新增独立编写的 Electron 桌面阅读器。不是 SoNovel 官方发行版，不代表原作者或上游贡献者。

## 本仓库的范围

- **桌面独立版 0.4.0** 源码位于 [`independent-reader/`](independent-reader/README.md)，包含隐蔽单行与普通大屏阅读、首次欢迎和每次启动位置提示、关于页面、原生置顶、书库与文档转换、在线检索与增量追更。
- 在线引擎上游为 **SoNovel**。仓库根目录保留其 Java 源码、构建配置和提交历史；固定基线为 `dcfc4a3f952eedd0036eb69fada01b935c06dc04`，本次未修改根目录的上游业务代码。
- 独立版的 Java 适配器位于 `independent-reader/engine/`；它与桌面代码一起发布，不包含旧 Electron/Vue 阅读器工程、个人书库、阅读进度、配置或测试缓存。
- 开发和安装包构建步骤见 [Windows 构建说明](DESKTOP_BUILD.md)，功能与验证范围见 [0.4.0 验证记录](independent-reader/VERIFICATION-0.4.0.md)。
- 本次上传源码及必要图标、规则资源，**未上传二进制安装包**。下方保留的上游安装脚本、下载入口和赞助链接仍属于 SoNovel，不是本 Fork 的桌面发行入口。

## 许可证与来源声明

保留上游的 [GNU AGPL v3 许可证原文](LICENSE)、作者署名和 [法律免责声明](bundle/DISCLAIMER.md)。本 Fork 新增的说明文字同样按 GNU AGPL v3 发布，不宣称取得上游项目名称、商标或第三方内容的额外权利。

详细来源、第三方组件与分发说明见 [NOTICE.md](NOTICE.md)，修改日期和范围见 [FORK_CHANGES.md](FORK_CHANGES.md)。上游许可不构成对任何第三方网站内容、小说或素材的使用授权。

---

## SoNovel 上游说明

以下保留上游 README 内容，其项目名称、安装入口、图片及贡献者信息仍指向原项目。

<div align="center">
  <img src="assets/logo.png" style="width: 128px;"/>
  <h1 align="center">So Novel</h1>
  <h4 align="center"></h4>
</div>

<div align="center">

[![zread](https://img.shields.io/badge/Ask_Zread-_.svg?style=flat&color=00b0aa&labelColor=000000&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB3aWR0aD0iMTYiIGhlaWdodD0iMTYiIHZpZXdCb3g9IjAgMCAxNiAxNiIgZmlsbD0ibm9uZSIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj4KPHBhdGggZD0iTTQuOTYxNTYgMS42MDAxSDIuMjQxNTZDMS44ODgxIDEuNjAwMSAxLjYwMTU2IDEuODg2NjQgMS42MDE1NiAyLjI0MDFWNC45NjAxQzEuNjAxNTYgNS4zMTM1NiAxLjg4ODEgNS42MDAxIDIuMjQxNTYgNS42MDAxSDQuOTYxNTZDNS4zMTUwMiA1LjYwMDEgNS42MDE1NiA1LjMxMzU2IDUuNjAxNTYgNC45NjAxVjIuMjQwMUM1LjYwMTU2IDEuODg2NjQgNS4zMTUwMiAxLjYwMDEgNC45NjE1NiAxLjYwMDFaIiBmaWxsPSIjZmZmIi8%2BCjxwYXRoIGQ9Ik00Ljk2MTU2IDEwLjM5OTlIMi4yNDE1NkMxLjg4ODEgMTAuMzk5OSAxLjYwMTU2IDEwLjY4NjQgMS42MDE1NiAxMS4wMzk5VjEzLjc1OTlDMS42MDE1NiAxNC4xMTM0IDEuODg4MSAxNC4zOTk5IDIuMjQxNTYgMTQuMzk5OUg0Ljk2MTU2QzUuMzE1MDIgMTQuMzk5OSA1LjYwMTU2IDE0LjExMzQgNS42MDE1NiAxMy43NTk5VjExLjAzOTlDNS42MDE1NiAxMC42ODY0IDUuMzE1MDIgMTAuMzk5OSA0Ljk2MTU2IDEwLjM5OTlaIiBmaWxsPSIjZmZmIi8%2BCjxwYXRoIGQ9Ik0xMy43NTg0IDEuNjAwMUgxMS4wMzg0QzEwLjY4NSAxLjYwMDEgMTAuMzk4NCAxLjg4NjY0IDEwLjM5ODQgMi4yNDAxVjQuOTYwMUMxMC4zOTg0IDUuMzEzNTYgMTAuNjg1IDUuNjAwMSAxMS4wMzg0IDUuNjAwMUgxMy43NTg0QzE0LjExMTkgNS42MDAxIDE0LjM5ODQgNS4zMTM1NiAxNC4zOTg0IDQuOTYwMVYyLjI0MDFDMTQuMzk4NCAxLjg4NjY0IDE0LjExMTkgMS42MDAxIDEzLjc1ODQgMS42MDAxWiIgZmlsbD0iI2ZmZiIvPgo8cGF0aCBkPSJNNCAxMkwxMiA0TDQgMTJaIiBmaWxsPSIjZmZmIi8%2BCjxwYXRoIGQ9Ik00IDEyTDEyIDQiIHN0cm9rZT0iI2ZmZiIgc3Ryb2tlLXdpZHRoPSIxLjUiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIvPgo8L3N2Zz4K&logoColor=ffffff)](https://zread.ai/freeok/so-novel)
[![GitHub License](https://img.shields.io/github/license/freeok/so-novel?style=flat-square)](https://github.com/freeok/so-novel/blob/main/LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/freeok/so-novel)](https://github.com/freeok/so-novel/releases/latest)
[![GitHub Downloads](https://img.shields.io/github/downloads/freeok/so-novel/total.svg?style=flat-square)](https://github.com/freeok/so-novel/releases/latest)

</div>

## 概述

**So Novel** 是一款通用的网页内容处理与导出工具，它致力于帮助用户高效地从网页中提取结构化信息，并将其灵活导出为
EPUB、TXT、PDF 等多种标准电子文档格式。适用于学习采集、格式转换、电子书制作等场景。

## 预览

<details>
  <summary>点击查看图片</summary>

### TUI 预览 (Text-based User Interface)

![preview-tui.png](assets/preview-tui.png)

### WebUI 预览 (网页版)

![preview-webui.jpg](assets/preview-webui.jpg)

### CLI 预览 (Command Line Interface)

![preview-cli.png](assets/preview-cli.png)

</details>

## 安装

### 📦 手动下载压缩包

<https://github.com/freeok/so-novel/releases>

### 🪟 Windows

#### 1. PowerShell

```powershell
# powershell
irm https://raw.githubusercontent.com/freeok/so-novel/main/bin/windows-install.ps1 | iex
```

#### 2. CMD (命令提示符)

```cmd
powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/freeok/so-novel/main/bin/windows-install.ps1 | iex"
```

### 🐧 Linux

```bash
bash <(curl -sSL https://raw.githubusercontent.com/freeok/so-novel/main/bin/linux-install.sh)
```

### 🍺 Homebrew

```bash
brew tap ownia/homebrew-ownia
# https://docs.brew.sh/Tap-Trust#why-tap-trust-exists
brew trust --formula ownia/homebrew-ownia/so-novel
brew install so-novel
```

### 🍨 Scoop

```bash
scoop bucket add freeok https://github.com/freeok/scoop-bucket
scoop install freeok/so-novel
```

### 🐳 Docker

**方式 1: 脚本一键安装**

```bash
curl -sSL https://raw.githubusercontent.com/freeok/so-novel/main/bin/docker-install.sh | bash
```

**方式 2: Docker Compose (NAS 首选)**

```yaml
services:
  sonovel:
    image: ghcr.io/freeok/sonovel:latest
    container_name: sonovel
    ports:
      - "7765:7765"
    environment:
      JAVA_OPTS: "-Dmode=web"
    volumes:
      - sonovel_data:/sonovel
    restart: unless-stopped

volumes:
  sonovel_data:
```

**方式 3: Docker Run**

```bash
# 如需挂载，请提前准备好 config.ini 文件、rules 目录
docker run -d \
  --name sonovel \
  -v /sonovel/config.ini:/sonovel/config.ini \
  -v /sonovel/rules:/sonovel/rules \
  -v /sonovel/downloads:/sonovel/downloads \
  -p 7765:7765 \
  -e JAVA_OPTS='-Dmode=web' \
  ghcr.io/freeok/sonovel:latest
```

**从源码构建 Docker 镜像**

```bash
# 确保已安装 git、jdk 21、maven
sudo apt update
sudo apt install -y git
sudo apt install -y openjdk-21-jdk
sudo apt install -y maven

# 验证安装
git -v
javac -version
mvn -v

# 构建项目
git clone https://github.com/freeok/so-novel.git && cd so-novel
# arch: [x64|arm64]
sh bin/release-linux.sh [arch]

# 构建 Docker 镜像
cp -r target/sonovel-linux_[arch]/{app.jar,config.ini,rules} .
docker build -t sonovel .
```

> [!TIP]
>
> 为获得最佳阅读体验，建议使用以下电子书阅读器：
>
> **桌面端**
>
> - [Readest](https://readest.com/)
> - [Koodo Reader](https://www.koodoreader.com/zh)
> - [Calibre](https://calibre-ebook.com/)
> - [Neat Reader（网页版）](https://www.neat-reader.cn/webapp)
>
> **移动端**
>
> - [Readest](https://readest.com/)
> - [Apple Books](https://www.apple.com/apple-books/)
> - [Moon+ Reader（静读天下）](https://moondownload.com/chinese.html)
> - [Kindle](https://apps.apple.com/us/app/amazon-kindle/id302584613)
>
> 如需转换为其它电子书格式，可使用：
>
> - [FreeConvert](https://www.freeconvert.com/zh)
> - [Calibre](https://calibre-ebook.com/zh_CN)
>
> 修复 WPS、掌阅等软件无法打开 so-novel 下载的 EPUB：https://github.com/freeok/so-novel/discussions/199

## 自定义 JVM 系统属性

| 参数            | 说明                     | 默认值          |
|---------------|------------------------|--------------|
| -Dmode        | 启动模式，可选值：tui\|cli\|web | tui          |
| -Dconfig.file | 配置文件路径                 | ./config.ini |

用法

> [!NOTE]
>
> Windows 修改 [sonovel.l4j.ini](bundle/sonovel.l4j.ini)
>
> Linux 修改  [run-linux.sh](bundle/run-linux.sh)
>
> macOS 修改  [run-macos.sh](bundle/run-macos.sh)

## 使用本地 JDK / JRE 启动

如果你不想使用内置 JRE（runtime 目录），可以通过本地 JDK / JRE 启动程序

Windows 使用脚本 start-custom-jre.cmd：

```cmd
REM --------------------------------------------------
REM 高级用户使用自定义 JRE 启动程序
REM 将 "your_path\java.exe" 替换为你的 JRE 路径，例如：
REM "C:\Java\jdk-21\bin\java.exe"
REM --------------------------------------------------
@echo off
your_path\java.exe ^
  -XX:+UseZGC ^
  -XX:+ZGenerational ^
  -Dconfig.file=config.ini ^
  -Dmode=tui ^
  -Dfile.encoding=GBK|Big5 ^
  -jar app.jar
```

Linux / macOS：[run-linux.sh](bundle/run-linux.sh) / [run-macos.sh](bundle/run-macos.sh) 修改 java 路径

## 常见问题

https://github.com/freeok/so-novel/issues?q=label%3A%22usage%20question%22

## 讨论

https://github.com/freeok/so-novel/discussions?discussions_q=

## 支持 & 赞助

如果觉得有所帮助，欢迎扫码赞助☕、点击项目主页顶部的⭐Star 按钮支持！

🚀这将是我们持续更新的动力源泉！同时，你也能第一时间获取到最新的更新动态。💡❤️

| 支付宝赞助                                                           | 微信赞助                                                           |
|-----------------------------------------------------------------|----------------------------------------------------------------|
| <img src="assets/donation-alipay.png" alt="支付宝收款码" width="197"> | <img src="assets/donation-wechat.jpg" alt="微信赞赏码" width="197"> |

[项目赞助者列表](./SPONSORS.md)

## 免责声明

在使用本工具前，请务必仔细阅读我们的[法律免责声明](bundle/DISCLAIMER.md)。使用本工具即表示您已阅读、理解并同意遵守所有条款。

## Star History

[![Star History Chart](https://api.star-history.com/chart?repos=freeok/so-novel&type=date&legend=top-left&sealed_token=5phfE_GEDrDmXeO6LQ3d-73K_Z4w75PKWLzMkb1UqUVwFszcO9t76DKuf9l4wYgZ-ik27ocHo67YGTpn8oCGHq0_Iy_3FKy-mi8H8BY6S3x0j5HYYO9__A)](https://www.star-history.com/?repos=freeok%2Fso-novel&type=date&legend=top-left)
