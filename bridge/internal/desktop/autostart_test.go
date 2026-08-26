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

func TestEnsureDefaultAutostartOnlyChangesUnconfiguredStore(t *testing.T) {
	unconfigured := &fakeAutostartPreference{}
	if err := ensureDefaultAutostart(unconfigured); err != nil {
		t.Fatal(err)
	}
	if !unconfigured.enabled || !unconfigured.configured || unconfigured.setCalls != 1 {
		t.Fatalf("首次配置 = %+v", unconfigured)
	}
	configuredOff := &fakeAutostartPreference{configured: true, enabled: false}
	if err := ensureDefaultAutostart(configuredOff); err != nil {
		t.Fatal(err)
	}
	if configuredOff.enabled || configuredOff.setCalls != 0 {
		t.Fatalf("已关闭配置被覆盖 = %+v", configuredOff)
	}
}

type fakeAutostartPreference struct {
	configured bool
	enabled    bool
	setCalls   int
}

func (f *fakeAutostartPreference) Enabled() (bool, error) { return f.enabled, nil }
func (f *fakeAutostartPreference) SetEnabled(enabled bool) error {
	f.enabled = enabled
	f.setCalls++
	return nil
}
func (f *fakeAutostartPreference) Configured() (bool, error) { return f.configured, nil }
func (f *fakeAutostartPreference) MarkConfigured() error {
	f.configured = true
	return nil
}
