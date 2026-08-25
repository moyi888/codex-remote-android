package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"

	"github.com/moyi888/codex-remote-android/bridge/internal/codex"
)

func TestVersionTextIncludesProductAndVersion(t *testing.T) {
	got := versionText("0.1.0-test")
	want := "codex-remote 0.1.0-test"
	if got != want {
		t.Fatalf("versionText() = %q, want %q", got, want)
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
