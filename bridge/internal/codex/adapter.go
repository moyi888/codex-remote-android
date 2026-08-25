package codex

import (
	"context"

	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
)

type Capabilities struct {
	ReadThreads bool `json:"readThreads"`
	StartTask   bool `json:"startTask"`
	SendTurn    bool `json:"sendTurn"`
	Steer       bool `json:"steer"`
	StopTurn    bool `json:"stopTurn"`
}

type StartTaskRequest struct {
	ProjectID string `json:"projectId"`
	Prompt    string `json:"prompt"`
	Model     string `json:"model,omitempty"`
	Reasoning string `json:"reasoning,omitempty"`
}

type SendTurnRequest struct {
	ThreadID string `json:"threadId"`
	Prompt   string `json:"prompt"`
}

type Adapter interface {
	Capabilities() Capabilities
	ListThreads(context.Context) ([]domain.ThreadSummary, error)
	ListProjects(context.Context) ([]domain.ProjectOption, error)
	ListModels(context.Context) ([]domain.ModelOption, error)
	StartTask(context.Context, StartTaskRequest) (domain.ThreadSummary, error)
	SendTurn(context.Context, SendTurnRequest) error
	Steer(context.Context, SendTurnRequest) error
	StopTurn(context.Context, string) error
}
