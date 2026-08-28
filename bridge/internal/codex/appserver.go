package codex

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
)

type RPCClient interface {
	Call(context.Context, string, any, any) error
}

type Project struct {
	ID          string
	DisplayName string
	Path        string
}

type AppServerAdapter struct {
	rpc          RPCClient
	catalog      ProjectCatalog
	activeTurnMu sync.Mutex
	activeTurns  map[string]string
}

func NewAppServerAdapter(rpc RPCClient, projects []Project) *AppServerAdapter {
	return &AppServerAdapter{rpc: rpc, catalog: NewStaticProjectCatalog(rpc, projects), activeTurns: make(map[string]string)}
}

func NewHistoryAppServerAdapter(rpc RPCClient) *AppServerAdapter {
	return &AppServerAdapter{rpc: rpc, catalog: NewHistoryProjectCatalog(rpc), activeTurns: make(map[string]string)}
}

func (a *AppServerAdapter) Capabilities() Capabilities {
	return Capabilities{ReadThreads: true, StartTask: true, SendTurn: true, Steer: true, StopTurn: true}
}

func (a *AppServerAdapter) ListProjects(ctx context.Context) ([]domain.ProjectOption, error) {
	projects, err := a.catalog.List(ctx)
	if err != nil {
		return nil, err
	}
	result := make([]domain.ProjectOption, 0, len(projects))
	for _, project := range projects {
		result = append(result, domain.ProjectOption{ID: project.ID, DisplayName: project.DisplayName})
	}
	return result, nil
}

func (a *AppServerAdapter) ListModels(ctx context.Context) ([]domain.ModelOption, error) {
	var response struct {
		Data []struct {
			ID                        string `json:"id"`
			DisplayName               string `json:"displayName"`
			SupportedReasoningEfforts []struct {
				ReasoningEffort string `json:"reasoningEffort"`
				Description     string `json:"description"`
			} `json:"supportedReasoningEfforts"`
		} `json:"data"`
	}
	if err := a.rpc.Call(ctx, "model/list", map[string]any{"includeHidden": false}, &response); err != nil {
		return nil, err
	}
	models := make([]domain.ModelOption, 0, len(response.Data))
	for _, item := range response.Data {
		model := domain.ModelOption{
			ID:               item.ID,
			DisplayName:      item.DisplayName,
			ReasoningOptions: make([]domain.ReasoningOption, 0, len(item.SupportedReasoningEfforts)),
		}
		for _, effort := range item.SupportedReasoningEfforts {
			model.ReasoningOptions = append(model.ReasoningOptions, domain.ReasoningOption{
				ID: effort.ReasoningEffort, DisplayName: effort.Description,
			})
		}
		models = append(models, model)
	}
	return models, nil
}

func (a *AppServerAdapter) ListThreads(ctx context.Context) ([]domain.ThreadSummary, error) {
	records, err := a.catalog.Threads(ctx)
	if err != nil {
		return nil, err
	}
	threads := make([]domain.ThreadSummary, 0, len(records))
	for _, item := range records {
		title := item.Preview
		if item.Name != nil && *item.Name != "" {
			title = *item.Name
		}
		projectID, projectName := "", item.CWD
		if project, ok := a.catalog.ProjectForPath(item.CWD); ok {
			projectID, projectName = project.ID, project.DisplayName
		}
		threads = append(threads, domain.ThreadSummary{
			ID: item.ID, Title: title, ProjectID: projectID, ProjectName: projectName,
			Source: domain.ThreadSourceAppServer, State: threadStateFromStatus(item.Status), UpdatedAt: time.Unix(item.UpdatedAt, 0).UTC(),
			ActiveTurnID: a.currentActiveTurnID(item.ID),
		})
	}
	return threads, nil
}

func (a *AppServerAdapter) currentActiveTurnID(threadID string) string {
	a.activeTurnMu.Lock()
	defer a.activeTurnMu.Unlock()
	return a.activeTurns[threadID]
}

