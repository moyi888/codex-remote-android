package codex

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
)

type desktopReadBase struct {
	commandSpyAdapter
	threads      []domain.ThreadSummary
	readPayload  json.RawMessage
	turnsPayload json.RawMessage
	readCalls    int
	turnCalls    int
}

func (b *desktopReadBase) ListThreads(context.Context) ([]domain.ThreadSummary, error) {
	return append([]domain.ThreadSummary(nil), b.threads...), nil
}

func (b *desktopReadBase) ReadThread(context.Context, string, bool) (json.RawMessage, error) {
	b.readCalls++
	return b.readPayload, nil
}

func (b *desktopReadBase) ListThreadTurns(context.Context, string, string, int) (json.RawMessage, error) {
	b.turnCalls++
	return b.turnsPayload, nil
}

type desktopReadTools struct {
	responses map[string]json.RawMessage
	errors    map[string]error
	arguments map[string]any
}

func (t desktopReadTools) CallTool(_ context.Context, _ string, tool string, arguments any, result any) error {
	if t.arguments != nil {
		if value, ok := arguments.(map[string]any); ok {
			t.arguments[tool] = value
		}
	}
	if err := t.errors[tool]; err != nil {
		return err
	}
	payload, ok := t.responses[tool]
	if !ok {
		return errors.New("unexpected tool: " + tool)
	}
	return json.Unmarshal(payload, result)
}

func TestDesktopReadAdapterBoundsHistoryPayload(t *testing.T) {
	tools := desktopReadTools{
		responses: map[string]json.RawMessage{
			"read_thread": json.RawMessage(`{"turns":[]}`),
		},
		arguments: map[string]any{},
	}

	if _, err := NewDesktopReadAdapter(&desktopReadBase{}, tools).ListThreadTurns(context.Background(), "target", "", 50); err != nil {
		t.Fatal(err)
	}
	arguments, ok := tools.arguments["read_thread"].(map[string]any)
	if !ok {
		t.Fatalf("captured arguments = %#v", tools.arguments)
	}
	if arguments["turnLimit"] != 20 {
		t.Fatalf("turnLimit = %#v, want 20", arguments["turnLimit"])
	}
	if arguments["includeOutputs"] != false {
		t.Fatalf("includeOutputs = %#v, want false", arguments["includeOutputs"])
	}
	if arguments["maxOutputCharsPerItem"] != 12000 {
		t.Fatalf("maxOutputCharsPerItem = %#v, want 12000", arguments["maxOutputCharsPerItem"])
	}
}

func TestDesktopReadAdapterOverlaysRecentDesktopThreadsWithoutDroppingBaseCatalog(t *testing.T) {
	base := &desktopReadBase{threads: []domain.ThreadSummary{
		{ID: "target", Title: "旧标题", ProjectID: "project-a", ProjectName: "目录 A", Source: domain.ThreadSourceAppServer, State: domain.ThreadIdle, UpdatedAt: time.Unix(10, 0).UTC()},
		{ID: "legacy", Title: "旧任务", ProjectID: "project-b", ProjectName: "目录 B", Source: domain.ThreadSourceAppServer, State: domain.ThreadIdle, UpdatedAt: time.Unix(20, 0).UTC()},
	}}
	tools := desktopReadTools{responses: map[string]json.RawMessage{
		"list_threads": json.RawMessage(`{"pinnedThreads":[],"threads":[{"id":"target","title":"桌面标题","cwd":"D:\\work","status":"active","updatedAt":30}]}`),
	}}

	threads, err := NewDesktopReadAdapter(base, tools).ListThreads(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(threads) != 2 || threads[0].ID != "target" || threads[1].ID != "legacy" {
		t.Fatalf("threads = %+v", threads)
	}
	if threads[0].Title != "桌面标题" || threads[0].State != domain.ThreadRunning ||
		threads[0].Source != domain.ThreadSourceDesktop || !threads[0].UpdatedAt.Equal(time.Unix(30, 0).UTC()) {
		t.Fatalf("desktop overlay = %+v", threads[0])
	}
	if threads[0].ProjectID != "project-a" || threads[0].ProjectName != "目录 A" {
		t.Fatalf("base project metadata was lost: %+v", threads[0])
	}
}

func TestDesktopReadAdapterNormalizesDesktopTurnsAndPageCursor(t *testing.T) {
	base := &desktopReadBase{}
	tools := desktopReadTools{responses: map[string]json.RawMessage{
		"read_thread": json.RawMessage(`{"thread":{"id":"target","status":{"type":"active"}},"page":{"nextCursor":"older","hasMore":true},"turns":[{"id":"turn-1","status":"inProgress","items":[]}]}`),
	}}
	adapter := NewDesktopReadAdapter(base, tools)

	payload, err := adapter.ListThreadTurns(context.Background(), "target", "", 50)
	if err != nil {
		t.Fatal(err)
	}
	var result struct {
		Turns []struct {
			ID string `json:"id"`
		} `json:"turns"`
		NextCursor string `json:"nextCursor"`
	}
	if err := json.Unmarshal(payload, &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Turns) != 1 || result.Turns[0].ID != "turn-1" || result.NextCursor != "older" {
		t.Fatalf("normalized payload = %s", payload)
	}
	if base.turnCalls != 0 {
		t.Fatalf("unexpected base history reads = %d", base.turnCalls)
	}
}

func TestDesktopReadAdapterFallsBackToBaseReaderWhenDesktopUnavailable(t *testing.T) {
	base := &desktopReadBase{
		readPayload:  json.RawMessage(`{"thread":{"id":"target"}}`),
		turnsPayload: json.RawMessage(`{"data":[],"nextCursor":null}`),
	}
	tools := desktopReadTools{errors: map[string]error{
		"read_thread":  &AppToolsUnavailableError{Err: errors.New("desktop closed")},
		"list_threads": &AppToolsUnavailableError{Err: errors.New("desktop closed")},
	}}
	adapter := NewDesktopReadAdapter(base, tools)

	if _, err := adapter.ReadThread(context.Background(), "target", true); err != nil {
		t.Fatal(err)
	}
	if _, err := adapter.ListThreadTurns(context.Background(), "target", "", 50); err != nil {
		t.Fatal(err)
	}
	if base.readCalls != 1 || base.turnCalls != 1 {
		t.Fatalf("base reads: thread=%d turns=%d", base.readCalls, base.turnCalls)
	}
}
