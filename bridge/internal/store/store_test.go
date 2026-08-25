package store

import (
	"encoding/json"
	"path/filepath"
	"testing"
	"time"
)

func TestDeviceLifecycle(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	if err := db.CreateDevice("phone-1", "Pixel", "hash-1"); err != nil {
		t.Fatal(err)
	}
	device, err := db.Device("phone-1")
	if err != nil {
		t.Fatal(err)
	}
	if device.Name != "Pixel" || device.CredentialHash != "hash-1" || device.Revoked {
		t.Fatalf("unexpected device: %+v", device)
	}
	if err := db.RevokeDevice("phone-1"); err != nil {
		t.Fatal(err)
	}
	device, err = db.Device("phone-1")
	if err != nil {
		t.Fatal(err)
	}
	if !device.Revoked {
		t.Fatal("device should be revoked")
	}
}

func TestLatestEventCursorEmptyAndAfterEvents(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	cursor, err := db.LatestEventCursor()
	if err != nil {
		t.Fatal(err)
	}
	if cursor != 0 {
		t.Fatalf("empty event cursor = %d, want 0", cursor)
	}

	for range 2 {
		if _, err := db.AppendEvent("test.event", json.RawMessage(`{}`), time.Unix(100, 0)); err != nil {
			t.Fatal(err)
		}
	}
	cursor, err = db.LatestEventCursor()
	if err != nil {
		t.Fatal(err)
	}
	if cursor != 2 {
		t.Fatalf("latest event cursor = %d, want 2", cursor)
	}
}
