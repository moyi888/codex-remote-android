package codex

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
)

type FakeAdapter struct {
	mu      sync.Mutex
	threads []domain.ThreadSummary
	nextID  int
}

func NewFakeAdapter() *FakeAdapter { return &FakeAdapter{nextID: 1} }

func (f *FakeAdapter) Capabilities() Capabilities {
	return Capabilities{ReadThreads: true, StartTask: true, SendTurn: true, Steer: true, StopTurn: true}
}

func (f *FakeAdapter) ListThreads(context.Context) ([]domain.ThreadSummary, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]domain.ThreadSummary(nil), f.threads...), nil
}

func (f *FakeAdapter) ListProjects(context.Context) ([]domain.ProjectOption, error) {
	return []domain.ProjectOption{{ID: "project-1", DisplayName: "示例项目"}}, nil
}

func (f *FakeAdapter) ListModels(context.Context) ([]domain.ModelOption, error) {
	return []domain.ModelOption{{
		ID: "gpt-test", DisplayName: "Test Model",
		ReasoningOptions: []domain.ReasoningOption{{ID: "high", DisplayName: "High"}},
	}}, nil
}

func (f *FakeAdapter) StartTask(_ context.Context, request StartTaskRequest) (domain.ThreadSummary, error) {
	if request.ProjectID == "" || request.Prompt == "" {
		return domain.ThreadSummary{}, fmt.Errorf("project and prompt are required")
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	thread := domain.ThreadSummary{
		ID: fmt.Sprintf("fake-thread-%d", f.nextID), Title: request.Prompt,
		ProjectID: request.ProjectID, ProjectName: "示例项目",
		Source: domain.ThreadSourceAppServer, State: domain.ThreadRunning, UpdatedAt: time.Now().UTC(),
	}
	f.nextID++
	f.threads = append(f.threads, thread)
	return thread, nil
}

func (f *FakeAdapter) SendTurn(context.Context, SendTurnRequest) error { return nil }
func (f *FakeAdapter) Steer(context.Context, SendTurnRequest) error    { return nil }
func (f *FakeAdapter) StopTurn(context.Context, string) error          { return nil }
