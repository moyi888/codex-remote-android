//go:build !windows

package codex

import (
	"context"
	"fmt"
)

func NewDesktopAppToolsClient() *AppToolsClient {
	return NewAppToolsClient(func(context.Context, string) (*appToolsConnection, error) {
		return nil, fmt.Errorf("Codex Desktop app tools are only available on Windows")
	})
}
