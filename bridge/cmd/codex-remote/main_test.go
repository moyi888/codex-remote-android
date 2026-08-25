package main

import "testing"

func TestVersionTextIncludesProductAndVersion(t *testing.T) {
	got := versionText("0.1.0-test")
	want := "codex-remote 0.1.0-test"
	if got != want {
		t.Fatalf("versionText() = %q, want %q", got, want)
	}
}
