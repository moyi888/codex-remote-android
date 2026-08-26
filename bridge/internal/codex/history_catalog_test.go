package codex

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestHistoryProjectCatalogPaginatesAndDerivesSafeProjects(t *testing.T) {
	root := t.TempDir()
	first := filepath.Join(root, "one", "app")
	second := filepath.Join(root, "two", "app")
	for _, path := range []string{first, second} {
		if err := os.MkdirAll(path, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	rpc := &pagedThreadRPC{pages: map[string]threadPage{
		"": {
			Data:       []ThreadRecord{{ID: "thread-1", CWD: first}},
			NextCursor: "page-2",
		},
		"page-2": {
			Data: []ThreadRecord{
				{ID: "thread-2", CWD: second},
				{ID: "thread-3", CWD: first},
				{ID: "thread-relative", CWD: "relative"},
				{ID: "thread-missing", CWD: filepath.Join(root, "missing")},
			},
		},
	}}
	catalog := NewHistoryProjectCatalog(rpc)

	threads, err := catalog.Threads(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(threads) != 5 || len(rpc.cursors) != 2 || rpc.cursors[1] != "page-2" {
		t.Fatalf("threads=%d cursors=%v", len(threads), rpc.cursors)
	}
	projects, err := catalog.List(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(projects) != 2 {
		t.Fatalf("projects=%+v", projects)
	}
	for _, project := range projects {
		if !strings.HasPrefix(project.ID, "project-") || strings.Contains(project.ID, root) {
			t.Fatalf("unsafe project id: %q", project.ID)
		}
		if project.DisplayName == "app" {
			t.Fatalf("duplicate base names must be disambiguated: %+v", projects)
		}
	}
	resolved, ok, err := catalog.Resolve(context.Background(), projects[0].ID)
	if err != nil || !ok || resolved.Path != projects[0].Path {
		t.Fatalf("resolved=%+v ok=%v err=%v", resolved, ok, err)
	}
	if _, ok, err := catalog.Resolve(context.Background(), "project-unknown"); err != nil || ok {
		t.Fatalf("unknown project resolved: ok=%v err=%v", ok, err)
	}
}

func TestHistoryProjectCatalogExcludesFiles(t *testing.T) {
	file := filepath.Join(t.TempDir(), "file.txt")
	if err := os.WriteFile(file, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	catalog := NewHistoryProjectCatalog(&pagedThreadRPC{pages: map[string]threadPage{
		"": {Data: []ThreadRecord{{ID: "thread-file", CWD: file}}},
	}})
	projects, err := catalog.List(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(projects) != 0 {
		t.Fatalf("file must not become a project: %+v", projects)
	}
}

type threadPage struct {
	Data       []ThreadRecord `json:"data"`
	NextCursor string         `json:"nextCursor"`
}

type pagedThreadRPC struct {
	pages   map[string]threadPage
	cursors []string
}

func (r *pagedThreadRPC) Call(_ context.Context, method string, params, result any) error {
	if method != "thread/list" {
		return nil
	}
	values, _ := params.(map[string]any)
	cursor, _ := values["cursor"].(string)
	r.cursors = append(r.cursors, cursor)
	raw, err := json.Marshal(r.pages[cursor])
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, result)
}
