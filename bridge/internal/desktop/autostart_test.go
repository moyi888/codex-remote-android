package desktop

import "testing"

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
