package desktop

import (
	"context"
	"errors"
	"net/netip"
	"path/filepath"
	"slices"
	"testing"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/auth"
	"github.com/moyi888/codex-remote-android/bridge/internal/store"
)

func TestControllerMapsEnvironmentStates(t *testing.T) {
	tests := []struct {
		name string
		env  Environment
		want StateKind
	}{
		{name: "Tailscale 未安装", env: Environment{TailscaleError: ErrTailscaleMissing}, want: NeedsTailscaleInstall},
		{name: "Tailscale 未连接", env: Environment{TailscaleError: ErrTailscaleDisconnected}, want: NeedsTailscaleConnection},
		{name: "未找到 Codex", env: Environment{Tailscale: tailscale("100.88.10.20"), CodexError: ErrCodexUnavailable}, want: NeedsCodex},
		{name: "Codex 探测失败", env: Environment{Tailscale: tailscale("100.88.10.20"), CodexError: ErrCodexProbe}, want: CodexFailed},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			controller := newTestController(&fakeEnvironmentProbe{environment: test.env}, &fakeBridgeFactory{}, newFakeClock())
			if err := controller.Refresh(context.Background()); err != nil {
				t.Fatal(err)
			}
			if got := controller.State().Kind; got != test.want {
				t.Fatalf("状态 = %q, want %q", got, test.want)
			}
		})
	}
}

func TestControllerWaitsForPairAndFallsBackToDynamicPort(t *testing.T) {
	clock := newFakeClock()
	bridge := &fakeBridge{
		address: "100.88.10.20:49152",
		invitations: []auth.PairingInvitation{{
			URL:       "codex-remote://pair?token=one",
			ExpiresAt: clock.Now().Add(5 * time.Minute),
		}},
	}
	factory := &fakeBridgeFactory{
		startErrors: []error{ErrAddressInUse, nil},
		bridges:     []Bridge{bridge},
	}
	controller := newTestController(readyProbe("100.88.10.20"), factory, clock)

	if err := controller.Refresh(context.Background()); err != nil {
		t.Fatal(err)
	}
	state := controller.State()
	if state.Kind != WaitingForPair || len(state.InvitationPNG) == 0 || state.ExpiresAt.IsZero() {
		t.Fatalf("状态 = %+v", state)
	}
	wantAddresses := []string{"100.88.10.20:8787", "100.88.10.20:0"}
	if got := startAddresses(factory.starts); !slices.Equal(got, wantAddresses) {
		t.Fatalf("监听尝试 = %v, want %v", got, wantAddresses)
	}
	if got := state.Address; got != bridge.address {
		t.Fatalf("地址 = %q", got)
	}
}

func TestControllerBecomesConnectedAndReturnsImmutableSnapshots(t *testing.T) {
	clock := newFakeClock()
	lastSeen := clock.Now()
	bridge := &fakeBridge{
		address: "100.88.10.20:8787",
		invitations: []auth.PairingInvitation{{
			URL:       "codex-remote://pair?token=one",
			ExpiresAt: clock.Now().Add(5 * time.Minute),
		}},
		devices: []store.DeviceSummary{{ID: "phone", Name: "手机", LastSeenAt: &lastSeen}},
	}
	controller := newTestController(readyProbe("100.88.10.20"), &fakeBridgeFactory{bridges: []Bridge{bridge}}, clock)
	if err := controller.Refresh(context.Background()); err != nil {
		t.Fatal(err)
	}

	first := controller.State()
	if first.Kind != Connected || len(first.Devices) != 1 {
		t.Fatalf("状态 = %+v", first)
	}
	first.InvitationPNG[0] ^= 0xff
	first.Devices[0].Name = "已篡改"
	second := controller.State()
	if slices.Equal(first.InvitationPNG, second.InvitationPNG) || second.Devices[0].Name != "手机" {
		t.Fatal("State 必须返回深拷贝快照")
	}
}

