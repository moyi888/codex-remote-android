package desktop

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"
)

func TestDiscoverCodexPrefersExplicitConfiguredRuntime(t *testing.T) {
	explicit := writeExecutable(t, filepath.Join(t.TempDir(), "custom-codex.exe"))
	pathCandidate := writeExecutable(t, filepath.Join(t.TempDir(), "path-codex.exe"))
	probe := &recordingCodexProbe{}
	got, err := DiscoverCodex(
		context.Background(),
		CodexDiscoveryOptions{Explicit: explicit},
		func(string) (string, error) { return pathCandidate, nil },
		probe,
	)
	if err != nil {
		t.Fatal(err)
	}
	if got != explicit || !slices.Equal(probe.paths, []string{explicit}) {
		t.Fatalf("got=%q paths=%v", got, probe.paths)
	}
}

func TestDiscoverCodexFallsThroughInvalidAndFailingCandidates(t *testing.T) {
	root := t.TempDir()
	explicit := filepath.Join(root, "missing.exe")
	pathCandidate := writeExecutable(t, filepath.Join(root, "path-codex.exe"))
	alias := writeExecutable(t, filepath.Join(root, "local", "Microsoft", "WindowsApps", "codex.exe"))
	probe := &recordingCodexProbe{failures: map[string]error{
		pathCandidate: errors.New("initialize failed"),
	}}
	got, err := DiscoverCodex(
		context.Background(),
		CodexDiscoveryOptions{Explicit: explicit, LocalAppData: filepath.Join(root, "local")},
		func(string) (string, error) { return pathCandidate, nil },
		probe,
	)
	if err != nil {
		t.Fatal(err)
	}
	if got != alias || !slices.Equal(probe.paths, []string{pathCandidate, alias}) {
		t.Fatalf("got=%q paths=%v", got, probe.paths)
	}
}

func TestDiscoverCodexDeduplicatesCanonicalCandidates(t *testing.T) {
	candidate := writeExecutable(t, filepath.Join(t.TempDir(), "codex.exe"))
	probe := &recordingCodexProbe{failures: map[string]error{candidate: errors.New("failed")}}
	_, err := DiscoverCodex(
		context.Background(),
		CodexDiscoveryOptions{Explicit: candidate},
		func(string) (string, error) { return candidate, nil },
		probe,
	)
	if !errors.Is(err, ErrCodexUnavailable) {
		t.Fatalf("error=%v", err)
	}
	if !slices.Equal(probe.paths, []string{candidate}) {
		t.Fatalf("duplicate probes=%v", probe.paths)
	}
}

func TestDiscoverCodexDoesNotRequireOfficialLoginFiles(t *testing.T) {
	candidate := writeExecutable(t, filepath.Join(t.TempDir(), "codex.exe"))
	probe := &recordingCodexProbe{}
	got, err := DiscoverCodex(
		context.Background(),
		CodexDiscoveryOptions{},
		func(string) (string, error) { return candidate, nil },
		probe,
	)
	if err != nil || got != candidate {
		t.Fatalf("got=%q err=%v", got, err)
	}
}

func TestAppServerCodexProbeInitializesAndAlwaysCloses(t *testing.T) {
	transport := &fakeProbeTransport{}
	probe := AppServerCodexProbe{
		Start: func(context.Context, string, []string, []string) (ProbeTransport, error) {
			return transport, nil
		},
		Timeout: time.Second,
		Version: "test",
	}
	if err := probe.Probe(context.Background(), `D:\tools\codex.exe`); err != nil {
		t.Fatal(err)
	}
	if !transport.closed || !slices.Equal(transport.calls, []string{"initialize", "initialized"}) {
		t.Fatalf("transport=%+v", transport)
	}
}

type recordingCodexProbe struct {
	paths    []string
	failures map[string]error
}

type fakeProbeTransport struct {
	calls  []string
	closed bool
}

func (f *fakeProbeTransport) Call(context.Context, string, any, any) error {
	f.calls = append(f.calls, "initialize")
	return nil
}

func (f *fakeProbeTransport) Notify(context.Context, string, any) error {
	f.calls = append(f.calls, "initialized")
	return nil
}

func (f *fakeProbeTransport) Close() error {
	f.closed = true
	return nil
}

func (p *recordingCodexProbe) Probe(_ context.Context, path string) error {
	p.paths = append(p.paths, path)
	return p.failures[path]
}

func writeExecutable(t *testing.T, path string) string {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("test"), 0o700); err != nil {
		t.Fatal(err)
	}
	canonical, err := filepath.EvalSymlinks(path)
	if err != nil {
		t.Fatal(err)
	}
	return canonical
}