// ReadThread exposes the persisted app-server conversation for detail views.
// The raw payload preserves new Codex item types without lossy translation.
func (a *AppServerAdapter) ReadThread(ctx context.Context, threadID string, includeTurns bool) (json.RawMessage, error) {
	if threadID == "" {
		return nil, fmt.Errorf("thread id is required")
	}
	var result json.RawMessage
	if err := a.rpc.Call(ctx, "thread/read", map[string]any{
		"threadId": threadID, "includeTurns": includeTurns,
	}, &result); err != nil {
		return nil, err
	}
	return result, nil
}

func (a *AppServerAdapter) ListThreadTurns(ctx context.Context, threadID, cursor string, limit int) (json.RawMessage, error) {
	if threadID == "" {
		return nil, fmt.Errorf("thread id is required")
	}
	params := map[string]any{"threadId": threadID, "itemsView": "full", "sortDirection": "desc"}
	if cursor != "" {
		params["cursor"] = cursor
	}
	if limit > 0 {
		params["limit"] = limit
	}
	var result json.RawMessage
	if err := a.rpc.Call(ctx, "thread/turns/list", params, &result); err != nil {
		return nil, err
	}
	return result, nil
}

func threadStateFromStatus(raw json.RawMessage) domain.ThreadState {
	var statusType string
	if err := json.Unmarshal(raw, &statusType); err != nil {
		var status struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal(raw, &status); err != nil {
			return domain.ThreadDisconnected
		}
		statusType = status.Type
	}
	switch statusType {
	case "active":
		return domain.ThreadRunning
	case "idle", "notLoaded":
		return domain.ThreadIdle
	case "systemError":
		return domain.ThreadFailed
	default:
		return domain.ThreadDisconnected
	}
}

