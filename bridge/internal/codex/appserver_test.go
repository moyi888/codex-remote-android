package codex

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"

	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
)

type recordedCall struct {
	method string
	params any
}

type recordingRPC struct{ calls []recordedCall }

func (r *recordingRPC) Call(_ context.Context, method string, params, result any) error {
	r.calls = append(r.calls, recordedCall{method: method, params: params})
	switch method {
	case "thread/start":
		return json.Unmarshal([]byte(`{"thread":{"id":"thread-real-1","preview":""},"model":"gpt-test","modelProvider":"openai","cwd":"D:\\\\code","approvalPolicy":"never","approvalsReviewer":null,"sandbox":{"type":"dangerFullAccess"}}`), result)
	case "turn/start":
		return json.Unmarshal([]byte(`{"turn":{"id":"turn-1","status":"inProgress","items":[]}}`), result)
	case "thread/resume":
		return json.Unmarshal([]byte(`{"thread":{"id":"thread-real-1"}}`), result)
	case "model/list":
		return json.Unmarshal([]byte(`{"data":[{"id":"gpt-test","displayName":"Test Model","supportedReasoningEfforts":[{"reasoningEffort":"high","description":"High"}]}],"nextCursor":null}`), result)
	default:
		return fmt.Errorf("unexpected method %s", method)
	}
}

func TestAppServerAdapterResumesThreadBeforeSendingTurn(t *testing.T) {
	rpc := &recordingRPC{}
	adapter := NewAppServerAdapter(rpc, nil)
	if err := adapter.SendTurn(context.Background(), SendTurnRequest{
		ThreadID: "thread-real-1", Prompt: "继续执行",
	}); err != nil {
		t.Fatal(err)
	}
	if len(rpc.calls) != 2 || rpc.calls[0].method != "thread/resume" || rpc.calls[1].method != "turn/start" {
		t.Fatalf("unexpected calls: %+v", rpc.calls)
	}
}

func TestAppServerAdapterStartsThreadThenTurn(t *testing.T) {
	rpc := &recordingRPC{}
	adapter := NewAppServerAdapter(rpc, []Project{{ID: "project-1", DisplayName: "Code", Path: `D:\code`}})
	thread, err := adapter.StartTask(context.Background(), StartTaskRequest{
		ProjectID: "project-1", Prompt: "检查状态", Model: "gpt-test", Reasoning: "high",
	})
	if err != nil {
		t.Fatal(err)
	}
	if thread.ID != "thread-real-1" || len(rpc.calls) != 2 {
		t.Fatalf("thread=%+v calls=%+v", thread, rpc.calls)
	}
	if rpc.calls[0].method != "thread/start" || rpc.calls[1].method != "turn/start" {
		t.Fatalf("unexpected method order: %+v", rpc.calls)
	}
	startParams, ok := rpc.calls[0].params.(map[string]any)
	if !ok {
		t.Fatalf("unexpected thread/start params: %#v", rpc.calls[0].params)
	}
	if startParams["approvalPolicy"] != "never" || startParams["sandbox"] != "dangerFullAccess" {
		t.Fatalf("thread/start must preserve full access without approvals: %#v", startParams)
	}
}

func TestAppServerAdapterMapsModels(t *testing.T) {
	rpc := &recordingRPC{}
	adapter := NewAppServerAdapter(rpc, nil)
	models, err := adapter.ListModels(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(models) != 1 || models[0].ID != "gpt-test" || models[0].ReasoningOptions[0].ID != "high" {
		t.Fatalf("unexpected models: %+v", models)
	}
}

func TestAppServerAdapterAdvertisesTurnControl(t *testing.T) {
	capabilities := NewAppServerAdapter(&recordingRPC{}, nil).Capabilities()
	if !capabilities.ReadThreads || !capabilities.StartTask || !capabilities.SendTurn || !capabilities.Steer || !capabilities.StopTurn {
		t.Fatalf("unexpected capabilities: %+v", capabilities)
	}
}

type modelWithoutReasoningRPC struct{}

func (modelWithoutReasoningRPC) Call(_ context.Context, method string, _, result any) error {
	if method != "model/list" {
		return fmt.Errorf("unexpected method %s", method)
	}
	return json.Unmarshal([]byte(`{"data":[{"id":"gpt-basic","displayName":"Basic"}],"nextCursor":null}`), result)
}

func TestAppServerAdapterMapsMissingReasoningOptionsToEmptyArray(t *testing.T) {
	adapter := NewAppServerAdapter(modelWithoutReasoningRPC{}, nil)
	models, err := adapter.ListModels(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(models[0])
	if err != nil {
		t.Fatal(err)
	}
	var model struct {
		ReasoningOptions json.RawMessage `json:"reasoningOptions"`
	}
	if err := json.Unmarshal(encoded, &model); err != nil {
		t.Fatal(err)
	}
	if string(model.ReasoningOptions) != "[]" {
		t.Fatalf("reasoningOptions = %s, want []", model.ReasoningOptions)
	}
}

func TestAppServerAdapterMapsOfficialThreadRuntimeStatuses(t *testing.T) {
	tests := []struct {
		name   string
		status string
		want   domain.ThreadState
	}{
		{name: "active", status: `{"type":"active","activeFlags":[]}`, want: domain.ThreadRunning},
		{name: "idle", status: `{"type":"idle"}`, want: domain.ThreadIdle},
		{name: "not loaded", status: `{"type":"notLoaded"}`, want: domain.ThreadIdle},
		{name: "system error", status: `{"type":"systemError"}`, want: domain.ThreadFailed},
		{name: "legacy string", status: `"idle"`, want: domain.ThreadIdle},
		{name: "unknown", status: `{"type":"futureStatus"}`, want: domain.ThreadDisconnected},
		{name: "malformed", status: `{`, want: domain.ThreadDisconnected},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got := threadStateFromStatus(json.RawMessage(test.status))
			if got != test.want {
				t.Fatalf("threadStateFromStatus(%s) = %q, want %q", test.status, got, test.want)
			}
		})
	}
}
