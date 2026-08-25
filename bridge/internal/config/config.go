package config

import (
	"fmt"
	"net"
	"net/netip"
)

var (
	tailscaleIPv4 = netip.MustParsePrefix("100.64.0.0/10")
	tailscaleIPv6 = netip.MustParsePrefix("fd7a:115c:a1e0::/48")
)

func ValidateListenAddress(address string, allowPublic bool) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return fmt.Errorf("invalid listen address: %w", err)
	}
	ip, err := netip.ParseAddr(host)
	if err != nil {
		return fmt.Errorf("listen host must be an IP address: %w", err)
	}
	if ip.IsUnspecified() && !allowPublic {
		return fmt.Errorf("wildcard listen address requires explicit public binding opt-in")
	}
	if allowPublic || ip.IsLoopback() || tailscaleIPv4.Contains(ip) || tailscaleIPv6.Contains(ip) {
		return nil
	}
	return fmt.Errorf("listen address must be loopback or a Tailscale address")
}
