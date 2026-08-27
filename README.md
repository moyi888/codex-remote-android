# Codex Remote Android

[English](README.en.md) | 简体中文

通过 Tailscale 从 Android 手机安全连接家中的 Windows 电脑，远程查看 Codex 任务、继续对话并新建任务。

> 当前为 Alpha 版本。请只在你信任的 Tailnet 内使用，不要把 Windows App 直接暴露到公网。

## 四步开始使用

1. 电脑安装并登录 [Tailscale for Windows](https://tailscale.com/download/windows)
2. 手机安装并登录 [Tailscale for Android](https://tailscale.com/download/android)
3. 从 [v0.2.0-alpha.2 发布页](https://github.com/moyi888/codex-remote-android/releases/tag/v0.2.0-alpha.2)下载并打开 Windows App，同时安装 Android APK
4. 用 Android App 扫描 Windows App 显示的二维码

Tailnet 是同一 Tailscale 账号或组织下设备组成的私有网络。两台设备登录同一个 Tailnet 后，Windows App 会自动发现 Tailscale 地址、启动本地 Bridge 并显示一次性二维码。无需手填 IP、端口或 Tailscale ACL，也无需创建项目白名单配置。

Tailscale 仍需在电脑和手机上单独安装并完成登录。本项目不建设公网中继，因此没有额外服务器、域名或带宽费用。

## 下载

在 [v0.2.0-alpha.2 发布页](https://github.com/moyi888/codex-remote-android/releases/tag/v0.2.0-alpha.2)下载：

- `codex-remote-windows-*.exe`：Windows App
- `codex-remote-cli-windows-*.exe`：保留控制台输出的高级命令行版本
- `codex-remote-android-*.apk`：Android App

尚未发布 Release 时，也可以从最新 GitHub Actions 的 artifacts 下载开发构建。

## 可以做什么

- 查看 Windows 上全部非归档 Codex 任务，不受单一项目目录限制。
- 打开已有任务、查看状态并继续发送消息。
- 新建任务并选择模型、推理强度以及 Codex 历史任务中出现过的目录。
- 使用一次性二维码配对；每台手机使用独立设备凭据，可在 Windows App 中撤销。
- AndroidKeyStore 加密凭据与待发命令队列，断线后自动重连。
- 普通任务使用 `approvalPolicy: never` 和 `dangerFullAccess`，不会弹出常规文件或命令审批。

Codex 不强制使用官方账号登录。官方登录、中转服务或自定义配置均可，只要当前电脑环境能正常启动 Codex `app-server`。Windows App 会自动寻找已配置的 Codex 运行入口。

## 需要在电脑操作的情况

Chrome DevTools MCP 等第三方工具遇到 OAuth、验证码、浏览器登录或授权确认时，手机 App 会尽可能提醒你。此类网页交互仍需打开向日葵等远控工具，在电脑浏览器中完成。

即使授权提示读取失败也不会影响安全性：Codex 任务会停止、失败或在最终回复中说明未授权。完成浏览器授权后，从手机再次要求任务执行即可。

## 高级模式

通常直接双击 `codex-remote-windows-*.exe` 即可，它是不会弹出控制台窗口的托盘 App。命令行模式使用 `codex-remote-cli-windows-*.exe`，用于调试、自定义监听地址或兼容已有部署：

```powershell
.\codex-remote-cli-windows-v0.2.0-alpha.2.exe serve `
  --listen 100.88.10.20:8787 `
  --advertise-url http://100.88.10.20:8787 `
  --projects .\projects.json `
  --codex-command codex
```

- `serve`：显式启动 Bridge 服务。
- `--listen`：自定义监听地址。
- `--advertise-url`：自定义写入配对邀请的访问地址。
- `--projects`：兼容旧版的显式项目 registry；桌面主流程不需要它。
- `--codex-command`：指定自定义 Codex 启动命令。

直接运行 `codex-remote-cli-windows-v0.2.0-alpha.2.exe version` 可查看版本（文件名中的版本号以实际下载为准）。高级模式中的监听地址仍应仅对 Tailnet 开放。

## 从源码验证

项目固定工具链：Go 1.24.2、Gradle Wrapper 8.11.1、AGP 8.10.1、Kotlin 2.1.21、Java 21（CI）。

```powershell
cd bridge
mise exec go@1.24.2 -- go test ./...
mise exec go@1.24.2 -- go vet ./...
mise exec go@1.24.2 -- go build -o codex-remote.exe ./cmd/codex-remote
```

Android 使用仓库内 Wrapper：

```bash
cd android
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

## 当前限制

- 手机详情页当前显示任务摘要并可继续对话；完整历史消息 API 尚在实现中。
- APK 暂为可安装的 debug 签名构建；正式签名与 Play 分发尚未配置。
- 第三方登录和浏览器授权必须通过向日葵等远控工具在电脑上完成。

协议与安全说明见源码和测试。内部设计资料保留在本地 `docs/`，按项目策略不进入 Git 历史。

## License

[MIT](LICENSE)
