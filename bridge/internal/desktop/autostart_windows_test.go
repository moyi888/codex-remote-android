//go:build windows

package desktop

import "testing"

func TestAutostartCommandQuotesAbsoluteExecutable(t *testing.T) {
	got, err := autostartCommand(`C:\Program Files\Codex Remote\codex-remote.exe`)
	if err != nil {
		t.Fatal(err)
	}
	if got != `"C:\Program Files\Codex Remote\codex-remote.exe"` {
		t.Fatalf("命令 = %q", got)
	}
}

func TestAutostartCommandRejectsUnsafePaths(t *testing.T) {
	for _, path := range []string{"", `codex-remote.exe`, `C:\bad"path\codex-remote.exe`} {
		if _, err := autostartCommand(path); err == nil {
			t.Fatalf("路径应被拒绝: %q", path)
		}
	}
}

func TestAutostartValueMustMatchCurrentExecutable(t *testing.T) {
	current := `C:\Program Files\Codex Remote\codex-remote.exe`
	if !autostartValueMatches(`"C:\Program Files\Codex Remote\codex-remote.exe"`, current) {
		t.Fatal("当前程序的安全引用应匹配")
	}
	if autostartValueMatches(`"D:\Old\codex-remote.exe"`, current) {
		t.Fatal("旧安装路径不应匹配")
	}
}
