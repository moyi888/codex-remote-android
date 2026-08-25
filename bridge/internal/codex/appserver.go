package codex

import (
	"context"
	"encoding/json"
	"fmt"
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
	rpc      RPCClient
	projects []Project
}

func NewAppServerAdapter(rpc RPCClient, projects []Project) *AppServerAdapter {
	return &AppServerAdapter{rpc: rpc, projects: append([]Project(nil), projects...)}
}

func (a *AppServerAdapter) Capabilities() Capabilities {
	return Capabilities{ReadThreads: true, StartTask: true, SendTurn: true, StopTurn: true}
}

func (a *AppServerAdapter) ListProjects(context.Context) ([]domain.ProjectOption, error) {
	result := make([]domain.ProjectOption, 0, len(a.projects))
	for _, project := range a.projects {
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
	var response struct {
		Data []struct {
			ID        string          `json:"id"`
			Name      *string         `json:"name"`
			Preview   string          `json:"preview"`
			CWD       string          `json:"cwd"`
			Status    json.RawMessage `json:"status"`
			UpdatedAt int64           `json:"updatedAt"`
		} `json:"data"`
	}
	if err := a.rpc.Call(ctx, "thread/list", map[string]any{"archived": false, "limit": 100}, &response); err != nil {
		return nil, err
	}
	threads := make([]domain.ThreadSummary, 0, len(response.Data))
	for _, item := range response.Data {
		title := item.Preview
		if item.Name != nil && *item.Name != "" {
			title = *item.Name
		}
		projectID, projectName := a.projectForPath(item.CWD)
		state := domain.ThreadIdle
		if string(item.Status) != `"idle"` && string(item.Status) != `{"type":"idle"}` {
			state = domain.ThreadRunning
		}
		threads = append(threads, domain.ThreadSummary{
			ID: item.ID, Title: title, ProjectID: projectID, ProjectName: projectName,
			Source: domain.ThreadSourceAppServer, State: state, UpdatedAt: time.Unix(item.UpdatedAt, 0).UTC(),
		})
	}
	return threads, nil
}

func (a *AppServerAdapter) StartTask(ctx context.Context, request StartTaskRequest) (domain.ThreadSummary, error) {
	project, ok := a.findProject(request.ProjectID)
	if !ok {
		return domain.ThreadSummary{}, fmt.Errorf("project %q is not allowed", request.ProjectID)
	}
	var threadResponse struct {
		Thread struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	if err := a.rpc.Call(ctx, "thread/start", map[string]any{
		"cwd": project.Path, "model": optionalString(request.Model),
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
	return domain.ThreadSummary{
		ID: threadResponse.Thread.ID, Title: request.Prompt,
		ProjectID: project.ID, ProjectName: project.DisplayName,
		Source: domain.ThreadSourceAppServer, State: domain.ThreadRunning, UpdatedAt: time.Now().UTC(),
	}, nil
}

func (a *AppServerAdapter) SendTurn(ctx context.Context, request SendTurnRequest) error {
	var response any
	return a.rpc.Call(ctx, "turn/start", map[string]any{
		"threadId": request.ThreadID,
		"input":    []map[string]any{{"type": "text", "text": request.Prompt, "text_elements": []any{}}},
	}, &response)
}

func (a *AppServerAdapter) Steer(context.Context, SendTurnRequest) error {
	return fmt.Errorf("steering requires an observed active turn id")
}

func (a *AppServerAdapter) StopTurn(context.Context, string) error {
	return fmt.Errorf("stopping requires an observed active turn id")
}

func (a *AppServerAdapter) findProject(id string) (Project, bool) {
	for _, project := range a.projects {
		if project.ID == id {
			return project, true
		}
	}
	return Project{}, false
}

func (a *AppServerAdapter) projectForPath(path string) (string, string) {
	for _, project := range a.projects {
		if project.Path == path {
			return project.ID, project.DisplayName
		}
	}
	return "", path
}

func optionalString(value string) any {
	if value == "" {
		return nil
	}
	return value
}
