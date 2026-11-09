# 额度哨兵 · QuotaSentry

> 把 Claude / ChatGPT / Gemini 的 5 小时额度窗口管起来：后台常驻、到点自动发一条保活消息、首页实时看剩余额度和重置倒计时。

[![Release](https://img.shields.io/github/v/release/DeepShawn/autoreset?include_prereleases&label=release)](https://github.com/DeepShawn/autoreset/releases)
[![minSdk](https://img.shields.io/badge/minSdk-26-57E6C0)](https://developer.android.com)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

## 功能

- **三家账号接入**：Claude、ChatGPT、Gemini，支持「粘贴 Cookie」和「内置网页登录」两种方式
- **保活发送**：跟随额度窗口 / 固定间隔 / 每天定时三种计划，到点自动发一条很短的问候
- **额度看板**：官方接口优先（Claude / ChatGPT），不可用时本地 5 小时滚动窗口估算（Gemini），显示剩余比例、剩余条数、重置倒计时、数据来源
- **后台常驻**：`specialUse` 前台服务 + 划掉任务不退出 + 开机自启 + 精确闹钟兜底 + 电池优化/自启动引导
- **代理**：HTTP / SOCKS5，国内网络基本必备
- **Liquid Glass + MIUIX**：深色优先的液体玻璃界面，三 Tab 丝滑切换，支持浅色与强调色自定义
- **自动更新**：从本仓库 Release 检查新版本，应用内下载安装（启动时 6 小时一次自动检查）
- **Claude OAuth 额度**：可选粘贴 Claude Code 的 OAuth 令牌，直接读取官方 5h / 7d 用量；发送消息仍用网页 Cookie

## 安装

从 [Releases](https://github.com/DeepShawn/autoreset/releases) 下载最新的 `QuotaSentry-<版本>.apk`，允许安装未知来源应用即可。

首次打开请按 [docs/USER_GUIDE.md](docs/USER_GUIDE.md) 完成：连接账号 → 填代理 → 打开后台保活四项授权（小米/HyperOS 另需自启动 + 省电无限制 + 最近任务加锁）。

## 构建

环境：JDK 21、Android SDK 36、Gradle 8.13。

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
gradle :app:assembleDebug     # 调试包
gradle :app:assembleRelease   # 需要 keystore.properties（见 keystore.properties.example）
gradle :app:testDebugUnitTest # 96 个纯 JVM 单测
```

> 本机（aarch64 proot）构建需要把 `android.aapt2FromMavenOverride` 指向 arm64 的 aapt2，见 `tools/build.sh`。

## 技术架构

```
core/       共享数据模型、额度窗口计算、定时计算、SharedPreferences 仓库
data/       三家 Provider 适配器 + HttpURLConnection 引擎（代理/SSE）+ 容错 JSON 解析
service/    前台服务、发送引擎、闹钟调度、通知、开机自启、后台可靠性引导
ui/         Compose + Liquid Glass + MIUIX 风格三 Tab 界面、网页登录 WebView
update/     GitHub Release 检查、版本比较、APK 下载与安装（FileProvider）
```

- 无 Room / OkHttp / 图片库等重型依赖，release APK ≈ 1.3 MB；
- 所有凭据只保存在本机 App 私有目录，除三家服务和 GitHub Release 外无任何外发请求。

## 免责声明

三家服务都没有面向个人账号的公开 API，本 App 使用其网页版前端接口与用户自己的登录 Cookie，
属于非官方用法：接口可能随时失效，账号也可能收到风控提示。请仅在自己账号上低频使用（默认 4.5 小时一次），
不用于批量注册、刷量或对外提供服务。本项目与 Anthropic、OpenAI、Google 无任何关联。

## License

[MIT](LICENSE)
