//go:build windows

package desktop

import (
	"bytes"
	"context"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"syscall"
	"time"

	"github.com/lxn/walk"
	. "github.com/lxn/walk/declarative"
	"github.com/moyi888/codex-remote-android/bridge/internal/codex"
)

const tailscaleDownloadURL = "https://tailscale.com/download/windows"
const createNoWindow uint32 = 0x08000000

type execCommandRunner struct{}

func (execCommandRunner) Run(ctx context.Context, command string, args ...string) ([]byte, error) {
	return runHiddenCommand(ctx, command, args...).Output()
}

func configureHiddenCommand(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
}

func runHiddenCommand(ctx context.Context, command string, args ...string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, command, args...)
	configureHiddenCommand(cmd)
	return cmd
}

type wallClock struct{}

func (wallClock) Now() time.Time                             { return time.Now() }
func (wallClock) After(delay time.Duration) <-chan time.Time { return time.After(delay) }

func RunApplication(version string) error {
	localAppData := os.Getenv("LOCALAPPDATA")
	if localAppData == "" {
		return fmt.Errorf("LOCALAPPDATA is unavailable")
	}
	runner := execCommandRunner{}
	probe := NewSystemEnvironmentProbe(
		func(ctx context.Context) (TailscaleStatus, error) {
			return DiscoverTailscale(ctx, exec.LookPath, os.Getenv("ProgramFiles"), runner)
		},
		func(ctx context.Context, explicit string) (string, error) {
			return DiscoverCodex(ctx, CodexDiscoveryOptions{
				Explicit: explicit, LocalAppData: localAppData, AppData: os.Getenv("APPDATA"),
				ProgramFiles: os.Getenv("ProgramFiles"),
			}, exec.LookPath, AppServerCodexProbe{
				Start: func(ctx context.Context, path string, args, environment []string) (ProbeTransport, error) {
					return codex.StartRPCProcess(ctx, path, args, environment)
				},
				Version: version,
			})
		},
	)
	factory := NewHTTPBridgeFactory(func(ctx context.Context, path string) (ManagedRPCTransport, error) {
		return codex.StartRPCProcess(ctx, path, []string{"app-server"}, nil)
	}, version, time.Now)
	autostart := RegistryAutostartStore{}

	var (
		mainWindow      *walk.MainWindow
		titleLabel      *walk.Label
		description     *walk.Label
		countdown       *walk.Label
		qrView          *walk.ImageView
		primaryButton   *walk.PushButton
		chooseButton    *walk.PushButton
		deviceList      *walk.ListBox
		revokeButton    *walk.PushButton
		autostartCheck  *walk.CheckBox
		autostartAction *walk.Action
		controller      *Controller
		currentAction   ViewAction
		currentIDs      []string
		currentBitmap   *walk.Bitmap
		currentQRBytes  []byte
		exiting         bool
		uiMu            sync.Mutex
	)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	actions := newBackgroundActions(ctx)

	primaryClicked := func() {
		if controller == nil {
			return
		}
		switch currentAction {
		case InstallTailscale:
			_ = openExternal(tailscaleDownloadURL)
		case OpenTailscale:
			_ = openExternal("tailscale:")
		case ChooseCodex:
			chooseCodexExecutable(mainWindow, probe, controller, actions)
		case RefreshInvitation:
			actions.Go(func(actionCtx context.Context) { _ = controller.RefreshInvitation(actionCtx) })
		case AddDevice:
			actions.Go(func(actionCtx context.Context) { _ = controller.BeginPairing(actionCtx) })
		default:
			actions.Go(func(actionCtx context.Context) { _ = controller.Refresh(actionCtx) })
		}
	}
	chooseClicked := func() { chooseCodexExecutable(mainWindow, probe, controller, actions) }
	revokeClicked := func() {
		index := deviceList.CurrentIndex()
		if index < 0 || index >= len(currentIDs) {
			return
		}
		deviceID := currentIDs[index]
		actions.Go(func(actionCtx context.Context) { _ = controller.RevokeDevice(actionCtx, deviceID) })
	}
	autostartChanged := func() {
		if autostartCheck != nil {
			enabled := autostartCheck.Checked()
			_ = autostart.SetEnabled(enabled)
			if autostartAction != nil && autostartAction.Checked() != enabled {
				autostartAction.SetChecked(enabled)
			}
		}
	}

	window := MainWindow{
		AssignTo: &mainWindow,
		Title:    "Codex Remote",
		Size:     Size{Width: 460, Height: 680},
		MinSize:  Size{Width: 420, Height: 600},
		Layout:   VBox{Margins: Margins{Left: 20, Top: 20, Right: 20, Bottom: 20}, Spacing: 10},
		Children: []Widget{
			Label{AssignTo: &titleLabel, Text: "正在检测环境…"},
			Label{AssignTo: &description, Text: "Codex Remote 正在检查 Tailscale 和 Codex。"},
			Label{AssignTo: &countdown},
			ImageView{AssignTo: &qrView, MinSize: Size{Width: 320, Height: 320}, Mode: ImageViewModeZoom},
			PushButton{AssignTo: &primaryButton, Text: "重新检测", OnClicked: primaryClicked},
			PushButton{AssignTo: &chooseButton, Text: "选择 Codex 程序", OnClicked: chooseClicked},
			Label{Text: "已配对设备"},
			ListBox{AssignTo: &deviceList, Model: []string{}},
			PushButton{AssignTo: &revokeButton, Text: "移除所选设备", OnClicked: revokeClicked},
			CheckBox{AssignTo: &autostartCheck, Text: "登录 Windows 时自动启动", OnCheckedChanged: autostartChanged},
		},
	}
	if err := window.Create(); err != nil {
		return err
	}
	defer mainWindow.Dispose()
	mainWindow.SetIcon(walk.IconApplication())
	brandIcon, iconErr := newBrandIcon()
	if iconErr == nil {
		_ = mainWindow.SetIcon(brandIcon)
		defer brandIcon.Dispose()
	}

	applyState := func(state DesktopState) {
		mainWindow.Synchronize(func() {
			uiMu.Lock()
			defer uiMu.Unlock()
			view := Present(state)
			currentAction = view.PrimaryAction
			_ = titleLabel.SetText(view.Title)
			_ = description.SetText(view.Description)
			_ = primaryButton.SetText(actionText(view.PrimaryAction))
			qrView.SetVisible(view.ShowQR)
			deviceList.SetVisible(view.ShowDevices)
			revokeButton.SetVisible(view.ShowDevices)
			if view.ShowQR && len(state.InvitationPNG) > 0 && !bytes.Equal(currentQRBytes, state.InvitationPNG) {
				if image, err := png.Decode(bytes.NewReader(state.InvitationPNG)); err == nil {
					if bitmap, err := walk.NewBitmapFromImage(image); err == nil {
						old := currentBitmap
						currentBitmap = bitmap
						_ = qrView.SetImage(bitmap)
						currentQRBytes = append(currentQRBytes[:0], state.InvitationPNG...)
						if old != nil {
							old.Dispose()
						}
					}
				}
			}
			currentIDs = currentIDs[:0]
			names := make([]string, 0, len(state.Devices))
			for _, device := range state.Devices {
				if device.Revoked {
					continue
				}
				currentIDs = append(currentIDs, device.ID)
				names = append(names, deviceLabel(device))
			}
			_ = deviceList.SetModel(names)
			updateCountdown(countdown, state.ExpiresAt)
		})
	}
	controller = NewController(ControllerOptions{
		Environment: probe, Bridges: factory, Autostart: autostart,
		Clock: wallClock{}, Notifier: viewNotifierFunc(applyState), LocalAppData: localAppData,
	})

	mainWindow.Closing().Attach(func(canceled *bool, _ walk.CloseReason) {
		if !exiting {
			*canceled = true
			mainWindow.Hide()
		}
	})

	notifyIcon, err := walk.NewNotifyIcon(mainWindow)
	if err != nil {
		return err
	}
	defer notifyIcon.Dispose()
	if brandIcon, err := newBrandIcon(); err == nil {
		_ = notifyIcon.SetIcon(brandIcon)
		defer brandIcon.Dispose()
	}
	_ = notifyIcon.SetToolTip("Codex Remote")
	showAction := walk.NewAction()
	_ = showAction.SetText("显示 Codex Remote")
	showAction.Triggered().Attach(func() { mainWindow.Show(); _ = mainWindow.SetFocus() })
	_ = notifyIcon.ContextMenu().Actions().Add(showAction)
	autostartAction = walk.NewAction()
	_ = autostartAction.SetText("登录 Windows 时自动启动")
	autostartAction.SetCheckable(true)
	autostartAction.SetChecked(autostartCheck.Checked())
	autostartAction.Triggered().Attach(func() {
		enabled := autostartAction.Checked()
		autostartCheck.SetChecked(enabled)
	})
	_ = notifyIcon.ContextMenu().Actions().Add(autostartAction)
	exitAction := walk.NewAction()
	_ = exitAction.SetText("退出")
	exitAction.Triggered().Attach(func() {
		exiting = true
		cancel()
		walk.App().Exit(0)
	})
	_ = notifyIcon.ContextMenu().Actions().Add(exitAction)
	notifyIcon.MouseDown().Attach(func(_, _ int, button walk.MouseButton) {
		if button == walk.LeftButton {
			mainWindow.Show()
		}
	})
	if err := notifyIcon.SetVisible(true); err != nil {
		return err
	}
	_ = ensureDefaultAutostart(autostart)
	if enabled, err := autostart.Enabled(); err == nil {
		autostartCheck.SetChecked(enabled)
		autostartAction.SetChecked(enabled)
	}

	controllerDone := make(chan struct{})
	go func() {
		defer close(controllerDone)
		if err := controller.Run(ctx); err != nil {
			applyState(DesktopState{Kind: CodexFailed})
		}
	}()
	tickerDone := make(chan struct{})
	go func() {
		defer close(tickerDone)
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				state := controller.State()
				mainWindow.Synchronize(func() { updateCountdown(countdown, state.ExpiresAt) })
			}
		}
	}()
	mainWindow.Show()
	mainWindow.Run()
	exiting = true
	cancel()
	actions.CloseAndWait()
	_ = controller.Shutdown()
	<-controllerDone
	<-tickerDone
	if currentBitmap != nil {
		_ = qrView.SetImage(nil)
		currentBitmap.Dispose()
	}
	return nil
}

