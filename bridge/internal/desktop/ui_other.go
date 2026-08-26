//go:build !windows

package desktop

import "fmt"

func RunApplication(string) error {
	return fmt.Errorf("Codex Remote desktop app is only supported on Windows")
}
