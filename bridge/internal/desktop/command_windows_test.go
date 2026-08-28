//go:build windows

package desktop

import (
	"os/exec"
	"testing"
)

func TestConfigureHiddenCommandHidesConsoleWindow(t *testing.T) {
	cmd := exec.Command("tailscale", "status", "--json")
	configureHiddenCommand(cmd)

	attributes := cmd.SysProcAttr
	if attributes == nil || !attributes.HideWindow || attributes.CreationFlags&createNoWindow == 0 {
		t.Fatalf("CLI command must hide its console window: %#v", cmd.SysProcAttr)
	}
}