// newBrandIcon draws the same compact CR mark used by the Android launcher.
// Drawing it at runtime keeps the Windows GUI and tray icon self-contained,
// without a second binary resource that can drift from the mobile artwork.
func newBrandIcon() (*walk.Icon, error) {
	const size = 64
	img := image.NewRGBA(image.Rect(0, 0, size, size))
	draw.Draw(img, img.Bounds(), &image.Uniform{C: color.RGBA{R: 27, G: 27, B: 31, A: 255}}, image.Point{}, draw.Src)
	blue := &image.Uniform{C: color.RGBA{R: 120, G: 169, B: 255, A: 255}}
	white := &image.Uniform{C: color.White}
	// Block-letter C.
	draw.Draw(img, image.Rect(12, 13, 20, 51), blue, image.Point{}, draw.Src)
	draw.Draw(img, image.Rect(20, 13, 31, 21), blue, image.Point{}, draw.Src)
	draw.Draw(img, image.Rect(20, 43, 31, 51), blue, image.Point{}, draw.Src)
	// Block-letter R.
	draw.Draw(img, image.Rect(34, 13, 42, 51), blue, image.Point{}, draw.Src)
	draw.Draw(img, image.Rect(42, 13, 53, 21), blue, image.Point{}, draw.Src)
	draw.Draw(img, image.Rect(42, 28, 53, 36), blue, image.Point{}, draw.Src)
	draw.Draw(img, image.Rect(50, 20, 58, 30), blue, image.Point{}, draw.Src)
	draw.Draw(img, image.Rect(47, 36, 55, 43), white, image.Point{}, draw.Src)
	draw.Draw(img, image.Rect(53, 43, 59, 51), white, image.Point{}, draw.Src)
	return walk.NewIconFromImage(img)
}

