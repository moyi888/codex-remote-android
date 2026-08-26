package desktop

import (
	"context"
	"errors"
	"net/netip"
	"os"
	"path/filepath"
	"slices"
	"testing"
)

func TestParseTailscaleStatusAcceptsRunningTailnetIPv4(t *testing.T) {
	got, err := parseTailscaleStatus([]byte(`{
        "BackendState":"Running",
        "TailscaleIPs":["fd7a:115c:a1e0::1","100.88.10.20"]
    }`))
	if err != nil {
		t.Fatal(err)
	}
	if got != netip.MustParseAddr("100.88.10.20") {
		t.Fatalf("address=%v", got)
	}
}

func TestParseTailscaleStatusRejectsUnavailableConnections(t *testing.T) {
	tests := map[string]struct {
		raw  string
		want error
	}{
		"stopped": {
			raw:  `{"BackendState":"Stopped","TailscaleIPs":["100.88.10.20"]}`,
			want: ErrTailscaleDisconnected,
		},
		"ipv6 only": {
			raw:  `{"BackendState":"Running","TailscaleIPs":["fd7a:115c:a1e0::1"]}`,
			want: ErrTailscaleDisconnected,
		},
		"public address": {
			raw:  `{"BackendState":"Running","TailscaleIPs":["203.0.113.10"]}`,
			want: ErrTailscaleDisconnected,
		},
		"malformed": {
			raw:  `{`,
			want: ErrTailscaleStatus,
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			_, err := parseTailscaleStatus([]byte(test.raw))
			if !errors.Is(err, test.want) {
				t.Fatalf("error=%v want=%v", err, test.want)
			}
		})
	}
}

func TestDiscoverTailscaleUsesPathBeforeProgramFiles(t *testing.T) {
	runner := &recordingCommandRunner{output: []byte(`{
        "BackendState":"Running","TailscaleIPs":["100.88.10.20"]
    }`)}
	lookup := func(name string) (string, error) {
		if name != "tailscale" {
			t.Fatalf("lookup=%q", name)
		}
		return `D:\tools\tailscale.exe`, nil
	}
	status, err := DiscoverTailscale(context.Background(), lookup, `C:\Program Files`, runner)
	if err != nil {
		t.Fatal(err)
	}
	if status.Executable != `D:\tools\tailscale.exe` || status.IP.String() != "100.88.10.20" {
		t.Fatalf("status=%+v", status)
	}
	if !slices.Equal(runner.arguments, []string{"status", "--json"}) {
		t.Fatalf("arguments=%v", runner.arguments)
	}
}

func TestDiscoverTailscaleFallsBackToProgramFiles(t *testing.T) {
	programFiles := t.TempDir()
	executable := filepath.Join(programFiles, "Tailscale", "tailscale.exe")
	if err := os.MkdirAll(filepath.Dir(executable), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(executable, []byte("test"), 0o600); err != nil {
		t.Fatal(err)
	}
	runner := &recordingCommandRunner{output: []byte(`{
        "BackendState":"Running","TailscaleIPs":["100.99.1.2"]
    }`)}
	status, err := DiscoverTailscale(
		context.Background(),
		func(string) (string, error) { return "", errors.New("not found") },
		programFiles,
		runner,
	)
	if err != nil {
		t.Fatal(err)
	}
	if status.Executable != executable || runner.command != executable {
		t.Fatalf("status=%+v command=%q", status, runner.command)
	}
}

type recordingCommandRunner struct {
	command   string
	arguments []string
	output    []byte
	err       error
}

func (r *recordingCommandRunner) Run(_ context.Context, command string, arguments ...string) ([]byte, error) {
	r.command = command
	r.arguments = append([]string(nil), arguments...)
	return r.output, r.err
}
