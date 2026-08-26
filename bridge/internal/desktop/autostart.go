package desktop

import (
	"fmt"
	"path/filepath"
	"strings"
)

type AutostartPreference interface {
	AutostartStore
	Configured() (bool, error)
	MarkConfigured() error
}

func ensureDefaultAutostart(store AutostartPreference) error {
	configured, err := store.Configured()
	if err != nil || configured {
		return err
	}
	if err := store.SetEnabled(true); err != nil {
		return err
	}
	return store.MarkConfigured()
}

func autostartCommand(executable string) (string, error) {
	if executable == "" || !filepath.IsAbs(executable) || strings.Contains(executable, `"`) {
		return "", fmt.Errorf("autostart executable path is invalid")
	}
	return `"` + filepath.Clean(executable) + `"`, nil
}

func autostartValueMatches(value, executable string) bool {
	command, err := autostartCommand(executable)
	return err == nil && value == command
}