func (a *AppServerAdapter) StartTask(ctx context.Context, request StartTaskRequest) (domain.ThreadSummary, error) {
	project, ok, err := a.catalog.Resolve(ctx, request.ProjectID)
	if err != nil {
		return domain.ThreadSummary{}, err
	}
	if !ok {
		return domain.ThreadSummary{}, fmt.Errorf("project %q is not allowed", request.ProjectID)
	}
	var threadResponse struct {
		Thread struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	if err := a.rpc.Call(ctx, "thread/start", map[string]any{
		"cwd":            project.Path,
		"model":          optionalString(request.Model),
		"approvalPolicy": "never",
		"sandbox":        "dangerFullAccess",
	}, &threadResponse); err != nil {
		return domain.ThreadSummary{}, err
	}
	var turnResponse struct {
		Turn struct {
			ID string `json:"id"`
		} `json:"turn"`
	}
	if err := a.rpc.Call(ctx, "turn/start", map[string]any{
		"threadId": threadResponse.Thread.ID,
		"input":    []map[string]any{{"type": "text", "text": request.Prompt, "text_elements": []any{}}},
		"model":    optionalString(request.Model),
		"effort":   optionalString(request.Reasoning),
	}, &turnResponse); err != nil {
		return domain.ThreadSummary{}, err
	}
	a.setActiveTurn(threadResponse.Thread.ID, turnResponse.Turn.ID)
	return domain.ThreadSummary{
		ID: threadResponse.Thread.ID, Title: request.Prompt,
		ProjectID: project.ID, ProjectName: project.DisplayName,
		Source: domain.ThreadSourceAppServer, State: domain.ThreadRunning, UpdatedAt: time.Now().UTC(),
		ActiveTurnID: turnResponse.Turn.ID,
	}, nil
}

func (a *AppServerAdapter) SendTurn(ctx context.Context, request SendTurnRequest) error {
	var resumed any
	if err := a.rpc.Call(ctx, "thread/resume", map[string]any{
		"threadId": request.ThreadID,
	}, &resumed); err != nil {
		return err
	}
	var response struct {
		Turn struct {
			ID string `json:"id"`
		} `json:"turn"`
	}
	if err := a.rpc.Call(ctx, "turn/start", map[string]any{
		"threadId": request.ThreadID,
		"input":    []map[string]any{{"type": "text", "text": request.Prompt, "text_elements": []any{}}},
	}, &response); err != nil {
		return err
	}
	a.setActiveTurn(request.ThreadID, response.Turn.ID)
	return nil
}

func (a *AppServerAdapter) Steer(ctx context.Context, request SendTurnRequest) error {
	turnID, err := a.activeTurnID(ctx, request.ThreadID)
	if err != nil {
		return err
	}
	var response struct {
		Turn struct {
			ID string `json:"id"`
		} `json:"turn"`
	}
	if err := a.rpc.Call(ctx, "turn/steer", map[string]any{
		"threadId":       request.ThreadID,
		"expectedTurnId": turnID,
		"input":          []map[string]any{{"type": "text", "text": request.Prompt, "text_elements": []any{}}},
	}, &response); err != nil {
		return err
	}
	if response.Turn.ID != "" {
		a.setActiveTurn(request.ThreadID, response.Turn.ID)
	}
	return nil
}

func (a *AppServerAdapter) StopTurn(ctx context.Context, threadID string) error {
	turnID, err := a.activeTurnID(ctx, threadID)
	if err != nil {
		return err
	}
	var response any
	if err := a.rpc.Call(ctx, "turn/interrupt", map[string]any{
		"threadId": threadID, "turnId": turnID,
	}, &response); err != nil {
		return err
	}
	a.clearActiveTurn(threadID, turnID)
	return nil
}

func (a *AppServerAdapter) setActiveTurn(threadID, turnID string) {
	if threadID == "" || turnID == "" {
		return
	}
	a.activeTurnMu.Lock()
	if a.activeTurns == nil {
		a.activeTurns = make(map[string]string)
	}
	a.activeTurns[threadID] = turnID
	a.activeTurnMu.Unlock()
}

func (a *AppServerAdapter) clearActiveTurn(threadID, turnID string) {
	a.activeTurnMu.Lock()
	if a.activeTurns[threadID] == turnID {
		delete(a.activeTurns, threadID)
	}
	a.activeTurnMu.Unlock()
}

func (a *AppServerAdapter) activeTurnID(ctx context.Context, threadID string) (string, error) {
	a.activeTurnMu.Lock()
	knownTurnID := a.activeTurns[threadID]
	a.activeTurnMu.Unlock()
	var response struct {
		Thread struct {
			Turns []struct {
				ID     string          `json:"id"`
				Status json.RawMessage `json:"status"`
			} `json:"turns"`
		} `json:"thread"`
	}
	if err := a.rpc.Call(ctx, "thread/read", map[string]any{
		"threadId": threadID, "includeTurns": true,
	}, &response); err != nil {
		return "", err
	}
	for index := len(response.Thread.Turns) - 1; index >= 0; index-- {
		candidate := response.Thread.Turns[index]
		if candidate.ID != "" && turnIsActive(candidate.Status) {
			a.setActiveTurn(threadID, candidate.ID)
			return candidate.ID, nil
		}
	}
	if knownTurnID != "" {
		a.clearActiveTurn(threadID, knownTurnID)
	}
	return "", fmt.Errorf("thread %q has no active turn", threadID)
}

func turnIsActive(raw json.RawMessage) bool {
	var status string
	if json.Unmarshal(raw, &status) == nil {
		return status == "active" || status == "inProgress" || status == "running"
	}
	var object struct {
		Type string `json:"type"`
	}
	if json.Unmarshal(raw, &object) != nil {
		return false
	}
	return object.Type == "active" || object.Type == "inProgress" || object.Type == "running"
}

func optionalString(value string) any {
	if value == "" {
		return nil
	}
	return value
}
