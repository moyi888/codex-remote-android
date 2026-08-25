package commands

import (
	"context"
	"encoding/json"
	"path/filepath"
	"testing"

	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
	"github.com/moyi888/codex-remote-android/bridge/internal/store"
)

type countingExecutor struct{ calls int }

func (e *countingExecutor) Execute(_ context.Context, command domain.CommandEnvelope) (json.RawMessage, error) {
	e.calls++
	return json.RawMessage(`{"accepted":true}`), nil
}

func TestServiceExecutesIdempotentCommandOnce(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	executor := &countingExecutor{}
	service := NewService(db, executor)
	command := domain.CommandEnvelope{
		ProtocolVersion: domain.ProtocolVersion,
		RequestID:       "request-1",
		DeviceID:        "phone-1",
		IdempotencyKey:  "key-1",
		Type:            "thread.send",
		Payload:         json.RawMessage(`{"threadId":"thread-1"}`),
	}

	first, err := service.Handle(context.Background(), command)
	if err != nil {
		t.Fatal(err)
	}
	second, err := service.Handle(context.Background(), command)
	if err != nil {
		t.Fatal(err)
	}
	if executor.calls != 1 {
		t.Fatalf("executor calls = %d, want 1", executor.calls)
	}
	if string(first) != string(second) {
		t.Fatalf("results differ: %s vs %s", first, second)
	}
}
