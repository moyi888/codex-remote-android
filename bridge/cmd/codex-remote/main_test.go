package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/moyi888/codex-remote-android/bridge/internal/codex"
)

type fakeAppServerRuntime struct {
	calls         []string
	notifications []string
	closed        bool
}

func (f *fakeAppServerRuntime) Call(_ context.Context, method string, _ any, result any) error {
	f.calls = append(f.calls, method)
	return json.Unmarshal([]byte(`{"userAgent":"codex-test"}`), result)
}

func (f *fakeAppServerRuntime) Notify(_ context.Context, method string, _ any) error {
	f.notifications = append(f.notifications, method)
	return nil
}

func (f *fakeAppServerRuntime) Close() error {
	f.closed = true
	return nil
}

func TestVersionTextIncludesProductAndVersion(t *testing.T) {
	got := versionText("0.1.0-test")
	want := "codex-remote 0.1.0-test"
	if got != want {
		t.Fatalf("versionText() = %q, want %q", got, want)
	}
}

func TestDefaultDesktopMode(t *testing.T) {
	mode, err := selectApplicationMode([]string{})
	if err != nil || mode != desktopMode {
		t.Fatalf("mode=%q err=%v", mode, err)
	}
}

func TestExplicitModesRemainAvailable(t *testing.T) {
	for argument, want := range map[string]applicationMode{"serve": serveMode, "version": versionMode} {
		mode, err := selectApplicationMode([]string{argument})
		if err != nil || mode != want {
			t.Fatalf("argument=%q mode=%q err=%v", argument, mode, err)
		}
	}
}

func TestRunApplicationDispatchesDefaultDesktop(t *testing.T) {
	desktopCalls := 0
	serveCalls := 0
	err := runApplication([]string{}, applicationActions{
		desktop: func() error { desktopCalls++; return nil },
		serve:   func(serveOptions) error { serveCalls++; return nil },
	})
	if err != nil || desktopCalls != 1 || serveCalls != 0 {
		t.Fatalf("err=%v desktop=%d serve=%d", err, desktopCalls, serveCalls)
	}
}

func TestRunApplicationKeepsServeAndVersionModes(t *testing.T) {
	serveCalls := 0
	versionCalls := 0
	actions := applicationActions{
		desktop: func() error { return errors.New("desktop must not run") },
		serve: func(options serveOptions) error {
			serveCalls++
			if !options.fake {
				t.Fatal("serve options were not parsed")
			}
			return nil
		},
		version: func() error { versionCalls++; return nil },
	}
	if err := runApplication([]string{"serve", "--fake"}, actions); err != nil {
		t.Fatal(err)
	}
	if err := runApplication([]string{"version"}, actions); err != nil {
		t.Fatal(err)
	}
	if serveCalls != 1 || versionCalls != 1 {
		t.Fatalf("serve=%d version=%d", serveCalls, versionCalls)
	}
	if err := runApplication([]string{"version", "extra"}, actions); err == nil {
		t.Fatal("version 不应接受额外参数")
	}
}

func TestNewRuntimeExposesHealthAndPairingToken(t *testing.T) {
	runtime, err := newRuntime(filepath.Join(t.TempDir(), "bridge.db"), codex.NewFakeAdapter())
	if err != nil {
		t.Fatal(err)
	}
	defer runtime.Close()
	if runtime.pairingToken == "" {
		t.Fatal("pairing token must be issued")
	}
	request := httptest.NewRequest(http.MethodGet, "/v1/health", nil)
	response := httptest.NewRecorder()
	runtime.handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("health status = %d", response.Code)
	}
}

func TestParseServeOptionsRejectsWildcardWithoutOptIn(t *testing.T) {
	_, err := parseServeOptions([]string{"--listen", "0.0.0.0:8787", "--fake"})
	if err == nil {
		t.Fatal("expected wildcard listener to be rejected")
	}
}

func TestParseServeOptionsDerivesAdvertiseURLFromTailscaleListen(t *testing.T) {
	options, err := parseServeOptions([]string{"--listen", "100.88.10.20:8787", "--fake"})
	if err != nil {
		t.Fatal(err)
	}
	if options.advertiseURL != "http://100.88.10.20:8787" {
		t.Fatalf("unexpected options: %+v", options)
	}
}

func TestParseServeOptionsPreservesDefaultLoopbackStartup(t *testing.T) {
	options, err := parseServeOptions([]string{"--fake"})
	if err != nil {
		t.Fatal(err)
	}
	if options.listen != "127.0.0.1:8787" || options.advertiseURL != "" || !options.fake {
		t.Fatalf("unexpected default options: %+v", options)
	}
}

func TestParseServeOptionsRequiresProjectsForRealRuntime(t *testing.T) {
	_, err := parseServeOptions([]string{})
	if err == nil || !strings.Contains(err.Error(), "--projects") {
		t.Fatalf("expected projects requirement, got %v", err)
	}
}

