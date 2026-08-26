package auth

import (
	"net/url"
	"path/filepath"
	"testing"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/store"
)

func TestIssueInvitationIncludesExpiryAndEncodedOneTimeToken(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Date(2026, 8, 26, 12, 0, 0, 0, time.UTC)
	service := NewPairingService(db, func() time.Time { return now })
	invitation, err := service.IssueInvitation("http://100.88.10.20:8787", 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := url.Parse(invitation.URL)
	if err != nil {
		t.Fatal(err)
	}
	if parsed.Scheme != "codex-remote" || parsed.Host != "pair" || parsed.Query().Get("baseUrl") != "http://100.88.10.20:8787" || parsed.Query().Get("token") == "" {
		t.Fatalf("url=%q", invitation.URL)
	}
	if !invitation.ExpiresAt.Equal(now.Add(5 * time.Minute)) {
		t.Fatalf("expiresAt=%v", invitation.ExpiresAt)
	}
}

func TestPairingTokenCanOnlyBeConsumedOnce(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	now := time.Date(2026, 8, 25, 12, 0, 0, 0, time.UTC)
	service := NewPairingService(db, func() time.Time { return now })
	token, err := service.Issue(5 * time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	credential, err := service.Exchange(token, "phone-1", "Pixel")
	if err != nil {
		t.Fatal(err)
	}
	if credential == "" {
		t.Fatal("credential must not be empty")
	}
	if _, err := service.Exchange(token, "phone-2", "Tablet"); err == nil {
		t.Fatal("expected consumed token to be rejected")
	}
	if ok, err := service.Authenticate("phone-1", credential); err != nil || !ok {
		t.Fatalf("expected credential to authenticate: ok=%v err=%v", ok, err)
	}
	devices, err := db.ListDevices()
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 1 || devices[0].LastSeenAt == nil || !devices[0].LastSeenAt.Equal(now) {
		t.Fatalf("authentication must update activity: %+v", devices)
	}
}

func TestPairingTokenExpires(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	now := time.Date(2026, 8, 25, 12, 0, 0, 0, time.UTC)
	service := NewPairingService(db, func() time.Time { return now })
	token, err := service.Issue(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	now = now.Add(2 * time.Minute)
	if _, err := service.Exchange(token, "phone-1", "Pixel"); err == nil {
		t.Fatal("expected expired token to be rejected")
	}
}
