package events

import (
	"encoding/json"
	"path/filepath"
	"testing"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/store"
)

func TestBrokerReplaysEventsAfterCursor(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	broker := NewBroker(db, func() time.Time { return time.Unix(100, 0).UTC() })

	for _, value := range []string{"one", "two", "three"} {
		if _, err := broker.Publish("test.event", json.RawMessage(`{"value":"`+value+`"}`)); err != nil {
			t.Fatal(err)
		}
	}

	replayed, snapshotRequired, err := broker.After(2, 10)
	if err != nil {
		t.Fatal(err)
	}
	if snapshotRequired {
		t.Fatal("snapshot should not be required")
	}
	if len(replayed) != 1 || replayed[0].Cursor != 3 {
		t.Fatalf("unexpected replay: %+v", replayed)
	}
}
