package codex

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
)

type CommandExecutor struct{ adapter Adapter }

func NewCommandExecutor(adapter Adapter) *CommandExecutor { return &CommandExecutor{adapter: adapter} }

func (e *CommandExecutor) Execute(ctx context.Context, command domain.CommandEnvelope) (json.RawMessage, error) {
	switch command.Type {
	case "task.start":
		var request StartTaskRequest
		if err := json.Unmarshal(command.Payload, &request); err != nil {
			return nil, fmt.Errorf("invalid task.start payload: %w", err)
		}
		thread, err := e.adapter.StartTask(ctx, request)
		if err != nil {
			return nil, err
		}
		return json.Marshal(thread)
	case "thread.send":
		var request SendTurnRequest
		if err := json.Unmarshal(command.Payload, &request); err != nil {
			return nil, fmt.Errorf("invalid thread.send payload: %w", err)
		}
		if err := e.adapter.SendTurn(ctx, request); err != nil {
			return nil, err
		}
		return json.RawMessage(`{"accepted":true}`), nil
	default:
		return nil, fmt.Errorf("unsupported command type %q", command.Type)
	}
}
