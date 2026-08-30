package codex

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"testing"
	"time"
)

type bufferWriteCloser struct{ bytes.Buffer }

func (bufferWriteCloser) Close() error { return nil }

func TestRPCProcessNotificationOmitsJSONRPCHeader(t *testing.T) {
	writer := &bufferWriteCloser{}
	process := &RPCProcess{stdin: writer}
	if err := process.Notify(context.Background(), "initialized", map[string]any{}); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(writer.String(), `"jsonrpc"`) {
		t.Fatalf("wire message contains forbidden jsonrpc header: %s", writer.String())
	}
}

func TestRPCProcessCorrelatesResponse(t *testing.T) {
	process, err := StartRPCProcess(context.Background(), os.Args[0], []string{"-test.run=TestRPCProcessHelper", "--"}, []string{"CODEX_REMOTE_HELPER=1"})
	if err != nil {
		t.Fatal(err)
	}
	defer process.Close()

	var result struct {
		Echo string `json:"echo"`
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := process.Call(ctx, "thread/list", map[string]any{"limit": 5}, &result); err != nil {
		t.Fatal(err)
	}
	if result.Echo != "thread/list" {
		t.Fatalf("echo = %q", result.Echo)
	}
}

func TestRPCProcessHandlesLargeJSONLine(t *testing.T) {
	process, err := StartRPCProcess(context.Background(), os.Args[0], []string{"-test.run=TestRPCProcessLargeResponseHelper", "--"}, []string{"CODEX_REMOTE_LARGE_RESPONSE_HELPER=1"})
	if err != nil {
		t.Fatal(err)
	}
	defer process.Close()

	var result struct {
		Payload string `json:"payload"`
	}
	if err := process.Call(context.Background(), "thread/list", nil, &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Payload) != 128*1024 {
		t.Fatalf("payload length = %d, want %d", len(result.Payload), 128*1024)
	}
}

func TestRPCProcessSkipsNotificationBeforeResponse(t *testing.T) {
	process, err := StartRPCProcess(context.Background(), os.Args[0], []string{"-test.run=TestRPCProcessNotificationHelper", "--"}, []string{"CODEX_REMOTE_NOTIFICATION_HELPER=1"})
	if err != nil {
		t.Fatal(err)
	}
	defer process.Close()

	var result struct {
		Echo string `json:"echo"`
	}
	if err := process.Call(context.Background(), "thread/list", nil, &result); err != nil {
		t.Fatal(err)
	}
	if result.Echo != "thread/list" {
		t.Fatalf("echo = %q", result.Echo)
	}
}

func TestRPCProcessDeliversNotificationAfterResponse(t *testing.T) {
	process, err := StartRPCProcess(context.Background(), os.Args[0], []string{"-test.run=TestRPCProcessDelayedNotificationHelper", "--"}, []string{"CODEX_REMOTE_DELAYED_NOTIFICATION_HELPER=1"})
	if err != nil {
		t.Fatal(err)
	}
	defer process.Close()

	var result struct {
		Echo string `json:"echo"`
	}
	if err := process.Call(context.Background(), "thread/list", nil, &result); err != nil {
		t.Fatal(err)
	}
	select {
	case notification := <-process.Notifications():
		if notification.Method != "item/started" {
			t.Fatalf("notification method = %q", notification.Method)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("response 后到达的通知未被持续读取")
	}
}

func TestRPCProcessPreservesServerRequestIDWithoutDeadlockingCall(t *testing.T) {
	process, err := StartRPCProcess(context.Background(), os.Args[0], []string{"-test.run=TestRPCProcessServerRequestHelper", "--"}, []string{"CODEX_REMOTE_SERVER_REQUEST_HELPER=1"})
	if err != nil {
		t.Fatal(err)
	}
	defer process.Close()

	callDone := make(chan error, 1)
	go func() {
		var result struct {
			Echo string `json:"echo"`
		}
		callDone <- process.Call(context.Background(), "turn/start", nil, &result)
	}()
	request := <-process.Notifications()
	if request.ID == nil || *request.ID != 99 || request.Method != "mcpServer/elicitation/request" {
		t.Fatalf("server request = %+v", request)
	}
	select {
	case err := <-callDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("server request response did not unblock the active call")
	}
}

func TestRPCProcessCancelsElicitationWhenNotificationQueueIsFull(t *testing.T) {
	process, err := StartRPCProcess(context.Background(), os.Args[0], []string{"-test.run=TestRPCProcessFullNotificationQueueHelper", "--"}, []string{"CODEX_REMOTE_FULL_NOTIFICATION_QUEUE_HELPER=1"})
	if err != nil {
		t.Fatal(err)
	}
	defer process.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	var result struct {
		Echo string `json:"echo"`
	}
	if err := process.Call(ctx, "turn/start", nil, &result); err != nil {
		t.Fatal(err)
	}
	if result.Echo != "turn/start" {
		t.Fatalf("echo = %q", result.Echo)
	}
}

func TestRPCProcessReportsUnexpectedExit(t *testing.T) {
	process, err := StartRPCProcess(context.Background(), os.Args[0], []string{"-test.run=TestRPCProcessExitHelper", "--"}, []string{"CODEX_REMOTE_EXIT_HELPER=1"})
	if err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-process.Done():
		if err == nil {
			t.Fatal("异常退出必须报告错误")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("未收到子进程退出信号")
	}
	_ = process.Close()
}

func TestRPCProcessPreservesStructuredServerError(t *testing.T) {
	process, err := StartRPCProcess(context.Background(), os.Args[0], []string{"-test.run=TestRPCProcessErrorHelper", "--"}, []string{"CODEX_REMOTE_ERROR_HELPER=1"})
	if err != nil {
		t.Fatal(err)
	}
	defer process.Close()
	err = process.Call(context.Background(), "thread/resume", nil, &struct{}{})
	var rpcErr *RPCError
	if !errors.As(err, &rpcErr) {
		t.Fatalf("error = %T %v, want *RPCError", err, err)
	}
	if rpcErr.Code != -32600 || rpcErr.Message != "thread abc already has an active writer" {
		t.Fatalf("rpc error = %+v", rpcErr)
	}
}

func TestRPCProcessCloseIsIdempotent(t *testing.T) {
	process, err := StartRPCProcess(context.Background(), os.Args[0], []string{"-test.run=TestRPCProcessWaitHelper", "--"}, []string{"CODEX_REMOTE_WAIT_HELPER=1"})
	if err != nil {
		t.Fatal(err)
	}
	if err := process.Close(); err != nil {
		t.Fatal(err)
	}
	if err := process.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestRPCProcessHelper(t *testing.T) {
	if os.Getenv("CODEX_REMOTE_HELPER") != "1" {
		return
	}
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() {
		os.Exit(2)
	}
	var request struct {
		JSONRPC string `json:"jsonrpc"`
		ID      uint64 `json:"id"`
		Method  string `json:"method"`
	}
	if err := json.Unmarshal(scanner.Bytes(), &request); err != nil {
		os.Exit(3)
	}
	if request.JSONRPC != "" {
		os.Exit(4)
	}
	fmt.Printf(`{"id":%d,"result":{"echo":%q}}`+"\n", request.ID, request.Method)
	os.Exit(0)
}

func TestRPCProcessLargeResponseHelper(t *testing.T) {
	if os.Getenv("CODEX_REMOTE_LARGE_RESPONSE_HELPER") != "1" {
		return
	}
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() {
		os.Exit(2)
	}
	var request struct {
		ID uint64 `json:"id"`
	}
	if err := json.Unmarshal(scanner.Bytes(), &request); err != nil {
		os.Exit(3)
	}
	result := struct {
		Payload string `json:"payload"`
	}{Payload: strings.Repeat("x", 128*1024)}
	encoded, err := json.Marshal(struct {
		ID     uint64 `json:"id"`
		Result any    `json:"result"`
	}{ID: request.ID, Result: result})
	if err != nil {
		os.Exit(4)
	}
	fmt.Println(string(encoded))
	os.Exit(0)
}

func TestRPCProcessNotificationHelper(t *testing.T) {
	if os.Getenv("CODEX_REMOTE_NOTIFICATION_HELPER") != "1" {
		return
	}
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() {
		os.Exit(2)
	}
	var request struct {
		ID     uint64 `json:"id"`
		Method string `json:"method"`
	}
	if err := json.Unmarshal(scanner.Bytes(), &request); err != nil {
		os.Exit(3)
	}
	fmt.Println(`{"method":"thread/status/changed","params":{"threadId":"thread-1"}}`)
	fmt.Printf(`{"id":%d,"result":{"echo":%q}}`+"\n", request.ID, request.Method)
	os.Exit(0)
}

func TestRPCProcessDelayedNotificationHelper(t *testing.T) {
	if os.Getenv("CODEX_REMOTE_DELAYED_NOTIFICATION_HELPER") != "1" {
		return
	}
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() {
		os.Exit(2)
	}
	var request struct {
		ID     uint64 `json:"id"`
		Method string `json:"method"`
	}
	if err := json.Unmarshal(scanner.Bytes(), &request); err != nil {
		os.Exit(3)
	}
	fmt.Printf(`{"id":%d,"result":{"echo":%q}}`+"\n", request.ID, request.Method)
	time.Sleep(100 * time.Millisecond)
	fmt.Println(`{"method":"item/started","params":{"threadId":"thread-1"}}`)
	_, _ = io.Copy(io.Discard, os.Stdin)
}

func TestRPCProcessServerRequestHelper(t *testing.T) {
	if os.Getenv("CODEX_REMOTE_SERVER_REQUEST_HELPER") != "1" {
		return
	}
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() {
		os.Exit(2)
	}
	var call struct {
		ID uint64 `json:"id"`
	}
	if json.Unmarshal(scanner.Bytes(), &call) != nil {
		os.Exit(3)
	}
	fmt.Println(`{"id":99,"method":"mcpServer/elicitation/request","params":{"threadId":"thread-1","mode":"url","url":"https://github.com/login"}}`)
	if !scanner.Scan() {
		os.Exit(4)
	}
	var response struct {
		ID     uint64 `json:"id"`
		Result struct {
			Action  string `json:"action"`
			Content any    `json:"content"`
		} `json:"result"`
	}
	if json.Unmarshal(scanner.Bytes(), &response) != nil || response.ID != 99 || response.Result.Action != "cancel" || response.Result.Content != nil {
		os.Exit(5)
	}
	fmt.Printf(`{"id":%d,"result":{"echo":"turn/start"}}`+"\n", call.ID)
}

func TestRPCProcessFullNotificationQueueHelper(t *testing.T) {
	if os.Getenv("CODEX_REMOTE_FULL_NOTIFICATION_QUEUE_HELPER") != "1" {
		return
	}
	scanner := bufio.NewScanner(os.Stdin)
	if !scanner.Scan() {
		os.Exit(2)
	}
	var call struct {
		ID uint64 `json:"id"`
	}
	if json.Unmarshal(scanner.Bytes(), &call) != nil {
		os.Exit(3)
	}
	for index := 0; index < 64; index++ {
		fmt.Printf(`{"method":"item/agentMessage/delta","params":{"index":%d}}`+"\n", index)
	}
	fmt.Println(`{"id":100,"method":"mcpServer/elicitation/request","params":{"threadId":"thread-1","mode":"url","url":"https://github.com/login"}}`)
	if !scanner.Scan() {
		os.Exit(4)
	}
	var response struct {
		ID     uint64 `json:"id"`
		Result struct {
			Action string `json:"action"`
		} `json:"result"`
	}
	if json.Unmarshal(scanner.Bytes(), &response) != nil || response.ID != 100 || response.Result.Action != "cancel" {
		os.Exit(5)
	}
	fmt.Printf(`{"id":%d,"result":{"echo":"turn/start"}}`+"\n", call.ID)
}

func TestRPCProcessExitHelper(t *testing.T) {
	if os.Getenv("CODEX_REMOTE_EXIT_HELPER") == "1" {
		os.Exit(9)
	}
}

func TestRPCProcessWaitHelper(t *testing.T) {
	if os.Getenv("CODEX_REMOTE_WAIT_HELPER") != "1" {
		return
	}
	_, _ = io.Copy(io.Discard, os.Stdin)
}

func TestRPCProcessErrorHelper(t *testing.T) {
	if os.Getenv("CODEX_REMOTE_ERROR_HELPER") != "1" {
		return
	}
	var request struct {
		ID uint64 `json:"id"`
	}
	if err := json.NewDecoder(os.Stdin).Decode(&request); err != nil {
		os.Exit(2)
	}
	fmt.Printf(`{"id":%d,"error":{"code":-32600,"message":"thread abc already has an active writer"}}`+"\n", request.ID)
	select {}
}
