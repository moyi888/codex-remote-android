package desktop

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/url"
	"path/filepath"
	"sync"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/auth"
	"github.com/moyi888/codex-remote-android/bridge/internal/store"
)

const (
	defaultBridgePort          = "8787"
	pairingInvitationTTL       = 5 * time.Minute
	controllerRefreshInterval  = 5 * time.Second
	restartBridgeBackoff       = 2 * time.Second
	maxConsecutiveBridgeStarts = 3
)

var ErrAddressInUse = errors.New("bridge address is already in use")

type StateKind string

const (
	NeedsTailscaleInstall    StateKind = "needs_tailscale_install"
	NeedsTailscaleConnection StateKind = "needs_tailscale_connection"
	NeedsCodex               StateKind = "needs_codex"
	CodexFailed              StateKind = "codex_failed"
	WaitingForPair           StateKind = "waiting_for_pair"
	Connected                StateKind = "connected"
)

type Environment struct {
	Tailscale      TailscaleStatus
	TailscaleError error
	CodexPath      string
	CodexError     error
}

type EnvironmentProbe interface {
	Probe(context.Context) Environment
}

type BridgeConfig struct {
	ListenAddress string
	CodexPath     string
	DatabasePath  string
}

type Bridge interface {
	Address() string
	IssueInvitation(string, time.Duration) (auth.PairingInvitation, error)
	ListDevices() ([]store.DeviceSummary, error)
	RevokeDevice(string) error
	Done() <-chan error
	Close() error
}

type BridgeFactory interface {
	Start(context.Context, BridgeConfig) (Bridge, error)
}

type AutostartStore interface {
	Enabled() (bool, error)
	SetEnabled(bool) error
}

type Clock interface {
	Now() time.Time
	After(time.Duration) <-chan time.Time
}

type ViewNotifier interface {
	Notify(DesktopState)
}

type DesktopState struct {
	Kind          StateKind
	Address       string
	InvitationPNG []byte
	ExpiresAt     time.Time
	Devices       []store.DeviceSummary
}

type ControllerOptions struct {
	Environment  EnvironmentProbe
	Bridges      BridgeFactory
	Autostart    AutostartStore
	Clock        Clock
	Notifier     ViewNotifier
	LocalAppData string
}

type Controller struct {
	mu                  sync.RWMutex
	options             ControllerOptions
	state               DesktopState
	bridge              Bridge
	tailscaleIP         string
	codexPath           string
	invitationURL       string
	generation          uint64
	consecutiveFailures int
	autoRestartBlocked  bool
	closed              bool
}

func NewController(options ControllerOptions) *Controller {
	return &Controller{options: options}
}

func (c *Controller) Run(ctx context.Context) error {
	if err := c.refresh(ctx, false); err != nil {
		c.publishFailure()
	}
	for {
		generation, bridgeDone, invitationDelay := c.waitConditions()
		invitationExpired := c.options.Clock.After(invitationDelay)
		select {
		case <-ctx.Done():
			return c.Shutdown()
		case <-invitationExpired:
			if err := c.refresh(ctx, false); err != nil {
				c.publishFailure()
			}
		case <-bridgeDone:
			c.mu.Lock()
			if c.generation != generation {
				c.mu.Unlock()
				continue
			}
			_ = c.closeBridgeLocked()
			c.consecutiveFailures++
			if c.consecutiveFailures >= maxConsecutiveBridgeStarts {
				c.autoRestartBlocked = true
			}
			state := c.publishLocked(DesktopState{Kind: CodexFailed})
			c.mu.Unlock()
			c.notify(state)
			if c.autoRestartIsBlocked() {
				continue
			}
			select {
			case <-ctx.Done():
				return c.Shutdown()
			case <-c.options.Clock.After(c.restartDelay()):
			}
			if err := c.refresh(ctx, false); err != nil {
				c.publishFailure()
			}
		}
	}
}

func (c *Controller) publishFailure() {
	c.mu.Lock()
	state := c.publishLocked(DesktopState{Kind: CodexFailed})
	c.mu.Unlock()
	c.notify(state)
}

func (c *Controller) Refresh(ctx context.Context) error {
	return c.refresh(ctx, true)
}

func (c *Controller) RefreshInvitation(ctx context.Context) error {
	c.mu.Lock()
	c.invitationURL = ""
	c.state.ExpiresAt = time.Time{}
	c.mu.Unlock()
	return c.Refresh(ctx)
}

func (c *Controller) RevokeDevice(ctx context.Context, deviceID string) error {
	if deviceID == "" {
		return fmt.Errorf("device id is empty")
	}
	c.mu.Lock()
	if c.bridge == nil {
		c.mu.Unlock()
		return fmt.Errorf("bridge is not running")
	}
	err := c.bridge.RevokeDevice(deviceID)
	c.mu.Unlock()
	if err != nil {
		return fmt.Errorf("revoke paired device")
	}
	return c.Refresh(ctx)
}

