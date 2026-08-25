package commands

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
	"github.com/moyi888/codex-remote-android/bridge/internal/store"
)

type Executor interface {
	Execute(context.Context, domain.CommandEnvelope) (json.RawMessage, error)
}

type Service struct {
	store    *store.Store
	executor Executor
	now      func() time.Time
}

func NewService(store *store.Store, executor Executor) *Service {
	return &Service{store: store, executor: executor, now: time.Now}
}

func (s *Service) Handle(ctx context.Context, command domain.CommandEnvelope) (json.RawMessage, error) {
	if command.ProtocolVersion != domain.ProtocolVersion {
		return nil, fmt.Errorf("unsupported protocol version %d", command.ProtocolVersion)
	}
	if command.DeviceID == "" || command.IdempotencyKey == "" || command.RequestID == "" {
		return nil, fmt.Errorf("device id, request id and idempotency key are required")
	}
	created, existing, err := s.store.BeginCommand(
		command.DeviceID, command.IdempotencyKey, command.RequestID, command.Type, s.now(),
	)
	if err != nil {
		return nil, err
	}
	if !created {
		if existing.Status == "pending" {
			return nil, fmt.Errorf("command is already pending")
		}
		return existing.Result, nil
	}

	result, executeErr := s.executor.Execute(ctx, command)
	status := "completed"
	if executeErr != nil {
		status = "failed"
		result = json.RawMessage(`{"error":"command failed"}`)
	}
	if err := s.store.CompleteCommand(command.DeviceID, command.IdempotencyKey, status, result, s.now()); err != nil {
		return nil, err
	}
	if executeErr != nil {
		return nil, executeErr
	}
	return result, nil
}
