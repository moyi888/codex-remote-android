package codex

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync/atomic"
	"time"
)

const maxAppToolsFrameBytes = 8 << 20
const appToolsCallTimeout = 15 * time.Second

type AppToolsCaller interface {
	CallTool(context.Context, string, string, any, any) error
}

type AppToolsUnavailableError struct{ Err error }

func (e *AppToolsUnavailableError) Error() string {
	return fmt.Sprintf("Codex Desktop app tools unavailable: %v", e.Err)
}

func (e *AppToolsUnavailableError) Unwrap() error { return e.Err }

type appToolsConnection struct {
	io.ReadWriteCloser
	callerThreadID string
}

type appToolsDialer func(context.Context, string) (*appToolsConnection, error)

type AppToolsClient struct {
	dial   appToolsDialer
	nextID atomic.Uint64
}

func NewAppToolsClient(dial appToolsDialer) *AppToolsClient {
	return &AppToolsClient{dial: dial}
}

func (c *AppToolsClient) CallTool(ctx context.Context, callerThreadID, tool string, arguments, result any) error {
	ctx, cancel := context.WithTimeout(ctx, appToolsCallTimeout)
	defer cancel()
	connected, err := c.dial(ctx, callerThreadID)
	if err != nil {
		return &AppToolsUnavailableError{Err: err}
	}
	connection := connected.ReadWriteCloser
	defer connection.Close()

	id := c.nextID.Add(1)
	requestID := newAppToolID("codex-remote")
	request := map[string]any{
		"id": id, "jsonrpc": "2.0", "method": "tools/call",
		"params": map[string]any{
			"arguments": arguments,
			"callId":    requestID, "namespace": "codex_app",
			"threadId": connected.callerThreadID, "tool": tool,
			"turnId": requestID + "-turn",
		},
	}
	var response struct {
		Result struct {
			ContentItems []struct {
				Text string `json:"text"`
				Type string `json:"type"`
			} `json:"contentItems"`
			Success bool `json:"success"`
		} `json:"result"`
		Error *struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := callAppToolsFrame(ctx, connection, request, &response); err != nil {
		return err
	}
	if response.Error != nil {
		return &RPCError{Code: response.Error.Code, Message: response.Error.Message}
	}
	if !response.Result.Success {
		return fmt.Errorf("Codex Desktop rejected app tool %q", tool)
	}
	if result == nil {
		return nil
	}
	for _, item := range response.Result.ContentItems {
		if item.Type == "inputText" && item.Text != "" {
			if err := json.Unmarshal([]byte(item.Text), result); err != nil {
				return fmt.Errorf("decode Codex Desktop tool result: %w", err)
			}
			return nil
		}
	}
	return fmt.Errorf("Codex Desktop tool %q returned no JSON result", tool)
}

func newAppToolID(prefix string) string {
	random := make([]byte, 16)
	if _, err := rand.Read(random); err == nil {
		return prefix + "-" + hex.EncodeToString(random)
	}
	return fmt.Sprintf("%s-%d", prefix, time.Now().UnixNano())
}

func callAppToolsFrame(ctx context.Context, connection io.ReadWriteCloser, request, response any) error {
	payload, err := json.Marshal(request)
	if err != nil {
		return err
	}
	if len(payload) > maxAppToolsFrameBytes {
		return fmt.Errorf("Codex Desktop app tool request is too large")
	}
	type readResult struct {
		payload []byte
		err     error
	}
	completed := make(chan readResult, 1)
	go func() {
		if err := binary.Write(connection, binary.LittleEndian, uint32(len(payload))); err != nil {
			completed <- readResult{err: err}
			return
		}
		if _, err := connection.Write(payload); err != nil {
			completed <- readResult{err: err}
			return
		}
		var size uint32
		if err := binary.Read(connection, binary.LittleEndian, &size); err != nil {
			completed <- readResult{err: err}
			return
		}
		if size > maxAppToolsFrameBytes {
			completed <- readResult{err: fmt.Errorf("Codex Desktop app tool response is too large")}
			return
		}
		payload := make([]byte, size)
		_, err := io.ReadFull(connection, payload)
		completed <- readResult{payload: payload, err: err}
	}()
	select {
	case <-ctx.Done():
		_ = connection.Close()
		return ctx.Err()
	case result := <-completed:
		if result.err != nil {
			return result.err
		}
		return json.Unmarshal(result.payload, response)
	}
}

type DesktopCommandAdapter struct {
	Adapter
	tools AppToolsCaller
}

func NewDesktopCommandAdapter(base Adapter, tools AppToolsCaller) *DesktopCommandAdapter {
	return &DesktopCommandAdapter{Adapter: base, tools: tools}
}

func (a *DesktopCommandAdapter) SendTurn(ctx context.Context, request SendTurnRequest) error {
	return a.sendMessage(ctx, request, a.Adapter.SendTurn)
}

func (a *DesktopCommandAdapter) Steer(ctx context.Context, request SendTurnRequest) error {
	return a.sendMessage(ctx, request, a.Adapter.Steer)
}

func (a *DesktopCommandAdapter) sendMessage(
	ctx context.Context,
	request SendTurnRequest,
	fallback func(context.Context, SendTurnRequest) error,
) error {
	var response struct {
		ThreadID string `json:"threadId"`
	}
	err := a.tools.CallTool(ctx, request.ThreadID, "send_message_to_thread", map[string]any{
		"threadId": request.ThreadID,
		"prompt":   request.Prompt,
	}, &response)
	if err == nil {
		return nil
	}
	var unavailable *AppToolsUnavailableError
	if errors.As(err, &unavailable) {
		return fallback(ctx, request)
	}
	return err
}
