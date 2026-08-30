package codex

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
)

type threadHistoryReader interface {
	ReadThread(context.Context, string, bool) (json.RawMessage, error)
	ListThreadTurns(context.Context, string, string, int) (json.RawMessage, error)
}

// DesktopReadAdapter reads the same thread view as the open Codex Desktop
// window, while retaining the complete app-server catalog as a fallback.
type DesktopReadAdapter struct {
	Adapter
	base  threadHistoryReader
	tools AppToolsCaller
}

func NewDesktopReadAdapter(base Adapter, tools AppToolsCaller) *DesktopReadAdapter {
	reader, _ := base.(threadHistoryReader)
	return &DesktopReadAdapter{Adapter: base, base: reader, tools: tools}
}

func (a *DesktopReadAdapter) ListThreads(ctx context.Context) ([]domain.ThreadSummary, error) {
	baseThreads, err := a.Adapter.ListThreads(ctx)
	if err != nil {
		return nil, err
	}
	caller := ""
	if len(baseThreads) > 0 {
		caller = baseThreads[0].ID
	}
	var response desktopThreadCatalog
	if err := a.tools.CallTool(ctx, caller, "list_threads", map[string]any{"limit": 50}, &response); err != nil {
		return baseThreads, nil
	}
	byID := make(map[string]domain.ThreadSummary, len(baseThreads)+len(response.Threads))
	for _, thread := range baseThreads {
		byID[thread.ID] = thread
	}
	for _, item := range append(response.PinnedThreads, response.Threads...) {
		if item.ID == "" {
			continue
		}
		thread := byID[item.ID]
		thread.ID = item.ID
		if item.Title != "" {
			thread.Title = item.Title
		} else if item.Summary != "" && thread.Title == "" {
			thread.Title = item.Summary
		}
		thread.State = desktopThreadState(item.Status)
		thread.Source = domain.ThreadSourceDesktop
		if item.UpdatedAt > 0 {
			thread.UpdatedAt = time.Unix(item.UpdatedAt, 0).UTC()
		}
		if thread.ProjectName == "" {
			thread.ProjectName = item.CWD
		}
		byID[item.ID] = thread
	}
	merged := make([]domain.ThreadSummary, 0, len(byID))
	for _, thread := range byID {
		merged = append(merged, thread)
	}
	sort.SliceStable(merged, func(i, j int) bool {
		if !merged[i].UpdatedAt.Equal(merged[j].UpdatedAt) {
			return merged[i].UpdatedAt.After(merged[j].UpdatedAt)
		}
		return merged[i].ID < merged[j].ID
	})
	return merged, nil
}

func (a *DesktopReadAdapter) ReadThread(ctx context.Context, threadID string, includeTurns bool) (json.RawMessage, error) {
	var response json.RawMessage
	if err := a.tools.CallTool(ctx, threadID, "read_thread", map[string]any{
		"threadId": threadID, "includeOutputs": includeTurns,
	}, &response); err != nil {
		if a.base != nil {
			return a.base.ReadThread(ctx, threadID, includeTurns)
		}
		return nil, err
	}
	return response, nil
}

func (a *DesktopReadAdapter) ListThreadTurns(ctx context.Context, threadID, cursor string, limit int) (json.RawMessage, error) {
	arguments := map[string]any{"threadId": threadID, "turnLimit": limit}
	if cursor != "" {
		arguments["cursor"] = cursor
	}
	var response json.RawMessage
	err := a.tools.CallTool(ctx, threadID, "read_thread", arguments, &response)
	if err != nil {
		if a.base != nil {
			return a.base.ListThreadTurns(ctx, threadID, cursor, limit)
		}
		return nil, err
	}
	var root struct {
		Turns []json.RawMessage `json:"turns"`
		Page  struct {
			NextCursor string `json:"nextCursor"`
		} `json:"page"`
		NextCursor string `json:"nextCursor"`
	}
	if err := json.Unmarshal(response, &root); err != nil {
		return nil, fmt.Errorf("decode Codex Desktop history: %w", err)
	}
	nextCursor := root.NextCursor
	if nextCursor == "" {
		nextCursor = root.Page.NextCursor
	}
	return json.Marshal(struct {
		Turns      []json.RawMessage `json:"turns"`
		NextCursor string            `json:"nextCursor,omitempty"`
	}{root.Turns, nextCursor})
}

type desktopThreadCatalog struct {
	PinnedThreads []desktopThread `json:"pinnedThreads"`
	Threads       []desktopThread `json:"threads"`
}

type desktopThread struct {
	ID        string          `json:"id"`
	Title     string          `json:"title"`
	Summary   string          `json:"summary"`
	CWD       string          `json:"cwd"`
	UpdatedAt int64           `json:"updatedAt"`
	Status    json.RawMessage `json:"status"`
}

func desktopThreadState(raw json.RawMessage) domain.ThreadState {
	var value string
	if json.Unmarshal(raw, &value) != nil {
		var object struct {
			Type string `json:"type"`
		}
		_ = json.Unmarshal(raw, &object)
		value = object.Type
	}
	switch value {
	case "active", "running", "inProgress":
		return domain.ThreadRunning
	case "idle", "notLoaded":
		return domain.ThreadIdle
	case "completed":
		return domain.ThreadCompleted
	case "failed", "systemError":
		return domain.ThreadFailed
	default:
		return domain.ThreadDisconnected
	}
}
