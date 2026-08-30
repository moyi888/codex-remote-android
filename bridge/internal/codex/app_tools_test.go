package codex

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net"
	"testing"
	"time"
)

func TestAppToolsClientCallsDesktopToolOverNativeFrame(t *testing.T) {
	clientSide, serverSide := net.Pipe()
	defer serverSide.Close()

	go func() {
		defer clientSide.Close()
		var size uint32
		if err := binary.Read(clientSide, binary.LittleEndian, &size); err != nil {
			return
		}
		payload := make([]byte, size)
		if _, err := io.ReadFull(clientSide, payload); err != nil {
			return
		}
		var request struct {
			Method string `json:"method"`
			Params struct {
				Tool      string         `json:"tool"`
				Namespace string         `json:"namespace"`
				ThreadID  string         `json:"threadId"`
				Arguments map[string]any `json:"arguments"`
			} `json:"params"`
		}
		if json.Unmarshal(payload, &request) != nil ||
			request.Method != "tools/call" || request.Params.Tool != "send_message_to_thread" ||
			request.Params.Namespace != "codex_app" || request.Params.ThreadID != "thread-1" ||
			request.Params.Arguments["prompt"] != "hello" {
			return
		}
		response := []byte(`{"id":1,"jsonrpc":"2.0","result":{"contentItems":[{"text":"{\"threadId\":\"thread-1\"}","type":"inputText"}],"success":true}}`)
		_ = binary.Write(clientSide, binary.LittleEndian, uint32(len(response)))
		_, _ = clientSide.Write(response)
	}()

	client := NewAppToolsClient(func(context.Context, string) (*appToolsConnection, error) {
		return &appToolsConnection{ReadWriteCloser: serverSide, callerThreadID: "thread-1"}, nil
	})
	var result struct {
		ThreadID string `json:"threadId"`
	}
	err := client.CallTool(context.Background(), "thread-1", "send_message_to_thread", map[string]any{
		"threadId": "thread-1", "prompt": "hello",
	}, &result)
	if err != nil {
		t.Fatal(err)
	}
	if result.ThreadID != "thread-1" {
		t.Fatalf("result = %+v", result)
	}
}

func TestAppToolsClientDoesNotClassifyPostSendResponseLossAsUnavailable(t *testing.T) {
	clientSide, serverSide := net.Pipe()
	go func() {
		defer clientSide.Close()
		var size uint32
		if binary.Read(clientSide, binary.LittleEndian, &size) != nil {
			return
		}
		_, _ = io.CopyN(io.Discard, clientSide, int64(size))
	}()
	client := NewAppToolsClient(func(context.Context, string) (*appToolsConnection, error) {
		return &appToolsConnection{ReadWriteCloser: serverSide, callerThreadID: "thread-1"}, nil
	})
	err := client.CallTool(context.Background(), "thread-1", "send_message_to_thread", map[string]any{}, &map[string]any{})
	if err == nil {
		t.Fatal("response loss must fail")
	}
	var unavailable *AppToolsUnavailableError
	if errors.As(err, &unavailable) {
		t.Fatalf("post-send response loss must not be replayable: %v", err)
	}
}

type blockingConnection struct {
	closed chan struct{}
}

func (c *blockingConnection) Read([]byte) (int, error) {
	<-c.closed
	return 0, io.ErrClosedPipe
}

func (c *blockingConnection) Write([]byte) (int, error) {
	<-c.closed
	return 0, io.ErrClosedPipe
}

func (c *blockingConnection) Close() error {
	select {
	case <-c.closed:
	default:
		close(c.closed)
	}
	return nil
}

func TestAppToolsClientCancelsBlockedPipeIO(t *testing.T) {
	connection := &blockingConnection{closed: make(chan struct{})}
	client := NewAppToolsClient(func(context.Context, string) (*appToolsConnection, error) {
		return &appToolsConnection{ReadWriteCloser: connection, callerThreadID: "thread-1"}, nil
	})
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	started := time.Now()
	err := client.CallTool(ctx, "thread-1", "send_message_to_thread", map[string]any{}, &map[string]any{})
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("error = %v", err)
	}
	if time.Since(started) > time.Second {
		t.Fatal("blocked pipe did not honor context cancellation")
	}
}

type unavailableAppTools struct{}

func (unavailableAppTools) CallTool(context.Context, string, string, any, any) error {
	return &AppToolsUnavailableError{Err: errors.New("desktop closed")}
}

func TestDesktopCommandAdapterFallsBackWhenDesktopChannelIsUnavailable(t *testing.T) {
	base := &fallbackAdapter{}
	adapter := NewDesktopCommandAdapter(base, unavailableAppTools{})
	if err := adapter.SendTurn(context.Background(), SendTurnRequest{ThreadID: "thread-1", Prompt: "hello"}); err != nil {
		t.Fatal(err)
	}
	if base.sendCalls != 1 {
		t.Fatalf("base.sendCalls = %d", base.sendCalls)
	}
	if err := adapter.Steer(context.Background(), SendTurnRequest{ThreadID: "thread-1", Prompt: "steer"}); err != nil {
		t.Fatal(err)
	}
	if base.steerRequest.Prompt != "steer" {
		t.Fatalf("base.steerRequest = %+v", base.steerRequest)
	}
}

type appToolsSpy struct {
	tool      string
	callerID  string
	arguments map[string]any
}

func (s *appToolsSpy) CallTool(_ context.Context, callerID, tool string, arguments any, result any) error {
	s.tool, s.callerID = tool, callerID
	s.arguments, _ = arguments.(map[string]any)
	return json.Unmarshal([]byte(`{"threadId":"thread-1"}`), result)
}

type fallbackAdapter struct {
	commandSpyAdapter
	sendCalls int
}

func (a *fallbackAdapter) SendTurn(context.Context, SendTurnRequest) error {
	a.sendCalls++
	return nil
}

func TestDesktopCommandAdapterUsesDesktopWriterForSendAndSteer(t *testing.T) {
	base := &fallbackAdapter{}
	tools := &appToolsSpy{}
	adapter := NewDesktopCommandAdapter(base, tools)

	request := SendTurnRequest{ThreadID: "thread-1", Prompt: "hello"}
	if err := adapter.SendTurn(context.Background(), request); err != nil {
		t.Fatal(err)
	}
	if err := adapter.Steer(context.Background(), request); err != nil {
		t.Fatal(err)
	}
	if base.sendCalls != 0 || tools.tool != "send_message_to_thread" || tools.callerID != "thread-1" ||
		tools.arguments["threadId"] != "thread-1" || tools.arguments["prompt"] != "hello" {
		t.Fatalf("base.sendCalls=%d tools=%+v", base.sendCalls, tools)
	}
}
