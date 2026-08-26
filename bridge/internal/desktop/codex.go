package desktop

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/codex"
)

var (
	ErrCodexUnavailable = errors.New("no usable Codex runtime was found")
	ErrCodexProbe       = errors.New("Codex app-server initialization failed")
)

type CodexProbe interface {
	Probe(context.Context, string) error
}

type ProbeTransport interface {
	codex.RPCTransport
	Close() error
}

type ProbeStarter func(context.Context, string, []string, []string) (ProbeTransport, error)

type AppServerCodexProbe struct {
	Start   ProbeStarter
	Timeout time.Duration
	Version string
}

func (p AppServerCodexProbe) Probe(ctx context.Context, path string) error {
	transport, err := p.Start(ctx, path, []string{"app-server"}, nil)
	if err != nil {
		return ErrCodexProbe
	}
	defer transport.Close()
	timeout := p.Timeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	initializeContext, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	if err := codex.InitializeTransport(initializeContext, transport, p.Version); err != nil {
		return ErrCodexProbe
	}
	return nil
}

type CodexDiscoveryOptions struct {
	Explicit     string
	LocalAppData string
	AppData      string
	ProgramFiles string
}

func DiscoverCodex(
	ctx context.Context,
	options CodexDiscoveryOptions,
	lookup func(string) (string, error),
	probe CodexProbe,
) (string, error) {
	candidates := []string{options.Explicit}
	if pathCandidate, err := lookup("codex"); err == nil {
		candidates = append(candidates, pathCandidate)
	}
	candidates = append(candidates,
		filepath.Join(options.LocalAppData, "Microsoft", "WindowsApps", "codex.exe"),
		filepath.Join(options.AppData, "npm", "codex.cmd"),
		filepath.Join(options.LocalAppData, "Programs", "Codex", "resources", "codex.exe"),
	)
	if options.ProgramFiles != "" {
		matches, _ := filepath.Glob(filepath.Join(
			options.ProgramFiles,
			"WindowsApps",
			"OpenAI.Codex_*",
			"app",
			"resources",
			"codex.exe",
		))
		candidates = append(candidates, matches...)
	}
	seen := make(map[string]struct{}, len(candidates))
	for _, candidate := range candidates {
		canonical, ok := usableExecutable(candidate)
		if !ok {
			continue
		}
		key := canonical
		if runtime.GOOS == "windows" {
			key = strings.ToLower(key)
		}
		if _, exists := seen[key]; exists {
			continue
		}
		seen[key] = struct{}{}
		if err := probe.Probe(ctx, canonical); err == nil {
			return canonical, nil
		}
	}
	return "", ErrCodexUnavailable
}

func usableExecutable(path string) (string, bool) {
	if path == "" {
		return "", false
	}
	canonical, err := filepath.EvalSymlinks(filepath.Clean(path))
	if err != nil {
		return "", false
	}
	info, err := os.Stat(canonical)
	if err != nil || info.IsDir() {
		return "", false
	}
	return canonical, true
}
