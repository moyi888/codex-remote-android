package desktop

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
)

var (
	ErrTailscaleMissing      = errors.New("tailscale is not installed")
	ErrTailscaleDisconnected = errors.New("tailscale is not connected")
	ErrTailscaleStatus       = errors.New("tailscale status is unavailable")
)

var tailnetIPv4 = netip.MustParsePrefix("100.64.0.0/10")

type CommandRunner interface {
	Run(context.Context, string, ...string) ([]byte, error)
}

type TailscaleStatus struct {
	IP         netip.Addr
	Executable string
}

func DiscoverTailscale(
	ctx context.Context,
	lookup func(string) (string, error),
	programFiles string,
	runner CommandRunner,
) (TailscaleStatus, error) {
	executable, err := lookup("tailscale")
	if err != nil || executable == "" {
		executable = filepath.Join(programFiles, "Tailscale", "tailscale.exe")
		info, statErr := os.Stat(executable)
		if statErr != nil || info.IsDir() {
			return TailscaleStatus{}, ErrTailscaleMissing
		}
	}
	output, err := runner.Run(ctx, executable, "status", "--json")
	if err != nil {
		return TailscaleStatus{}, fmt.Errorf("%w: command failed", ErrTailscaleStatus)
	}
	ip, err := parseTailscaleStatus(output)
	if err != nil {
		return TailscaleStatus{}, err
	}
	return TailscaleStatus{IP: ip, Executable: executable}, nil
}

func parseTailscaleStatus(raw []byte) (netip.Addr, error) {
	var status struct {
		BackendState string   `json:"BackendState"`
		TailscaleIPs []string `json:"TailscaleIPs"`
	}
	if err := json.Unmarshal(raw, &status); err != nil {
		return netip.Addr{}, fmt.Errorf("%w: invalid response", ErrTailscaleStatus)
	}
	if status.BackendState != "Running" {
		return netip.Addr{}, ErrTailscaleDisconnected
	}
	for _, value := range status.TailscaleIPs {
		ip, err := netip.ParseAddr(value)
		if err == nil && ip.Is4() && tailnetIPv4.Contains(ip) {
			return ip, nil
		}
	}
	return netip.Addr{}, ErrTailscaleDisconnected
}
