package desktop

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"syscall"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/api"
	"github.com/moyi888/codex-remote-android/bridge/internal/auth"
	"github.com/moyi888/codex-remote-android/bridge/internal/codex"
	"github.com/moyi888/codex-remote-android/bridge/internal/commands"
	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
	"github.com/moyi888/codex-remote-android/bridge/internal/events"
	"github.com/moyi888/codex-remote-android/bridge/internal/store"
)

type ManagedRPCTransport interface {
	codex.RPCTransport
	Notifications() <-chan codex.Notification
	Done() <-chan error
	Close() error
}

type ManagedRPCStarter func(context.Context, string) (ManagedRPCTransport, error)

type HTTPBridgeFactory struct {
	start   ManagedRPCStarter
	version string
	now     func() time.Time
}

func NewHTTPBridgeFactory(start ManagedRPCStarter, version string, now func() time.Time) *HTTPBridgeFactory {
	return &HTTPBridgeFactory{start: start, version: version, now: now}
}

func (f *HTTPBridgeFactory) Start(ctx context.Context, config BridgeConfig) (Bridge, error) {
	listener, err := net.Listen("tcp", config.ListenAddress)
	if err != nil {
		if isAddressInUse(err) {
			return nil, ErrAddressInUse
		}
		return nil, fmt.Errorf("listen for bridge")
	}
	failed := true
	defer func() {
		if failed {
			_ = listener.Close()
		}
	}()

	if err := os.MkdirAll(filepath.Dir(config.DatabasePath), 0o700); err != nil {
		return nil, fmt.Errorf("create bridge data directory")
	}
	transport, err := f.start(ctx, config.CodexPath)
	if err != nil {
		return nil, ErrCodexProbe
	}
	transportOwned := true
	defer func() {
		if transportOwned {
			_ = transport.Close()
		}
	}()
	initializeContext, cancelInitialize := context.WithTimeout(ctx, 10*time.Second)
	err = codex.InitializeTransport(initializeContext, transport, f.version)
	cancelInitialize()
	if err != nil {
		return nil, ErrCodexProbe
	}

	database, err := store.Open(config.DatabasePath)
	if err != nil {
		return nil, fmt.Errorf("open bridge database")
	}
	pairing := auth.NewPairingService(database, f.now)
	adapter := codex.NewHistoryAppServerAdapter(transport)
	broker := events.NewBroker(database, f.now)
	commandService := commands.NewService(database, codex.NewCommandExecutor(adapter))
	apiServer := api.NewServer(pairing, adapter, api.WithCommands(commandService), api.WithEvents(broker))
	httpServer := &http.Server{
		Handler:           apiServer.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	runtimeContext, cancelRuntime := context.WithCancel(ctx)
	runtime := &httpBridge{
		address:   listener.Addr().String(),
		listener:  listener,
		server:    httpServer,
		transport: transport,
		database:  database,
		pairing:   pairing,
		done:      make(chan error, 1),
		cancel:    cancelRuntime,
	}
	go runtime.forwardNotifications(runtimeContext, broker, f.now)
	go runtime.serve(runtimeContext)
	failed = false
	transportOwned = false
	return runtime, nil
}

func isAddressInUse(err error) bool {
	if errors.Is(err, syscall.EADDRINUSE) {
		return true
	}
	if runtime.GOOS != "windows" {
		return false
	}
	var errno syscall.Errno
	return errors.As(err, &errno) && errno == 10048
}

type httpBridge struct {
	address   string
	listener  net.Listener
	server    *http.Server
	transport ManagedRPCTransport
	database  *store.Store
	pairing   *auth.PairingService
	done      chan error
	cancel    context.CancelFunc
	closeOnce sync.Once
}

func (b *httpBridge) forwardNotifications(ctx context.Context, broker *events.Broker, now func() time.Time) {
	for {
		select {
		case <-ctx.Done():
			return
		case notification, ok := <-b.transport.Notifications():
			if !ok {
				return
			}
			threadID, attention, detected := codex.AttentionFromNotification(notification, now())
			if !detected {
				continue
			}
			payload, err := json.Marshal(struct {
				ID        string           `json:"id"`
				Attention domain.Attention `json:"attention"`
			}{ID: threadID, Attention: attention})
			if err != nil {
				continue
			}
			_, _ = broker.Publish("attention.required", payload)
		}
	}
}

func (b *httpBridge) Address() string { return b.address }

func (b *httpBridge) IssueInvitation(baseURL string, ttl time.Duration) (auth.PairingInvitation, error) {
	return b.pairing.IssueInvitation(baseURL, ttl)
}

func (b *httpBridge) ListDevices() ([]store.DeviceSummary, error) {
	return b.database.ListDevices()
}

func (b *httpBridge) RevokeDevice(deviceID string) error {
	return b.database.RevokeDevice(deviceID)
}

func (b *httpBridge) Done() <-chan error { return b.done }

func (b *httpBridge) Close() error {
	b.closeOnce.Do(func() {
		b.cancel()
		_ = b.server.Close()
		_ = b.listener.Close()
		_ = b.transport.Close()
		_ = b.database.Close()
	})
	return nil
}

func (b *httpBridge) serve(ctx context.Context) {
	httpDone := make(chan error, 1)
	go func() { httpDone <- b.server.Serve(b.listener) }()
	select {
	case <-ctx.Done():
		_ = b.Close()
		return
	case err := <-b.transport.Done():
		b.reportExit(err)
	case err := <-httpDone:
		if !errors.Is(err, http.ErrServerClosed) && !errors.Is(err, net.ErrClosed) {
			b.reportExit(err)
		}
	}
}

func (b *httpBridge) reportExit(err error) {
	if err == nil {
		err = errors.New("bridge runtime stopped")
	}
	select {
	case b.done <- err:
	default:
	}
}
