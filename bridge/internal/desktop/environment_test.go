package desktop

import (
	"context"
	"errors"
	"testing"
)

func TestSystemEnvironmentProbePassesExplicitCodexPath(t *testing.T) {
	var explicit string
	probe := NewSystemEnvironmentProbe(
		func(context.Context) (TailscaleStatus, error) { return tailscale("100.88.10.20"), nil },
		func(_ context.Context, path string) (string, error) {
			explicit = path
			return path, nil
		},
	)
	probe.SetCodexPath(`C:\custom\codex.exe`)
	environment := probe.Probe(context.Background())
	if explicit != `C:\custom\codex.exe` || environment.CodexPath != explicit || environment.CodexError != nil {
		t.Fatalf("environment=%+v explicit=%q", environment, explicit)
	}
}

func TestSystemEnvironmentProbeStopsAfterTailscaleFailure(t *testing.T) {
	codexCalled := false
	probe := NewSystemEnvironmentProbe(
		func(context.Context) (TailscaleStatus, error) { return TailscaleStatus{}, ErrTailscaleDisconnected },
		func(context.Context, string) (string, error) {
			codexCalled = true
			return "", errors.New("must not run")
		},
	)
	environment := probe.Probe(context.Background())
	if !errors.Is(environment.TailscaleError, ErrTailscaleDisconnected) || codexCalled {
		t.Fatalf("environment=%+v codexCalled=%v", environment, codexCalled)
	}
}

func TestSystemEnvironmentProbeCachesValidatedCodexPath(t *testing.T) {
	calls := 0
	probe := NewSystemEnvironmentProbe(
		func(context.Context) (TailscaleStatus, error) { return tailscale("100.88.10.20"), nil },
		func(context.Context, string) (string, error) {
			calls++
			return `C:\Codex\codex.exe`, nil
		},
	)
	first := probe.Probe(context.Background())
	second := probe.Probe(context.Background())
	if first.CodexPath != second.CodexPath || calls != 1 {
		t.Fatalf("first=%+v second=%+v calls=%d", first, second, calls)
	}
	probe.SetCodexPath(`D:\Other\codex.exe`)
	_ = probe.Probe(context.Background())
	if calls != 2 {
		t.Fatalf("用户改变路径后探测次数 = %d", calls)
	}
}
