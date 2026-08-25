package config

import "testing"

func TestValidateListenAddressRejectsWildcardByDefault(t *testing.T) {
	err := ValidateListenAddress("0.0.0.0:8787", false)
	if err == nil {
		t.Fatal("expected wildcard listen address to be rejected")
	}
}

func TestValidateListenAddressAcceptsTailscaleAddress(t *testing.T) {
	if err := ValidateListenAddress("100.88.10.20:8787", false); err != nil {
		t.Fatalf("expected Tailscale address to be accepted: %v", err)
	}
}
