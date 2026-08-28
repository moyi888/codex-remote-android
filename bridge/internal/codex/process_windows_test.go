//go:build windows

package codex

import (
	"os/exec"
	"testing"
)

func TestConfigureChildProcessHidesConsoleWindow(t *testing.T) {
	cmd := exec.Command("codex", "app-server")
	configureChildProcess(cmd)

	attributes := cmd.SysProcAttr
	if attributes == nil || !attributes.HideWindow || attributes.CreationFlags&createNoWindow == 0 {
		t.Fatalf("child process must hide its console window: %#v", cmd.SysProcAttr)
	}
}
