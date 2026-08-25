package domain

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestThreadSnapshotFixtureRoundTrip(t *testing.T) {
	fixture := filepath.Join("..", "..", "..", "protocol", "fixtures", "thread-snapshot.json")
	raw, err := os.ReadFile(fixture)
	if err != nil {
		t.Fatal(err)
	}

	var envelope EventEnvelope[ThreadSummary]
	if err := json.Unmarshal(raw, &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.ProtocolVersion != 1 || envelope.EventCursor != 42 {
		t.Fatalf("unexpected envelope: %+v", envelope)
	}
	if envelope.Payload.ID != "thread-1" || envelope.Payload.Attention == nil {
		t.Fatalf("unexpected thread payload: %+v", envelope.Payload)
	}

	encoded, err := json.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}
	var roundTrip EventEnvelope[ThreadSummary]
	if err := json.Unmarshal(encoded, &roundTrip); err != nil {
		t.Fatal(err)
	}
	if roundTrip.Payload.Attention.Site != "github.com" {
		t.Fatalf("attention site = %q", roundTrip.Payload.Attention.Site)
	}
}