func TestParseServeOptionsAcceptsHistoryProjectsForDesktopRuntime(t *testing.T) {
	options, err := parseServeOptions([]string{
		"--listen", "100.88.10.20:8787",
		"--history-projects",
	})
	if err != nil {
		t.Fatal(err)
	}
	if !options.historyProjects || options.projects != "" {
		t.Fatalf("unexpected options: %+v", options)
	}
}

func TestParseServeOptionsRejectsAmbiguousProjectSources(t *testing.T) {
	_, err := parseServeOptions([]string{
		"--projects", `D:\config\projects.json`,
		"--history-projects",
	})
	if err == nil || !strings.Contains(err.Error(), "choose one") {
		t.Fatalf("expected exclusive project sources, got %v", err)
	}
}

func TestParseServeOptionsAcceptsRealRuntimeConfiguration(t *testing.T) {
	options, err := parseServeOptions([]string{
		"--projects", `D:\config\projects.json`,
		"--codex-command", `D:\tools\codex.exe`,
	})
	if err != nil {
		t.Fatal(err)
	}
	if options.projects != `D:\config\projects.json` || options.codexCommand != `D:\tools\codex.exe` || options.fake {
		t.Fatalf("unexpected options: %+v", options)
	}
}

func TestStartRealAdapterInitializesConfiguredAppServer(t *testing.T) {
	projectPath := t.TempDir()
	registryPath := filepath.Join(t.TempDir(), "projects.json")
	registry := `[{"id":"app","displayName":"App","path":` + strconv.Quote(projectPath) + `}]`
	if err := os.WriteFile(registryPath, []byte(registry), 0o600); err != nil {
		t.Fatal(err)
	}
	runtime := &fakeAppServerRuntime{}
	var command string
	var arguments []string
	starter := func(_ context.Context, gotCommand string, gotArguments, _ []string) (appServerRuntime, error) {
		command = gotCommand
		arguments = append([]string(nil), gotArguments...)
		return runtime, nil
	}

	adapter, closer, err := startRealAdapter(context.Background(), serveOptions{
		projects: registryPath, codexCommand: "custom-codex",
	}, starter)
	if err != nil {
		t.Fatal(err)
	}
	defer closer.Close()
	projects, err := adapter.ListProjects(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if command != "custom-codex" || len(arguments) != 1 || arguments[0] != "app-server" {
		t.Fatalf("command=%q arguments=%v", command, arguments)
	}
	if len(projects) != 1 || projects[0].ID != "app" || len(runtime.calls) != 1 || runtime.calls[0] != "initialize" || len(runtime.notifications) != 1 || runtime.notifications[0] != "initialized" {
		t.Fatalf("projects=%+v runtime=%+v", projects, runtime)
	}
}

func TestStartRealAdapterUsesHistoryProjectCatalog(t *testing.T) {
	runtime := &fakeAppServerRuntime{}
	starter := func(context.Context, string, []string, []string) (appServerRuntime, error) {
		return runtime, nil
	}
	adapter, closer, err := startRealAdapter(context.Background(), serveOptions{
		historyProjects: true,
		codexCommand:    "codex",
	}, starter)
	if err != nil {
		t.Fatal(err)
	}
	defer closer.Close()
	if _, err := adapter.ListProjects(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(runtime.calls) != 2 || runtime.calls[0] != "initialize" || runtime.calls[1] != "thread/list" {
		t.Fatalf("calls=%v", runtime.calls)
	}
}

func TestStartRealAdapterFailsClosedWhenRegistryCannotLoad(t *testing.T) {
	starter := func(context.Context, string, []string, []string) (appServerRuntime, error) {
		return nil, errors.New("must not start")
	}
	_, _, err := startRealAdapter(context.Background(), serveOptions{
		projects: "missing.json", codexCommand: "codex",
	}, starter)
	if err == nil {
		t.Fatal("real runtime start must fail without falling back to fake")
	}
}

func TestParseServeOptionsRejectsWildcardWithoutAdvertiseURL(t *testing.T) {
	_, err := parseServeOptions([]string{
		"--listen", "0.0.0.0:8787", "--allow-public-listen", "--fake",
	})
	if err == nil || !strings.Contains(err.Error(), "--advertise-url") {
		t.Fatalf("expected actionable advertise URL error, got %v", err)
	}
}

func TestParseServeOptionsAcceptsExplicitAdvertiseURLIndependentOfBind(t *testing.T) {
	options, err := parseServeOptions([]string{
		"--listen", "127.0.0.1:8787",
		"--advertise-url", "https://bridge.example:9443/",
		"--fake",
	})
	if err != nil {
		t.Fatal(err)
	}
	if options.listen != "127.0.0.1:8787" || options.advertiseURL != "https://bridge.example:9443" {
		t.Fatalf("unexpected options: %+v", options)
	}
}

func TestParseServeOptionsRejectsInvalidAdvertiseURL(t *testing.T) {
	invalid := []string{
		"ftp://bridge.example",
		"https:///missing-host",
		"https://user@bridge.example",
		"https://bridge.example/path",
		"https://bridge.example?mode=pair",
		"https://bridge.example#fragment",
		"https://bridge.example:99999",
		"https://under_score.example",
		"https://例子.example",
		"https://-leading.example",
		"https://trailing-.example",
		"https://" + strings.Repeat("a", 64) + ".example",
		"https://" + strings.Repeat("a.", 126) + "aa",
		"http://0.0.0.0:8787",
		"http://[::]:8787",
	}
	for _, advertiseURL := range invalid {
		t.Run(advertiseURL, func(t *testing.T) {
			_, err := parseServeOptions([]string{
				"--listen", "127.0.0.1:8787", "--advertise-url", advertiseURL, "--fake",
			})
			if err == nil {
				t.Fatalf("expected %q to be rejected", advertiseURL)
			}
		})
	}
}

func TestParseServeOptionsAcceptsPunycodeAdvertiseHost(t *testing.T) {
	options, err := parseServeOptions([]string{
		"--listen", "127.0.0.1:8787",
		"--advertise-url", "https://xn--bcher-kva.example/",
		"--fake",
	})
	if err != nil {
		t.Fatal(err)
	}
	if options.advertiseURL != "https://xn--bcher-kva.example" {
		t.Fatalf("advertise URL = %q", options.advertiseURL)
	}
}

func TestNormalizeAdvertiseURLRejectsAmbiguousNumericHosts(t *testing.T) {
	invalidHosts := []string{
		"999.999.999.999",
		"1.2.3",
		"bridge.123",
	}
	for _, host := range invalidHosts {
		t.Run(host, func(t *testing.T) {
			if _, err := normalizeAdvertiseURL("127.0.0.1:8787", "https://"+host); err == nil {
				t.Fatalf("expected ambiguous numeric host %q to be rejected", host)
			}
		})
	}
}

func TestNormalizeAdvertiseURLAcceptsValidIPAndDNSHosts(t *testing.T) {
	validURLs := []string{
		"http://100.88.10.20:8787",
		"https://bridge-1.tailnet.ts.net",
	}
	for _, advertiseURL := range validURLs {
		t.Run(advertiseURL, func(t *testing.T) {
			got, err := normalizeAdvertiseURL("127.0.0.1:8787", advertiseURL)
			if err != nil {
				t.Fatal(err)
			}
			if got != advertiseURL {
				t.Fatalf("normalizeAdvertiseURL() = %q, want %q", got, advertiseURL)
			}
		})
	}
}

func TestPairingOutputWithoutAdvertiseURLKeepsTokenAndExplainsMissingLink(t *testing.T) {
	got, err := pairingOutput("", "one-time-secret")
	if err != nil {
		t.Fatal(err)
	}
	want := "One-time pairing token (expires in 5 minutes): one-time-secret\n" +
		"Pairing link unavailable; set --advertise-url to a phone-reachable origin.\n"
	if got != want {
		t.Fatalf("pairingOutput() = %q, want %q", got, want)
	}
}

func TestPairingOutputWithAdvertiseURLIncludesLink(t *testing.T) {
	got, err := pairingOutput("https://bridge.example", "one-time-secret")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(got, "One-time pairing token (expires in 5 minutes): one-time-secret\n") ||
		!strings.Contains(got, "Pairing link: codex-remote://pair?") {
		t.Fatalf("unexpected pairing output: %q", got)
	}
}

func TestPairingInvitationURLPercentEncodesBaseURLAndToken(t *testing.T) {
	got, err := pairingInvitationURL("https://bridge.example:9443", "one time/token?")
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := url.Parse(got)
	if err != nil {
		t.Fatal(err)
	}
	if parsed.Scheme != "codex-remote" || parsed.Host != "pair" {
		t.Fatalf("unexpected pairing invitation target: %q", got)
	}
	if baseURL := parsed.Query().Get("baseUrl"); baseURL != "https://bridge.example:9443" {
		t.Fatalf("baseUrl = %q", baseURL)
	}
	if token := parsed.Query().Get("token"); token != "one time/token?" {
		t.Fatalf("token = %q", token)
	}
	if got == "codex-remote://pair?baseUrl=https://bridge.example:9443&token=one time/token?" {
		t.Fatal("pairing invitation values must be percent encoded")
	}
}

func TestPairingInvitationURLFormatsIPv6BaseURL(t *testing.T) {
	got, err := pairingInvitationURL("http://[fd7a:115c:a1e0::1]:8787", "secret")
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := url.Parse(got)
	if err != nil {
		t.Fatal(err)
	}
	if baseURL := parsed.Query().Get("baseUrl"); baseURL != "http://[fd7a:115c:a1e0::1]:8787" {
		t.Fatalf("baseUrl = %q", baseURL)
	}
}
