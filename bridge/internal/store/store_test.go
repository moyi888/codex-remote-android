package store

import (
	"path/filepath"
	"testing"
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