type viewNotifierFunc func(DesktopState)

func (f viewNotifierFunc) Notify(state DesktopState) { f(state) }

func actionText(action ViewAction) string {
	switch action {
	case InstallTailscale:
		return "安装 Tailscale"
	case OpenTailscale:
		return "打开 Tailscale"
	case ChooseCodex:
		return "选择 Codex 程序"
	case RefreshInvitation:
		return "刷新二维码"
	case AddDevice:
		return "添加连接设备"
	default:
		return "重新检测"
	}
}

func updateCountdown(label *walk.Label, expiresAt time.Time) {
	if expiresAt.IsZero() {
		_ = label.SetText("")
		return
	}
	remaining := time.Until(expiresAt).Round(time.Second)
	if remaining < 0 {
		remaining = 0
	}
	_ = label.SetText("二维码剩余有效时间：" + remaining.String())
}

func chooseCodexExecutable(owner walk.Form, probe *SystemEnvironmentProbe, controller *Controller, actions *backgroundActions) {
	dialog := walk.FileDialog{
		Title: "选择 Codex 程序", Filter: "可执行文件 (*.exe;*.cmd)|*.exe;*.cmd|所有文件 (*.*)|*.*",
		InitialDirPath: filepath.Join(os.Getenv("LOCALAPPDATA"), "Programs"),
	}
	accepted, err := dialog.ShowOpen(owner)
	if err != nil || !accepted {
		return
	}
	probe.SetCodexPath(dialog.FilePath)
	actions.Go(func(ctx context.Context) { _ = controller.Refresh(ctx) })
}

func openExternal(target string) error {
	command := exec.Command("rundll32.exe", "url.dll,FileProtocolHandler", target)
	return command.Start()
}
