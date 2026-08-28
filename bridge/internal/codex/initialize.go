package codex

import "context"

type RPCTransport interface {
	RPCClient
	Notify(context.Context, string, any) error
}

func InitializeTransport(ctx context.Context, transport RPCTransport, version string) error {
	var response struct {
		UserAgent string `json:"userAgent"`
	}
	if err := transport.Call(ctx, "initialize", map[string]any{
		"clientInfo": map[string]any{
			"name": "codex-remote-android", "title": "Codex Remote Bridge", "version": version,
		},
		"capabilities": map[string]any{"experimentalApi": true},
	}, &response); err != nil {
		return err
	}
	return transport.Notify(ctx, "initialized", map[string]any{})
}