func TestControllerRotatesExpiredInvitation(t *testing.T) {
	clock := newFakeClock()
	bridge := &fakeBridge{
		address: "100.88.10.20:8787",
		invitations: []auth.PairingInvitation{
			{URL: "codex-remote://pair?token=one", ExpiresAt: clock.Now().Add(time.Minute)},
			{URL: "codex-remote://pair?token=two", ExpiresAt: clock.Now().Add(7 * time.Minute)},
		},
	}
	controller := newTestController(readyProbe("100.88.10.20"), &fakeBridgeFactory{bridges: []Bridge{bridge}}, clock)
	if err := controller.Refresh(context.Background()); err != nil {
		t.Fatal(err)
	}
	firstPNG := controller.State().InvitationPNG
	clock.Advance(2 * time.Minute)
	if err := controller.Refresh(context.Background()); err != nil {
		t.Fatal(err)
	}
	state := controller.State()
	if bridge.issueCalls != 2 || slices.Equal(firstPNG, state.InvitationPNG) {
		t.Fatalf("邀请签发次数 = %d", bridge.issueCalls)
	}
}

func TestControllerRestartsBridgeWhenTailnetAddressChanges(t *testing.T) {
	clock := newFakeClock()
	first := newFakeBridge("100.88.10.20:8787", clock.Now())
	second := newFakeBridge("100.99.1.7:8787", clock.Now())
	probe := &fakeEnvironmentProbe{environments: []Environment{
		readyEnvironment("100.88.10.20"),
		readyEnvironment("100.99.1.7"),
	}}
	factory := &fakeBridgeFactory{bridges: []Bridge{first, second}}
	controller := newTestController(probe, factory, clock)
	if err := controller.Refresh(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := controller.Refresh(context.Background()); err != nil {
		t.Fatal(err)
	}
	if first.closeCalls != 1 || controller.State().Address != second.address {
		t.Fatalf("旧 Bridge 关闭次数 = %d, 新地址 = %q", first.closeCalls, controller.State().Address)
	}
}

func TestControllerShutdownClosesBridgeExactlyOnce(t *testing.T) {
	clock := newFakeClock()
	bridge := newFakeBridge("100.88.10.20:8787", clock.Now())
	controller := newTestController(readyProbe("100.88.10.20"), &fakeBridgeFactory{bridges: []Bridge{bridge}}, clock)
	if err := controller.Refresh(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := controller.Shutdown(); err != nil {
		t.Fatal(err)
	}
	if err := controller.Shutdown(); err != nil {
		t.Fatal(err)
	}
	if bridge.closeCalls != 1 {
		t.Fatalf("关闭次数 = %d", bridge.closeCalls)
	}
}

func TestControllerNotifierCanReadStateWithoutDeadlock(t *testing.T) {
	clock := newFakeClock()
	bridge := newFakeBridge("100.88.10.20:8787", clock.Now())
	var controller *Controller
	notified := make(chan struct{}, 1)
	controller = NewController(ControllerOptions{
		Environment: readyProbe("100.88.10.20"),
		Bridges:     &fakeBridgeFactory{bridges: []Bridge{bridge}},
		Autostart:   fakeAutostart{},
		Clock:       clock,
		Notifier: notifierFunc(func(DesktopState) {
			_ = controller.State()
			notified <- struct{}{}
		}),
		LocalAppData: `C:\Users\test\AppData\Local`,
	})
	refreshDone := make(chan error, 1)
	go func() { refreshDone <- controller.Refresh(context.Background()) }()
	select {
	case err := <-refreshDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Notifier 读取 State 时发生死锁")
	}
	select {
	case <-notified:
	default:
		t.Fatal("Notifier 未收到状态")
	}
}

func TestControllerRunBacksOffBeforeRestartingFailedBridge(t *testing.T) {
	clock := newFakeClock()
	first := newFakeBridge("100.88.10.20:8787", clock.Now())
	second := newFakeBridge("100.88.10.20:8787", clock.Now())
	factory := &fakeBridgeFactory{bridges: []Bridge{first, second}}
	notifier := fakeNotifier{states: make(chan DesktopState, 8)}
	controller := NewController(ControllerOptions{
		Environment:  readyProbe("100.88.10.20"),
		Bridges:      factory,
		Autostart:    fakeAutostart{},
		Clock:        clock,
		Notifier:     notifier,
		LocalAppData: `C:\Users\test\AppData\Local`,
	})
	ctx, cancel := context.WithCancel(context.Background())
	runResult := make(chan error, 1)
	go func() { runResult <- controller.Run(ctx) }()

	waitForState(t, notifier.states, WaitingForPair)
	first.done <- errors.New("app-server exited")
	waitForState(t, notifier.states, CodexFailed)
	restartTimer := waitForTimer(t, clock.afterRequests, restartBridgeBackoff)
	if len(factory.starts) != 1 {
		t.Fatalf("退避期间启动次数 = %d", len(factory.starts))
	}
	restartTimer <- clock.Now()
	waitForState(t, notifier.states, WaitingForPair)
	if len(factory.starts) != 2 || first.closeCalls != 1 {
		t.Fatalf("启动次数 = %d, 首个关闭次数 = %d", len(factory.starts), first.closeCalls)
	}

	cancel()
	select {
	case err := <-runResult:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Run 未在 context 取消后退出")
	}
	if second.closeCalls != 1 {
		t.Fatalf("第二个 Bridge 关闭次数 = %d", second.closeCalls)
	}
}

func TestControllerRunIgnoresExitFromReplacedBridge(t *testing.T) {
	clock := newFakeClock()
	first := newFakeBridge("100.88.10.20:8787", clock.Now())
	second := newFakeBridge("100.99.1.7:8787", clock.Now())
	probe := &fakeEnvironmentProbe{environments: []Environment{
		readyEnvironment("100.88.10.20"),
		readyEnvironment("100.99.1.7"),
	}}
	notifier := fakeNotifier{states: make(chan DesktopState, 8)}
	controller := NewController(ControllerOptions{
		Environment:  probe,
		Bridges:      &fakeBridgeFactory{bridges: []Bridge{first, second}},
		Autostart:    fakeAutostart{},
		Clock:        clock,
		Notifier:     notifier,
		LocalAppData: `C:\Users\test\AppData\Local`,
	})
	ctx, cancel := context.WithCancel(context.Background())
	runResult := make(chan error, 1)
	go func() { runResult <- controller.Run(ctx) }()
	waitForState(t, notifier.states, WaitingForPair)
	_ = waitForTimer(t, clock.afterRequests, controllerRefreshInterval)

	if err := controller.Refresh(context.Background()); err != nil {
		t.Fatal(err)
	}
	waitForState(t, notifier.states, WaitingForPair)
	first.done <- errors.New("old app-server exited late")
	_ = waitForTimer(t, clock.afterRequests, controllerRefreshInterval)
	if second.closeCalls != 0 || controller.State().Address != second.address {
		t.Fatalf("新 Bridge 被旧退出信号影响: close=%d state=%+v", second.closeCalls, controller.State())
	}

	cancel()
	select {
	case err := <-runResult:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Run 未退出")
	}
}

func TestControllerRunRefreshesDeviceStateBeforeInvitationExpires(t *testing.T) {
	clock := newFakeClock()
	bridge := newFakeBridge("100.88.10.20:8787", clock.Now())
	bridge.deviceLists = [][]store.DeviceSummary{
		{},
		{{ID: "phone", Name: "手机"}},
	}
	notifier := fakeNotifier{states: make(chan DesktopState, 8)}
	controller := NewController(ControllerOptions{
		Environment:  readyProbe("100.88.10.20"),
		Bridges:      &fakeBridgeFactory{bridges: []Bridge{bridge}},
		Autostart:    fakeAutostart{},
		Clock:        clock,
		Notifier:     notifier,
		LocalAppData: `C:\Users\test\AppData\Local`,
	})
	ctx, cancel := context.WithCancel(context.Background())
	runResult := make(chan error, 1)
	go func() { runResult <- controller.Run(ctx) }()
	waitForState(t, notifier.states, WaitingForPair)
	refreshTimer := waitForTimer(t, clock.afterRequests, 5*time.Second)
	refreshTimer <- clock.Now()
	waitForState(t, notifier.states, Connected)
	if bridge.issueCalls != 1 {
		t.Fatalf("未过期邀请签发次数 = %d", bridge.issueCalls)
	}
	cancel()
	<-runResult
}

func TestControllerRunStopsAfterBoundedProgressiveRestarts(t *testing.T) {
	clock := newFakeClock()
	bridges := []*fakeBridge{
		newFakeBridge("100.88.10.20:8787", clock.Now()),
		newFakeBridge("100.88.10.20:8787", clock.Now()),
		newFakeBridge("100.88.10.20:8787", clock.Now()),
	}
	factory := &fakeBridgeFactory{bridges: []Bridge{bridges[0], bridges[1], bridges[2]}}
	notifier := fakeNotifier{states: make(chan DesktopState, 16)}
	controller := NewController(ControllerOptions{
		Environment: readyProbe("100.88.10.20"), Bridges: factory,
		Autostart: fakeAutostart{}, Clock: clock, Notifier: notifier,
		LocalAppData: `C:\Users\test\AppData\Local`,
	})
	ctx, cancel := context.WithCancel(context.Background())
	runResult := make(chan error, 1)
	go func() { runResult <- controller.Run(ctx) }()
	waitForState(t, notifier.states, WaitingForPair)

	for index, delay := range []time.Duration{2 * time.Second, 4 * time.Second} {
		bridges[index].done <- errors.New("app-server exited")
		waitForState(t, notifier.states, CodexFailed)
		timer := waitForTimer(t, clock.afterRequests, delay)
		timer <- clock.Now()
		waitForState(t, notifier.states, WaitingForPair)
	}
	bridges[2].done <- errors.New("app-server exited")
	waitForState(t, notifier.states, CodexFailed)
	time.Sleep(50 * time.Millisecond)
	if len(factory.starts) != 3 {
		t.Fatalf("连续失败后的启动次数 = %d", len(factory.starts))
	}

	cancel()
	select {
	case <-runResult:
	case <-time.After(time.Second):
		t.Fatal("Run 未退出")
	}
}

func TestControllerRunRecoversFromInvitationFailure(t *testing.T) {
	clock := newFakeClock()
	bridge := &fakeBridge{
		address: "100.88.10.20:8787",
		invitations: []auth.PairingInvitation{
			{},
			{URL: "codex-remote://pair?token=recovered", ExpiresAt: clock.Now().Add(5 * time.Minute)},
		},
		issueErrors: []error{errors.New("temporary database failure"), nil},
		done:        make(chan error, 1),
	}
	notifier := fakeNotifier{states: make(chan DesktopState, 8)}
	controller := NewController(ControllerOptions{
		Environment: readyProbe("100.88.10.20"),
		Bridges:     &fakeBridgeFactory{bridges: []Bridge{bridge}},
		Autostart:   fakeAutostart{}, Clock: clock, Notifier: notifier,
		LocalAppData: `C:\Users\test\AppData\Local`,
	})
	ctx, cancel := context.WithCancel(context.Background())
	runResult := make(chan error, 1)
	go func() { runResult <- controller.Run(ctx) }()
	waitForState(t, notifier.states, CodexFailed)
	select {
	case err := <-runResult:
		t.Fatalf("临时错误导致 Run 退出: %v", err)
	default:
	}
	timer := waitForTimer(t, clock.afterRequests, controllerRefreshInterval)
	timer <- clock.Now()
	waitForState(t, notifier.states, WaitingForPair)
	cancel()
	<-runResult
}

func newTestController(probe EnvironmentProbe, factory BridgeFactory, clock Clock) *Controller {
	return NewController(ControllerOptions{
		Environment:  probe,
		Bridges:      factory,
		Autostart:    fakeAutostart{},
		Clock:        clock,
		Notifier:     fakeNotifier{},
		LocalAppData: `C:\Users\test\AppData\Local`,
	})
}

func readyProbe(ip string) EnvironmentProbe {
	return &fakeEnvironmentProbe{environment: readyEnvironment(ip)}
}

func readyEnvironment(ip string) Environment {
	return Environment{Tailscale: tailscale(ip), CodexPath: `C:\Program Files\Codex\codex.exe`}
}

func tailscale(ip string) TailscaleStatus {
	return TailscaleStatus{IP: netip.MustParseAddr(ip), Executable: `C:\Program Files\Tailscale\tailscale.exe`}
}

type fakeEnvironmentProbe struct {
	environment  Environment
	environments []Environment
	calls        int
}

func (f *fakeEnvironmentProbe) Probe(context.Context) Environment {
	if len(f.environments) == 0 {
		return f.environment
	}
	index := f.calls
	if index >= len(f.environments) {
		index = len(f.environments) - 1
	}
	f.calls++
	return f.environments[index]
}

type fakeBridgeFactory struct {
	starts      []BridgeConfig
	startErrors []error
	bridges     []Bridge
	calls       int
}

func (f *fakeBridgeFactory) Start(_ context.Context, config BridgeConfig) (Bridge, error) {
	f.starts = append(f.starts, config)
	index := f.calls
	f.calls++
	if index < len(f.startErrors) && f.startErrors[index] != nil {
		return nil, f.startErrors[index]
	}
	bridgeIndex := index
	for i := 0; i <= index && i < len(f.startErrors); i++ {
		if f.startErrors[i] != nil {
			bridgeIndex--
		}
	}
	if bridgeIndex < 0 || bridgeIndex >= len(f.bridges) {
		return nil, errors.New("fake bridge is missing")
	}
	return f.bridges[bridgeIndex], nil
}

type fakeBridge struct {
	address     string
	invitations []auth.PairingInvitation
	issueErrors []error
	devices     []store.DeviceSummary
	deviceLists [][]store.DeviceSummary
	listCalls   int
	issueCalls  int
	closeCalls  int
	done        chan error
}

func newFakeBridge(address string, now time.Time) *fakeBridge {
	return &fakeBridge{address: address, invitations: []auth.PairingInvitation{{
		URL: "codex-remote://pair?token=one", ExpiresAt: now.Add(5 * time.Minute),
	}}, done: make(chan error, 1)}
}

func (f *fakeBridge) Address() string { return f.address }

func (f *fakeBridge) IssueInvitation(_ string, _ time.Duration) (auth.PairingInvitation, error) {
	index := f.issueCalls
	f.issueCalls++
	if index < len(f.issueErrors) && f.issueErrors[index] != nil {
		return auth.PairingInvitation{}, f.issueErrors[index]
	}
	if index >= len(f.invitations) {
		return auth.PairingInvitation{}, errors.New("fake invitation is missing")
	}
	return f.invitations[index], nil
}

func (f *fakeBridge) ListDevices() ([]store.DeviceSummary, error) {
	if len(f.deviceLists) > 0 {
		index := f.listCalls
		if index >= len(f.deviceLists) {
			index = len(f.deviceLists) - 1
		}
		f.listCalls++
		return slices.Clone(f.deviceLists[index]), nil
	}
	return slices.Clone(f.devices), nil
}

func (f *fakeBridge) Close() error {
	f.closeCalls++
	return nil
}

func (f *fakeBridge) Done() <-chan error { return f.done }

type fakeAutostart struct{}

func (fakeAutostart) Enabled() (bool, error) { return false, nil }
func (fakeAutostart) SetEnabled(bool) error  { return nil }

type fakeNotifier struct{ states chan DesktopState }

func (f fakeNotifier) Notify(state DesktopState) {
	if f.states != nil {
		f.states <- state
	}
}

type notifierFunc func(DesktopState)

func (f notifierFunc) Notify(state DesktopState) { f(state) }

type timerRequest struct {
	duration time.Duration
	channel  chan time.Time
}

type fakeClock struct {
	now           time.Time
	afterRequests chan timerRequest
}

func newFakeClock() *fakeClock {
	return &fakeClock{
		now:           time.Date(2026, 8, 26, 12, 0, 0, 0, time.UTC),
		afterRequests: make(chan timerRequest, 8),
	}
}

func (f *fakeClock) Now() time.Time { return f.now }
func (f *fakeClock) After(d time.Duration) <-chan time.Time {
	channel := make(chan time.Time, 1)
	f.afterRequests <- timerRequest{duration: d, channel: channel}
	return channel
}
func (f *fakeClock) Advance(d time.Duration) { f.now = f.now.Add(d) }

func waitForState(t *testing.T, states <-chan DesktopState, want StateKind) DesktopState {
	t.Helper()
	deadline := time.After(time.Second)
	for {
		select {
		case state := <-states:
			if state.Kind == want {
				return state
			}
		case <-deadline:
			t.Fatalf("未收到状态 %q", want)
		}
	}
}

func waitForTimer(t *testing.T, requests <-chan timerRequest, want time.Duration) chan time.Time {
	t.Helper()
	deadline := time.After(time.Second)
	for {
		select {
		case request := <-requests:
			if request.duration == want {
				return request.channel
			}
		case <-deadline:
			t.Fatalf("未收到计时器 %s", want)
		}
	}
}

func startAddresses(configs []BridgeConfig) []string {
	result := make([]string, 0, len(configs))
	for _, config := range configs {
		result = append(result, config.ListenAddress)
		if want := filepath.Join(`C:\Users\test\AppData\Local`, "CodexRemote", "bridge.db"); config.DatabasePath != want {
			panic("unexpected database path: " + config.DatabasePath)
		}
	}
	return result
}
