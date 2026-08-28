package codex

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"

	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
)

type controlRPC struct {
	calls []recordedCall
}

func (r *controlRPC) Call(_ context.Context, method string, params, result any) error {
	r.calls = append(r.calls, recordedCall{method: method, params: params})
	var response string
	switch method {
	case "thread/start":
		response = `{"thread":{"id":"thread-1"}}`
	case "turn/start":
		response = `{"turn":{"id":"turn-1"}}`
	case "thread/resume":
		response = `{"thread":{"id":"thread-1"}}`
	case "thread/read":
		if len(r.calls) == 3 {
			response = `{"thread":{"turns":[{"id":"turn-1","status":"inProgress"}]}}`
		} else {
			response = `{"thread":{"turns":[{"id":"turn-2","status":"inProgress"}]}}`
		}
	case "turn/steer":
		response = `{"turn":{"id":"turn-2"}}`
	case "turn/interrupt":
		response = `{}`
	default:
		return fmt.Errorf("unexpected method %s", method)
	}
	return json.Unmarshal([]byte(response), result)
}

func TestAppServerAdapterSteersAndInterruptsRecordedTurn(t *testing.T) {
	rpc := &controlRPC{}
	adapter := NewAppServerAdapter(rpc, []Project{{ID: "p", DisplayName: "P", Path: t.TempDir()}})
	if _, err := adapter.StartTask(context.Background(), StartTaskRequest{ProjectID: "p", Prompt: "start"}); err != nil {
		t.Fatal(err)
	}
	if err := adapter.Steer(context.Background(), SendTurnRequest{ThreadID: "thread-1", Prompt: "steer"}); err != nil {
		t.Fatal(err)
	}
	if err := adapter.StopTurn(context.Background(), "thread-1"); err != nil {
		t.Fatal(err)
	}
	if len(rpc.calls) != 6 || rpc.calls[3].method != "turn/steer" || rpc.calls[5].method != "turn/interrupt" {
		t.Fatalf("calls = %+v", rpc.calls)
	}
	steer, ok := rpc.calls[3].params.(map[string]any)
	if !ok || steer["threadId"] != "thread-1" || steer["expectedTurnId"] != "turn-1" {
		t.Fatalf("turn/steer params = %#v", rpc.calls[3].params)
	}
	interrupt, ok := rpc.calls[5].params.(map[string]any)
	if !ok || interrupt["threadId"] != "thread-1" || interrupt["turnId"] != "turn-2" {
		t.Fatalf("turn/interrupt params = %#v", rpc.calls[5].params)
	}
}

type readControlRPC struct {
	calls []recordedCall
}

func (r *readControlRPC) Call(_ context.Context, method string, params, result any) error {
	r.calls = append(r.calls, recordedCall{method: method, params: params})
	var response string
	switch method {
	case "thread/read":
		response = `{"thread":{"turns":[{"id":"old","status":"completed"},{"id":"active-1","status":"inProgress"}]}}`
	case "turn/steer":
		response = `{}`
	default:
		return fmt.Errorf("unexpected method %s", method)
	}
	return json.Unmarshal([]byte(response), result)
}

func TestAppServerAdapterReadsActiveTurnWhenNotRecorded(t *testing.T) {
	rpc := &readControlRPC{}
	adapter := NewHistoryAppServerAdapter(rpc)
	if err := adapter.Steer(context.Background(), SendTurnRequest{ThreadID: "thread-2", Prompt: "steer"}); err != nil {
		t.Fatal(err)
	}
	if len(rpc.calls) != 2 || rpc.calls[0].method != "thread/read" || rpc.calls[1].method != "turn/steer" {
		t.Fatalf("calls = %+v", rpc.calls)
	}
	steer, ok := rpc.calls[1].params.(map[string]any)
	if !ok || steer["expectedTurnId"] != "active-1" {
		t.Fatalf("turn/steer params = %#v", rpc.calls[1].params)
	}
}

type commandSpyAdapter struct {
	steerRequest SendTurnRequest
	stopThread   string
}

func (s *commandSpyAdapter) Capabilities() Capabilities {
	return Capabilities{Steer: true, StopTurn: true}
}
func (s *commandSpyAdapter) ListThreads(context.Context) ([]domain.ThreadSummary, error) {
	return nil, nil
}
func (s *commandSpyAdapter) ListProjects(context.Context) ([]domain.ProjectOption, error) {
	return nil, nil
}
func (s *commandSpyAdapter) ListModels(context.Context) ([]domain.ModelOption, error) {
	return nil, nil
}
func (s *commandSpyAdapter) StartTask(context.Context, StartTaskRequest) (domain.ThreadSummary, error) {
	return domain.ThreadSummary{}, nil
}
func (s *commandSpyAdapter) SendTurn(context.Context, SendTurnRequest) error { return nil }
func (s *commandSpyAdapter) Steer(_ context.Context, request SendTurnRequest) error {
	s.steerRequest = request
	return nil
}
func (s *commandSpyAdapter) StopTurn(_ context.Context, threadID string) error {
	s.stopThread = threadID
	return nil
}

func TestCommandExecutorRoutesTurnControlCommands(t *testing.T) {
	spy := &commandSpyAdapter{}
	executor := NewCommandExecutor(spy)
	if _, err := executor.Execute(context.Background(), domain.CommandEnvelope{Type: "thread.steer", Payload: json.RawMessage(`{"threadId":"t","prompt":"p"}`)}); err != nil {
		t.Fatal(err)
	}
	if _, err := executor.Execute(context.Background(), domain.CommandEnvelope{Type: "thread.interrupt", Payload: json.RawMessage(`{"threadId":"t"}`)}); err != nil {
		t.Fatal(err)
	}
	if spy.steerRequest.ThreadID != "t" || spy.steerRequest.Prompt != "p" || spy.stopThread != "t" {
		t.Fatalf("spy = %+v", spy)
	}
}
