# Codex Remote Android

通过 Tailscale 从 Android 手机连接家中 Windows Bridge，远程查看 Codex 任务、继续对话并新建任务。

> 当前为 Alpha 版本。请只在你信任的 Tailnet 内使用，不要把 Bridge 直接暴露到公网。

## 已实现

- 一次性深链配对，手机使用独立设备凭据
- AndroidKeyStore 加密凭据与加密待发命令队列
- 任务列表、任务摘要、继续对话、新建任务
- 新建任务可选择项目、模型和推理强度
- Android 前台服务、WebSocket 事件游标、断线自动重连
- 仅在 Chrome DevTools MCP 遇到 OAuth、验证码或第三方登录时提醒打开向日葵
- 普通 Codex 任务使用 `approvalPolicy: never` 和 `dangerFullAccess`，不弹常规审批
- 显式项目白名单，手机不能选择 registry 之外的目录

## 下载

在 [GitHub Releases](https://github.com/moyi888/codex-remote-android/releases) 下载：

- `codex-remote-windows-*.exe`：Windows Bridge
- `codex-remote-android-*.apk`：Android 安装包

尚未发布 Release 时，也可以从最新 GitHub Actions 的 artifacts 下载开发构建。

## Windows Bridge 配置

1. 确保 Windows 已登录 Codex，`codex` 命令可用；如果不在 `PATH`，启动时传 `--codex-command`。
2. 安装并登录 Tailscale，记下 Windows 的 `100.x.x.x` Tailnet IP。
3. 复制 [projects.example.json](config/projects.example.json)，把 `path` 改成真实绝对目录。
4. 启动 Bridge：

```powershell
.\codex-remote.exe serve `
  --listen 100.88.10.20:8787 `
  --advertise-url http://100.88.10.20:8787 `
  --projects .\projects.json `
  --codex-command codex
```

Bridge 会输出 5 分钟有效的一次性配对链接。当前命令行先输出链接；二维码终端渲染将在后续版本补充。

## Android 使用

1. 手机安装 APK，并加入同一个 Tailnet。
2. 点击电脑输出的 `codex-remote://pair?...` 链接，或复制到 App 配对页。
3. 配对成功后即可刷新任务、打开任务继续发送，或选择项目/模型/推理强度新建任务。
4. 收到“需要在电脑上完成授权”通知时，打开向日葵处理第三方浏览器登录/授权，然后回 App 重试。

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

- 手机详情页当前显示线程摘要并可继续对话；完整历史消息 API 尚在实现中。
- APK 暂为可安装的 debug 签名构建；正式签名与 Play 分发尚未配置。
- 第三方登录授权必须通过向日葵等远控工具在电脑上完成。

协议与安全说明见源码和测试。内部设计资料位于本地 `docs/`，按项目策略不进入 Git 历史。

## License

[MIT](LICENSE)
