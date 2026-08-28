//go:build !windows

package codex

import "os/exec"

func configureChildProcess(*exec.Cmd) {}
