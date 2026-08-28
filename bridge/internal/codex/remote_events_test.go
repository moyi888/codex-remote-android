package codex

import (
	"encoding/json"
	"testing"
)

func TestRemoteEventTypeFiltersHighFrequencyDeltas(t *testing.T) {
	if _, ok := RemoteEventType("item/agentMessage/delta"); ok {
		t.Fatal("agent message deltas must not be persisted as events")
	}
	if got, ok := RemoteEventType("turn/started"); !ok || got != "turn.started" {
		t.Fatalf("turn event = %q, %v", got, ok)
	}
}

func TestRemoteEventPayloadPreservesParams(t *testing.T) {
	payload := RemoteEventPayload(Notification{Method: "item/completed", Params: json.RawMessage(`{"threadId":"t1"}`)})
	if string(payload) != `{"threadId":"t1"}` {
		t.Fatalf("payload = %s", payload)
	}
}
