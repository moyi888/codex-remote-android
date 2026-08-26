package desktop

import (
	"testing"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/store"
)

func TestPresentationMapsDesktopStates(t *testing.T) {
	tests := []struct {
		state       DesktopState
		title       string
		showQR      bool
		primary     ViewAction
		showDevices bool
	}{
		{state: DesktopState{Kind: NeedsTailscaleInstall}, title: "需要安装 Tailscale", primary: InstallTailscale},
		{state: DesktopState{Kind: NeedsTailscaleConnection}, title: "需要连接 Tailscale", primary: OpenTailscale},
		{state: DesktopState{Kind: NeedsCodex}, title: "未检测到 Codex", primary: ChooseCodex},
		{state: DesktopState{Kind: CodexFailed}, title: "Codex 启动失败", primary: RetryDetection},
		{state: DesktopState{Kind: WaitingForPair}, title: "等待手机扫码", showQR: true, primary: RefreshInvitation},
		{state: DesktopState{Kind: Connected}, title: "已连接", showQR: true, primary: RefreshInvitation, showDevices: true},
	}
	for _, test := range tests {
		t.Run(test.title, func(t *testing.T) {
			view := Present(test.state)
			if view.Title != test.title || view.ShowQR != test.showQR || view.PrimaryAction != test.primary || view.ShowDevices != test.showDevices {
				t.Fatalf("展示 = %+v", view)
			}
		})
	}
}

func TestWaitingPresentationNeverContainsInvitationSecret(t *testing.T) {
	view := Present(DesktopState{
		Kind:          WaitingForPair,
		Address:       "100.88.10.20:8787",
		InvitationPNG: []byte("secret-bitmap"),
	})
	if view.Description == "secret-bitmap" || view.Description == "100.88.10.20:8787" {
		t.Fatalf("展示文案泄漏内部值: %+v", view)
	}
}

func TestDeviceLabelIncludesRecentActivity(t *testing.T) {
	seenAt := time.Date(2026, 8, 26, 15, 4, 0, 0, time.Local)
	if got := deviceLabel(store.DeviceSummary{Name: "Pixel", LastSeenAt: &seenAt}); got != "Pixel · 最近连接 2026-08-26 15:04" {
		t.Fatalf("设备标签 = %q", got)
	}
	if got := deviceLabel(store.DeviceSummary{Name: "Pixel"}); got != "Pixel · 尚未连接" {
		t.Fatalf("未连接设备标签 = %q", got)
	}
}
