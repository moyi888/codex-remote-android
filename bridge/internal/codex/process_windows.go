//go:build windows

package codex

import (
	"os/exec"
	"syscall"
)

const createNoWindow uint32 = 0x08000000

// configureChildProcess prevents console-based Codex launchers from opening
// a visible console when started by the tray GUI application.
func configureChildProcess(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
}
