# Codex Remote Android

English | [简体中文](README.md)

Securely connect an Android phone to Codex on a Windows PC over Tailscale. View task status, continue conversations, and start new tasks remotely.

> This project is currently in Alpha. Use it only inside a Tailnet you trust, and never expose the Windows App directly to the public internet.

## Get started in four steps

1. Install and sign in to [Tailscale for Windows](https://tailscale.com/download/windows)
2. Install and sign in to [Tailscale for Android](https://tailscale.com/download/android)
3. Open the [v0.2.0-alpha.4 release](https://github.com/moyi888/codex-remote-android/releases/tag/v0.2.0-alpha.4), download and launch the Windows App, and install the Android APK
4. In the Android App, scan the QR code displayed by the Windows App

A Tailnet is the private network formed by devices in the same Tailscale account or organization. Once both devices are connected to the same Tailnet, the Windows App automatically discovers its Tailscale address, starts the local Bridge, and displays a one-time QR code. You do not need to enter an IP address or port, configure a Tailscale ACL, or maintain a project allowlist.

Tailscale must still be installed and signed in separately on the PC and phone. This project does not operate a public relay, so it adds no server, domain, or bandwidth costs.

## Downloads

Download from the [v0.2.0-alpha.4 release](https://github.com/moyi888/codex-remote-android/releases/tag/v0.2.0-alpha.4):

- `codex-remote-windows-*.exe`: Windows App
- `codex-remote-cli-windows-*.exe`: advanced command-line build with console output
- `codex-remote-android-*.apk`: Android App

Before a release is available, development builds can also be downloaded from the latest GitHub Actions artifacts.

## What it can do

- View every non-archived Codex task on the Windows PC, without restricting access to one project directory.
- Open existing tasks, view their status, and continue sending messages.
- Start new tasks and choose the model, reasoning effort, and a directory previously seen in Codex task history.
- Pair with a one-time QR code. Each phone receives its own device credential, which can be revoked from the Windows App.
- Encrypt credentials and queued commands with AndroidKeyStore, reconnect automatically, and send queued commands after reconnection.
- Run ordinary tasks with `approvalPolicy: never` and `dangerFullAccess`, avoiding routine file and command approval prompts.

An official Codex account login is not required. Official login, a proxy service, or custom configuration can all work as long as the current Windows environment can start the Codex `app-server`. The Windows App automatically looks for the configured Codex launch command.

## When you still need the PC

When Chrome DevTools MCP or another third-party tool requires OAuth, a CAPTCHA, browser login, or an authorization confirmation, the Android App will notify you when possible. You must still use Sunlogin, another remote desktop tool, or direct access to complete the interaction in the PC browser.

If the authorization prompt cannot be detected, Codex will normally stop, fail, or mention the missing authorization in its final response. Complete the browser authorization and then ask the task to run again from the phone.

## How to provide logs when pairing fails

The Android App includes an in-app “Diagnostic logs” screen. After a failed scan, open it and copy or share the logs. Pairing tokens, device credentials, and Authorization headers are redacted automatically; task prompts and conversation content are not recorded.

## Advanced mode

For normal use, double-click `codex-remote-windows-*.exe`. It is a tray application and does not open a console window. Use `codex-remote-cli-windows-*.exe` for debugging, custom listening addresses, or compatibility with an existing deployment:

```powershell
.\codex-remote-cli-windows-v0.2.0-alpha.4.exe serve `
  --listen 100.88.10.20:8787 `
  --advertise-url http://100.88.10.20:8787 `
  --projects .\projects.json `
  --codex-command codex
```

- `serve`: explicitly start the Bridge service.
- `--listen`: set a custom listening address.
- `--advertise-url`: set the client-reachable origin included in pairing invitations.
- `--projects`: legacy explicit project registry; the desktop flow does not need it.
- `--codex-command`: select a custom Codex launch command.

Run `codex-remote-cli-windows-v0.2.0-alpha.4.exe version` to print the version. Adjust the version in the filename to match the downloaded release. Even in advanced mode, expose the listening address only to your Tailnet.

## Verify from source

The project toolchain is Go 1.24.2, Gradle Wrapper 8.11.1, AGP 8.10.1, Kotlin 2.1.21, and Java 21 in CI.

```powershell
cd bridge
mise exec go@1.24.2 -- go test ./...
mise exec go@1.24.2 -- go vet ./...
mise exec go@1.24.2 -- go build -o codex-remote.exe ./cmd/codex-remote
```

Android uses the Gradle Wrapper included in the repository:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug --stacktrace
```

## Current limitations

- The phone task detail screen currently shows a task summary and supports continuing the conversation; the complete message-history API is still in progress.
- The APK is currently an installable debug-signed build. Production signing and Play distribution are not configured.
- Third-party login and browser authorization must still be completed on the PC through Sunlogin or another remote desktop tool.

Protocol and security behavior are documented in the source and tests. Internal design materials remain local under `docs/` and are intentionally excluded from Git history.

## License

[MIT](LICENSE)
