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

const (
	// Codex Desktop's read_thread tool currently accepts at most ten turns.
	// Keep the Desktop path within that contract; the app-server fallback is
	// still used whenever the optional Desktop tool cannot serve the request.
	desktopHistoryPageLimit       = 10
	desktopHistoryOutputCharLimit = 12000
	// Desktop app-tools is an optional title/status overlay. It must never hold
	// the initial mobile snapshot hostage when the Codex UI is busy or closed.
	desktopCatalogOverlayTimeout = 2 * time.Second
)

func NewDesktopReadAdapter(base Adapter, tools AppToolsCaller) *DesktopReadAdapter {
	reader, _ := base.(threadHistoryReader)
	return &DesktopReadAdapter{Adapter: base, base: reader, tools: tools}
}

func (a *DesktopReadAdapter) ListThreads(ctx context.Context) ([]domain.ThreadSummary, error) {
	baseThreads, err := a.Adapter.ListThreads(ctx)
	if err != nil {
		return nil, err
	}
	return a.overlayDesktopThreads(ctx, baseThreads), nil
}

// Snapshot uses the base adapter's combined read path when available, then
// applies the best-effort Desktop title/status overlay. This keeps pairing
// responsive even when the app-tools named pipe is unavailable.
func (a *DesktopReadAdapter) Snapshot(ctx context.Context) (SnapshotData, error) {
	reader, ok := a.Adapter.(SnapshotReader)
	if !ok {
		projects, err := a.ListProjects(ctx)
		if err != nil {
			return SnapshotData{}, err
		}
		models, err := a.ListModels(ctx)
		if err != nil {
			return SnapshotData{}, err
		}
		threads, err := a.ListThreads(ctx)
		if err != nil {
			return SnapshotData{}, err
		}
		return SnapshotData{Projects: projects, Models: models, Threads: threads}, nil
	}
	data, err := reader.Snapshot(ctx)
	if err != nil {
		return SnapshotData{}, err
	}
	data.Threads = a.overlayDesktopThreads(ctx, data.Threads)
	return data, nil
}

func (a *DesktopReadAdapter) overlayDesktopThreads(ctx context.Context, baseThreads []domain.ThreadSummary) []domain.ThreadSummary {
	if a.tools == nil {
		return baseThreads
	}
	caller := ""
	if len(baseThreads) > 0 {
		caller = baseThreads[0].ID
	}
	var response desktopThreadCatalog
	overlayCtx, cancel := context.WithTimeout(ctx, desktopCatalogOverlayTimeout)
	defer cancel()
	if err := a.tools.CallTool(overlayCtx, caller, "list_threads", map[string]any{"limit": 50}, &response); err != nil {
		return baseThreads
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
	return merged
}

func (a *DesktopReadAdapter) ReadThread(ctx context.Context, threadID string, includeTurns bool) (json.RawMessage, error) {
	var response json.RawMessage
	if a.tools == nil {
		if a.base != nil {
			return a.base.ReadThread(ctx, threadID, includeTurns)
		}
		return nil, fmt.Errorf("thread history is not configured")
	}
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
	if a.tools == nil {
		if a.base != nil {
			return a.base.ListThreadTurns(ctx, threadID, cursor, limit)
		}
		return nil, fmt.Errorf("thread history is not configured")
	}
	pageLimit := limit
	if pageLimit <= 0 || pageLimit > desktopHistoryPageLimit {
		pageLimit = desktopHistoryPageLimit
	}
	arguments := map[string]any{
		"threadId":  threadID,
		"turnLimit": pageLimit,
		// Conversation messages and status are still included; omitting command
		// execution output keeps mobile history responses bounded.
		"includeOutputs":        false,
		"maxOutputCharsPerItem": desktopHistoryOutputCharLimit,
	}
	if cursor != "" {
		arguments["cursor"] = cursor
	}
	var response json.RawMessage
	err := a.tools.CallTool(ctx, threadID, "read_thread", arguments, &response)
	if err != nil {
		return a.listThreadTurnsFallback(ctx, threadID, cursor, pageLimit, err)
	}
	var root struct {
		Turns  []json.RawMessage `json:"turns"`
		Thread struct {
			Turns []json.RawMessage `json:"turns"`
		} `json:"thread"`
		Page struct {
			NextCursor string `json:"nextCursor"`
		} `json:"page"`
		NextCursor string `json:"nextCursor"`
	}
	if err := json.Unmarshal(response, &root); err != nil {
		return a.listThreadTurnsFallback(ctx, threadID, cursor, pageLimit, err)
	}
	if len(root.Turns) == 0 {
		root.Turns = root.Thread.Turns
	}
	// Desktop can report success with an empty payload when a thread is not
	// currently loaded. Treat the app-server as authoritative for that case.
	if len(root.Turns) == 0 && a.base != nil {
		return a.base.ListThreadTurns(ctx, threadID, cursor, pageLimit)
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

func (a *DesktopReadAdapter) listThreadTurnsFallback(
	ctx context.Context,
	threadID, cursor string,
	limit int,
	toolErr error,
) (json.RawMessage, error) {
	if a.base != nil {
		if payload, err := a.base.ListThreadTurns(ctx, threadID, cursor, limit); err == nil {
			return payload, nil
		}
	}
	return nil, toolErr
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
