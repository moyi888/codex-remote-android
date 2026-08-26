package codex

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
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
