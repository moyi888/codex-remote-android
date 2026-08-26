package desktop

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestHTTPBridgeFactoryStartsRuntimeAndServesHealth(t *testing.T) {
	now := time.Date(2026, 8, 26, 12, 0, 0, 0, time.UTC)
	transport := newFakeManagedTransport()
	var startedPath string
	factory := NewHTTPBridgeFactory(func(_ context.Context, path string) (ManagedRPCTransport, error) {
		startedPath = path
		return transport, nil
	}, "test-version", func() time.Time { return now })
	databasePath := filepath.Join(t.TempDir(), "nested", "bridge.db")

	bridge, err := factory.Start(context.Background(), BridgeConfig{
		ListenAddress: "127.0.0.1:0",
		CodexPath:     `C:\Codex\codex.exe`,
		DatabasePath:  databasePath,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer bridge.Close()
	if startedPath != `C:\Codex\codex.exe` {
		t.Fatalf("启动路径 = %q", startedPath)
	}
	if transport.calls != 1 || transport.notifications != 1 {
		t.Fatalf("初始化 calls=%d notifications=%d", transport.calls, transport.notifications)
	}
	response, err := http.Get("http://" + bridge.Address() + "/v1/health")
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("健康检查状态 = %d", response.StatusCode)
	}
	invitation, err := bridge.IssueInvitation("http://"+bridge.Address(), 5*time.Minute)
	if err != nil || invitation.URL == "" || !invitation.ExpiresAt.Equal(now.Add(5*time.Minute)) {
		t.Fatalf("邀请 = %+v, err = %v", invitation, err)
	}
	devices, err := bridge.ListDevices()
	if err != nil || len(devices) != 0 {
		t.Fatalf("设备 = %+v, err = %v", devices, err)
	}
	if _, err := os.Stat(databasePath); err != nil {
		t.Fatalf("数据库未创建: %v", err)
	}
}

func TestHTTPBridgeFactoryMapsOccupiedPort(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	factory := NewHTTPBridgeFactory(func(context.Context, string) (ManagedRPCTransport, error) {
		t.Fatal("端口占用时不应启动 Codex")
		return nil, nil
	}, "test-version", time.Now)

	_, err = factory.Start(context.Background(), BridgeConfig{
		ListenAddress: listener.Addr().String(),
		CodexPath:     `C:\Codex\codex.exe`,
		DatabasePath:  filepath.Join(t.TempDir(), "bridge.db"),
	})
	if !errors.Is(err, ErrAddressInUse) {
		t.Fatalf("错误 = %v", err)
	}
}

func TestHTTPBridgeReportsAppServerExitAndClosesIdempotently(t *testing.T) {
	transport := newFakeManagedTransport()
	factory := NewHTTPBridgeFactory(func(context.Context, string) (ManagedRPCTransport, error) {
		return transport, nil
	}, "test-version", time.Now)
	bridge, err := factory.Start(context.Background(), BridgeConfig{
		ListenAddress: "127.0.0.1:0",
		CodexPath:     `C:\Codex\codex.exe`,
		DatabasePath:  filepath.Join(t.TempDir(), "bridge.db"),
	})
	if err != nil {
		t.Fatal(err)
	}
	transport.done <- errors.New("process exited")
	select {
	case <-bridge.Done():
	case <-time.After(time.Second):
		t.Fatal("Bridge 未报告 app-server 退出")
	}
	if err := bridge.Close(); err != nil {
		t.Fatal(err)
	}
	if err := bridge.Close(); err != nil {
		t.Fatal(err)
	}
	if transport.closeCalls != 1 {
		t.Fatalf("transport 关闭次数 = %d", transport.closeCalls)
	}
}

func TestHTTPBridgeContextCancellationClosesAllResources(t *testing.T) {
	transport := newFakeManagedTransport()
	ctx, cancel := context.WithCancel(context.Background())
	factory := NewHTTPBridgeFactory(func(context.Context, string) (ManagedRPCTransport, error) {
		return transport, nil
	}, "test-version", time.Now)
	bridge, err := factory.Start(ctx, BridgeConfig{
		ListenAddress: "127.0.0.1:0",
		CodexPath:     `C:\Codex\codex.exe`,
		DatabasePath:  filepath.Join(t.TempDir(), "bridge.db"),
	})
	if err != nil {
		t.Fatal(err)
	}
	address := bridge.Address()
	cancel()
	select {
	case <-transport.closed:
	case <-time.After(time.Second):
		t.Fatal("context 取消后 transport 未关闭")
	}
	if connection, err := net.DialTimeout("tcp", address, 100*time.Millisecond); err == nil {
		connection.Close()
		t.Fatal("context 取消后监听端口仍可连接")
	}
	if transport.closeCalls != 1 {
		t.Fatalf("transport 关闭次数 = %d", transport.closeCalls)
	}
}

type fakeManagedTransport struct {
	done          chan error
	closed        chan struct{}
	calls         int
	notifications int
	closeCalls    int
}

func newFakeManagedTransport() *fakeManagedTransport {
	return &fakeManagedTransport{done: make(chan error, 1), closed: make(chan struct{})}
}

func (f *fakeManagedTransport) Call(_ context.Context, method string, _ any, result any) error {
	f.calls++
	if method != "initialize" {
		return errors.New("unexpected method")
	}
	return json.Unmarshal([]byte(`{"userAgent":"test"}`), result)
}

func (f *fakeManagedTransport) Notify(_ context.Context, method string, _ any) error {
	f.notifications++
	if method != "initialized" {
		return errors.New("unexpected notification")
	}
	return nil
}

func (f *fakeManagedTransport) Done() <-chan error { return f.done }
func (f *fakeManagedTransport) Close() error {
	f.closeCalls++
	if f.closeCalls == 1 {
		close(f.closed)
	}
	return nil
}
