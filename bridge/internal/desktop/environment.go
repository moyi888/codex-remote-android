package desktop

import (
	"context"
	"sync"
)

type TailscaleDiscovery func(context.Context) (TailscaleStatus, error)
type CodexDiscovery func(context.Context, string) (string, error)

type SystemEnvironmentProbe struct {
	mu        sync.RWMutex
	tailscale TailscaleDiscovery
	codex     CodexDiscovery
	explicit  string
	validated string
}

func NewSystemEnvironmentProbe(tailscale TailscaleDiscovery, codex CodexDiscovery) *SystemEnvironmentProbe {
	return &SystemEnvironmentProbe{tailscale: tailscale, codex: codex}
}

func (p *SystemEnvironmentProbe) SetCodexPath(path string) {
	p.mu.Lock()
	p.explicit = path
	p.validated = ""
	p.mu.Unlock()
}

func (p *SystemEnvironmentProbe) Probe(ctx context.Context) Environment {
	tailscale, err := p.tailscale(ctx)
	if err != nil {
		return Environment{TailscaleError: err}
	}
	p.mu.RLock()
	explicit := p.explicit
	validated := p.validated
	p.mu.RUnlock()
	if validated != "" {
		return Environment{Tailscale: tailscale, CodexPath: validated}
	}
	codexPath, err := p.codex(ctx, explicit)
	if err == nil && codexPath != "" {
		p.mu.Lock()
		if p.explicit == explicit {
			p.validated = codexPath
		}
		p.mu.Unlock()
	}
	return Environment{
		Tailscale: tailscale, CodexPath: codexPath, CodexError: err,
	}
}
