package codex

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

type ThreadRecord struct {
	ID        string          `json:"id"`
	Name      *string         `json:"name"`
	Preview   string          `json:"preview"`
	CWD       string          `json:"cwd"`
	Status    json.RawMessage `json:"status"`
	UpdatedAt int64           `json:"updatedAt"`
}

type HistoryProjectCatalog struct {
	rpc RPCClient
}

type ProjectCatalog interface {
	Threads(context.Context) ([]ThreadRecord, error)
	List(context.Context) ([]Project, error)
	Resolve(context.Context, string) (Project, bool, error)
	ProjectForPath(string) (Project, bool)
}

type StaticProjectCatalog struct {
	rpc      RPCClient
	projects []Project
}

func NewStaticProjectCatalog(rpc RPCClient, projects []Project) *StaticProjectCatalog {
	return &StaticProjectCatalog{rpc: rpc, projects: append([]Project(nil), projects...)}
}

func NewHistoryProjectCatalog(rpc RPCClient) *HistoryProjectCatalog {
	return &HistoryProjectCatalog{rpc: rpc}
}

func (c *HistoryProjectCatalog) Threads(ctx context.Context) ([]ThreadRecord, error) {
	return listThreadRecords(ctx, c.rpc)
}

func listThreadRecords(ctx context.Context, rpc RPCClient) ([]ThreadRecord, error) {
	var threads []ThreadRecord
	cursor := ""
	seenCursors := map[string]struct{}{"": {}}
	for {
		params := map[string]any{"archived": false, "limit": 100}
		if cursor != "" {
			params["cursor"] = cursor
		}
		var page struct {
			Data       []ThreadRecord `json:"data"`
			NextCursor string         `json:"nextCursor"`
		}
		if err := rpc.Call(ctx, "thread/list", params, &page); err != nil {
			return nil, err
		}
		threads = append(threads, page.Data...)
		if page.NextCursor == "" {
			return threads, nil
		}
		if _, exists := seenCursors[page.NextCursor]; exists {
			return nil, fmt.Errorf("thread list returned a repeated cursor")
		}
		seenCursors[page.NextCursor] = struct{}{}
		cursor = page.NextCursor
	}
}

func (c *StaticProjectCatalog) Threads(ctx context.Context) ([]ThreadRecord, error) {
	return listThreadRecords(ctx, c.rpc)
}

func (c *StaticProjectCatalog) List(context.Context) ([]Project, error) {
	return append([]Project(nil), c.projects...), nil
}

func (c *StaticProjectCatalog) Resolve(_ context.Context, id string) (Project, bool, error) {
	for _, project := range c.projects {
		if project.ID == id {
			return project, true, nil
		}
	}
	return Project{}, false, nil
}

func (c *StaticProjectCatalog) ProjectForPath(path string) (Project, bool) {
	for _, project := range c.projects {
		if canonicalPathKey(project.Path) == canonicalPathKey(path) {
			return project, true
		}
	}
	return Project{}, false
}

func (c *HistoryProjectCatalog) List(ctx context.Context) ([]Project, error) {
	threads, err := c.Threads(ctx)
	if err != nil {
		return nil, err
	}
	projects := make([]Project, 0, len(threads))
	seen := make(map[string]struct{}, len(threads))
	for _, thread := range threads {
		project, ok := historyProject(thread.CWD)
		if !ok {
			continue
		}
		key := canonicalPathKey(project.Path)
		if _, exists := seen[key]; exists {
			continue
		}
		seen[key] = struct{}{}
		projects = append(projects, project)
	}
	disambiguateProjectNames(projects)
	return projects, nil
}

func (c *HistoryProjectCatalog) Resolve(ctx context.Context, id string) (Project, bool, error) {
	projects, err := c.List(ctx)
	if err != nil {
		return Project{}, false, err
	}
	for _, project := range projects {
		if project.ID == id {
			return project, true, nil
		}
	}
	return Project{}, false, nil
}

func (c *HistoryProjectCatalog) ProjectForPath(path string) (Project, bool) {
	return historyProject(path)
}

func historyProject(path string) (Project, bool) {
	if !filepath.IsAbs(path) {
		return Project{}, false
	}
	canonical, err := filepath.EvalSymlinks(filepath.Clean(path))
	if err != nil {
		return Project{}, false
	}
	info, err := os.Stat(canonical)
	if err != nil || !info.IsDir() {
		return Project{}, false
	}
	key := canonicalPathKey(canonical)
	digest := sha256.Sum256([]byte(key))
	return Project{
		ID:          "project-" + hex.EncodeToString(digest[:12]),
		DisplayName: filepath.Base(canonical),
		Path:        canonical,
	}, true
}

func canonicalPathKey(path string) string {
	clean := filepath.Clean(path)
	if runtime.GOOS == "windows" {
		return strings.ToLower(clean)
	}
	return clean
}

func disambiguateProjectNames(projects []Project) {
	counts := make(map[string]int, len(projects))
	for _, project := range projects {
		counts[strings.ToLower(project.DisplayName)]++
	}
	for index := range projects {
		if counts[strings.ToLower(projects[index].DisplayName)] > 1 {
			projects[index].DisplayName += " (" + filepath.Dir(projects[index].Path) + ")"
		}
	}
}
