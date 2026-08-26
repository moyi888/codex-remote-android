package desktop

import "github.com/moyi888/codex-remote-android/bridge/internal/store"

type ViewAction string

const (
	InstallTailscale  ViewAction = "install_tailscale"
	OpenTailscale     ViewAction = "open_tailscale"
	ChooseCodex       ViewAction = "choose_codex"
	RetryDetection    ViewAction = "retry_detection"
	RefreshInvitation ViewAction = "refresh_invitation"
)

type DesktopView struct {
	Title         string
	Description   string
	ShowQR        bool
	ShowDevices   bool
	PrimaryAction ViewAction
}

func Present(state DesktopState) DesktopView {
	switch state.Kind {
	case NeedsTailscaleInstall:
		return DesktopView{
			Title: "需要安装 Tailscale", Description: "请先安装 Tailscale，然后重新检测。",
			PrimaryAction: InstallTailscale,
		}
	case NeedsTailscaleConnection:
		return DesktopView{
			Title: "需要连接 Tailscale", Description: "请打开 Tailscale 并连接到你的网络。",
			PrimaryAction: OpenTailscale,
		}
	case NeedsCodex:
		return DesktopView{
			Title: "未检测到 Codex", Description: "请选择能够启动 app-server 的 Codex 程序。",
			PrimaryAction: ChooseCodex,
		}
	case CodexFailed:
		return DesktopView{
			Title: "Codex 启动失败", Description: "请检查 Codex 配置后重试。",
			PrimaryAction: RetryDetection,
		}
	case Connected:
		return DesktopView{
			Title: "已连接", Description: "手机已配对，可以远程查看和继续任务。",
			ShowQR: true, ShowDevices: true, PrimaryAction: RefreshInvitation,
		}
	default:
		return DesktopView{
			Title: "等待手机扫码", Description: "请使用 Android App 扫描二维码完成配对。",
			ShowQR: true, PrimaryAction: RefreshInvitation,
		}
	}
}

func deviceLabel(device store.DeviceSummary) string {
	if device.LastSeenAt == nil {
		return device.Name + " · 尚未连接"
	}
	return device.Name + " · 最近连接 " + device.LastSeenAt.Local().Format("2006-01-02 15:04")
}
