package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
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

func TestParseServeOptionsAcceptsLoopback(t *testing.T) {
	options, err := parseServeOptions([]string{"--listen", "127.0.0.1:8787", "--fake"})
	if err != nil {
		t.Fatal(err)
	}
	if !options.fake || options.listen != "127.0.0.1:8787" {
		t.Fatalf("unexpected options: %+v", options)
	}
}

func TestPairingInvitationURLPercentEncodesBaseURLAndToken(t *testing.T) {
	got, err := pairingInvitationURL("127.0.0.1:8787", "one time/token?")
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
	if baseURL := parsed.Query().Get("baseUrl"); baseURL != "http://127.0.0.1:8787" {
		t.Fatalf("baseUrl = %q", baseURL)
	}
	if token := parsed.Query().Get("token"); token != "one time/token?" {
		t.Fatalf("token = %q", token)
	}
	if got == "codex-remote://pair?baseUrl=http://127.0.0.1:8787&token=one time/token?" {
		t.Fatal("pairing invitation values must be percent encoded")
	}
}

func TestPairingInvitationURLFormatsIPv6BaseURL(t *testing.T) {
	got, err := pairingInvitationURL("[fd7a:115c:a1e0::1]:8787", "secret")
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
