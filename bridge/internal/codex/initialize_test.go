package codex

import (
	"context"
	"encoding/json"
	"testing"
)

type recordingTransport struct {
	methods       []string
	notifications []string
}

func (r *recordingTransport) Call(_ context.Context, method string, _ any, result any) error {
	r.methods = append(r.methods, method)
	return json.Unmarshal([]byte(`{"userAgent":"codex-test"}`), result)
}

func (r *recordingTransport) Notify(_ context.Context, method string, _ any) error {
	r.notifications = append(r.notifications, method)
	return nil
}

func TestInitializeTransportPerformsHandshake(t *testing.T) {
	transport := &recordingTransport{}
	if err := InitializeTransport(context.Background(), transport, "0.1.0"); err != nil {
		t.Fatal(err)
	}
	if len(transport.methods) != 1 || transport.methods[0] != "initialize" {
		t.Fatalf("methods = %+v", transport.methods)
	}
	if len(transport.notifications) != 1 || transport.notifications[0] != "initialized" {
		t.Fatalf("notifications = %+v", transport.notifications)
	}
}
