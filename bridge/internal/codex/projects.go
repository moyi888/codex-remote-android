package codex

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

func LoadProjects(registryPath string) ([]Project, error) {
	file, err := os.Open(registryPath)
	if err != nil {
		return nil, fmt.Errorf("open project registry: %w", err)
	}
	defer file.Close()

	decoder := json.NewDecoder(file)
	decoder.DisallowUnknownFields()
	var projects []Project
	if err := decoder.Decode(&projects); err != nil {
		return nil, fmt.Errorf("decode project registry: %w", err)
	}
	if err := requireJSONEnd(decoder); err != nil {
		return nil, err
	}
	if len(projects) == 0 {
		return nil, fmt.Errorf("project registry must contain at least one project")
	}

	seen := make(map[string]struct{}, len(projects))
	validated := make([]Project, 0, len(projects))
	for index, project := range projects {
		project.ID = strings.TrimSpace(project.ID)
		project.DisplayName = strings.TrimSpace(project.DisplayName)
		if project.ID == "" || project.DisplayName == "" {
			return nil, fmt.Errorf("project %d requires id and displayName", index)
		}
		if _, exists := seen[project.ID]; exists {
			return nil, fmt.Errorf("duplicate project id %q", project.ID)
		}
		if !filepath.IsAbs(project.Path) {
			return nil, fmt.Errorf("project %q path must be absolute", project.ID)
		}
		canonical, err := filepath.EvalSymlinks(filepath.Clean(project.Path))
		if err != nil {
			return nil, fmt.Errorf("resolve project %q path: %w", project.ID, err)
		}
		info, err := os.Stat(canonical)
		if err != nil {
			return nil, fmt.Errorf("stat project %q path: %w", project.ID, err)
		}
		if !info.IsDir() {
			return nil, fmt.Errorf("project %q path must be a directory", project.ID)
		}
		project.Path = canonical
		seen[project.ID] = struct{}{}
		validated = append(validated, project)
	}
	return validated, nil
}

func requireJSONEnd(decoder *json.Decoder) error {
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		if err == nil {
			return fmt.Errorf("project registry contains trailing JSON content")
		}
		return fmt.Errorf("decode project registry trailing content: %w", err)
	}
	return nil
}
