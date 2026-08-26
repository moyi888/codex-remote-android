package codex

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadProjectsAcceptsStrictAbsoluteDirectoryRegistry(t *testing.T) {
	projectPath := t.TempDir()
	registry := writeRegistry(t, `[{"id":"app","displayName":"手机应用","path":`+quote(projectPath)+`}]`)

	projects, err := LoadProjects(registry)
	if err != nil {
		t.Fatal(err)
	}
	canonical, err := filepath.EvalSymlinks(projectPath)
	if err != nil {
		t.Fatal(err)
	}
	if len(projects) != 1 || projects[0].ID != "app" || projects[0].DisplayName != "手机应用" || projects[0].Path != canonical {
		t.Fatalf("unexpected projects: %+v", projects)
	}
}

func TestLoadProjectsRejectsUnsafeOrAmbiguousEntries(t *testing.T) {
	directory := t.TempDir()
	filePath := filepath.Join(directory, "file.txt")
	if err := os.WriteFile(filePath, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	cases := map[string]string{
		"empty registry":        `[]`,
		"empty id":              `[{"id":"","displayName":"App","path":` + quote(directory) + `}]`,
		"empty display name":    `[{"id":"app","displayName":" ","path":` + quote(directory) + `}]`,
		"relative path":         `[{"id":"app","displayName":"App","path":"relative"}]`,
		"missing path":          `[{"id":"app","displayName":"App","path":` + quote(filepath.Join(directory, "missing")) + `}]`,
		"path is file":          `[{"id":"app","displayName":"App","path":` + quote(filePath) + `}]`,
		"unknown field":         `[{"id":"app","displayName":"App","path":` + quote(directory) + `,"token":"secret"}]`,
		"duplicate id":          `[{"id":"app","displayName":"A","path":` + quote(directory) + `},{"id":"app","displayName":"B","path":` + quote(directory) + `}]`,
		"trailing json content": `[{"id":"app","displayName":"A","path":` + quote(directory) + `}] {}`,
	}
	for name, raw := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := LoadProjects(writeRegistry(t, raw)); err == nil {
				t.Fatal("expected registry to be rejected")
			}
		})
	}
}

func writeRegistry(t *testing.T, raw string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "projects.json")
	if err := os.WriteFile(path, []byte(raw), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func quote(value string) string {
	return `"` + strings.ReplaceAll(value, `\`, `\\`) + `"`
}