func (c *Controller) refresh(ctx context.Context, manual bool) error {
	c.mu.Lock()
	var notification *DesktopState
	defer func() {
		c.mu.Unlock()
		if notification != nil {
			c.notify(*notification)
		}
	}()
	if c.closed {
		return nil
	}
	if manual {
		c.consecutiveFailures = 0
		c.autoRestartBlocked = false
	}

	environment := c.options.Environment.Probe(ctx)
	if environment.TailscaleError != nil {
		c.closeBridgeLocked()
		kind := NeedsTailscaleConnection
		if errors.Is(environment.TailscaleError, ErrTailscaleMissing) {
			kind = NeedsTailscaleInstall
		}
		state := c.publishLocked(DesktopState{Kind: kind})
		notification = &state
		return nil
	}
	if environment.CodexError != nil || environment.CodexPath == "" {
		c.closeBridgeLocked()
		kind := CodexFailed
		if errors.Is(environment.CodexError, ErrCodexUnavailable) || (environment.CodexError == nil && environment.CodexPath == "") {
			kind = NeedsCodex
		}
		state := c.publishLocked(DesktopState{Kind: kind})
		notification = &state
		return nil
	}

	ip := environment.Tailscale.IP.String()
	if c.bridge != nil && (c.tailscaleIP != ip || c.codexPath != environment.CodexPath) {
		c.closeBridgeLocked()
	}
	if c.bridge == nil {
		if c.autoRestartBlocked {
			state := c.publishLocked(DesktopState{Kind: CodexFailed})
			notification = &state
			return nil
		}
		if err := c.startBridgeLocked(ctx, ip, environment.CodexPath); err != nil {
			state := c.publishLocked(DesktopState{Kind: CodexFailed})
			notification = &state
			return nil
		}
	}

	now := c.options.Clock.Now()
	if c.invitationURL == "" || !c.state.ExpiresAt.After(now) {
		baseURL := (&url.URL{Scheme: "http", Host: c.bridge.Address()}).String()
		invitation, err := c.bridge.IssueInvitation(baseURL, pairingInvitationTTL)
		if err != nil {
			return fmt.Errorf("issue pairing invitation")
		}
		png, err := RenderQRCode(invitation.URL)
		if err != nil {
			return fmt.Errorf("render pairing QR code")
		}
		c.invitationURL = invitation.URL
		c.state.InvitationPNG = png
		c.state.ExpiresAt = invitation.ExpiresAt
	}
	devices, err := c.bridge.ListDevices()
	if err != nil {
		return fmt.Errorf("list paired devices")
	}
	kind := WaitingForPair
	for _, device := range devices {
		if !device.Revoked {
			kind = Connected
			break
		}
	}
	state := c.publishLocked(DesktopState{
		Kind:          kind,
		Address:       c.bridge.Address(),
		InvitationPNG: c.state.InvitationPNG,
		ExpiresAt:     c.state.ExpiresAt,
		Devices:       devices,
	})
	notification = &state
	return nil
}

func (c *Controller) autoRestartIsBlocked() bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.autoRestartBlocked
}

func (c *Controller) restartDelay() time.Duration {
	c.mu.RLock()
	defer c.mu.RUnlock()
	shift := c.consecutiveFailures - 1
	if shift < 0 {
		shift = 0
	}
	return restartBridgeBackoff * time.Duration(1<<shift)
}

func (c *Controller) State() DesktopState {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return cloneDesktopState(c.state)
}

func (c *Controller) waitConditions() (uint64, <-chan error, time.Duration) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	var done <-chan error
	if c.bridge != nil {
		done = c.bridge.Done()
	}
	delay := controllerRefreshInterval
	if !c.state.ExpiresAt.IsZero() {
		untilExpiry := c.state.ExpiresAt.Sub(c.options.Clock.Now())
		if untilExpiry < delay {
			delay = untilExpiry
			if delay < 0 {
				delay = 0
			}
		}
	}
	return c.generation, done, delay
}

func (c *Controller) Shutdown() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil
	}
	c.closed = true
	return c.closeBridgeLocked()
}

func (c *Controller) startBridgeLocked(ctx context.Context, ip, codexPath string) error {
	config := BridgeConfig{
		ListenAddress: net.JoinHostPort(ip, defaultBridgePort),
		CodexPath:     codexPath,
		DatabasePath:  filepath.Join(c.options.LocalAppData, "CodexRemote", "bridge.db"),
	}
	bridge, err := c.options.Bridges.Start(ctx, config)
	if errors.Is(err, ErrAddressInUse) {
		config.ListenAddress = net.JoinHostPort(ip, "0")
		bridge, err = c.options.Bridges.Start(ctx, config)
	}
	if err != nil {
		return err
	}
	c.bridge = bridge
	c.generation++
	c.tailscaleIP = ip
	c.codexPath = codexPath
	c.invitationURL = ""
	c.state = DesktopState{}
	return nil
}

func (c *Controller) closeBridgeLocked() error {
	if c.bridge == nil {
		return nil
	}
	err := c.bridge.Close()
	c.bridge = nil
	c.generation++
	c.tailscaleIP = ""
	c.codexPath = ""
	c.invitationURL = ""
	c.state = DesktopState{}
	return err
}

func (c *Controller) publishLocked(state DesktopState) DesktopState {
	c.state = cloneDesktopState(state)
	return cloneDesktopState(state)
}

func (c *Controller) notify(state DesktopState) {
	if c.options.Notifier != nil {
		c.options.Notifier.Notify(cloneDesktopState(state))
	}
}

func cloneDesktopState(state DesktopState) DesktopState {
	clone := state
	clone.InvitationPNG = append([]byte(nil), state.InvitationPNG...)
	clone.Devices = append([]store.DeviceSummary(nil), state.Devices...)
	for index := range clone.Devices {
		if state.Devices[index].LastSeenAt != nil {
			lastSeen := *state.Devices[index].LastSeenAt
			clone.Devices[index].LastSeenAt = &lastSeen
		}
	}
	return clone
}
