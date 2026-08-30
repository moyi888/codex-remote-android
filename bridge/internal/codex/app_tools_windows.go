//go:build windows

package codex

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"golang.org/x/sys/windows"
)

const appToolsPipeDirectory = `\\.\pipe\`

type desktopAppToolsDialer struct {
	mu             sync.Mutex
	pipePath       string
	callerThreadID string
	targetThreadID string
}

func NewDesktopAppToolsClient() *AppToolsClient {
	dialer := &desktopAppToolsDialer{}
	return NewAppToolsClient(dialer.dial)
}

func (d *desktopAppToolsDialer) dial(ctx context.Context, targetThreadID string) (*appToolsConnection, error) {
	pipePath, callerThreadID, cached := d.cachedConnection(targetThreadID)
	if cached {
		if connection, err := openAppToolsPipe(ctx, pipePath); err == nil {
			return &appToolsConnection{ReadWriteCloser: connection, callerThreadID: callerThreadID}, nil
		}
		d.mu.Lock()
		if d.pipePath == pipePath {
			d.pipePath = ""
			d.callerThreadID = ""
			d.targetThreadID = ""
		}
		d.mu.Unlock()
	}
	if pipePath != "" && !cached {
		if connection, err := openAppToolsPipe(ctx, pipePath); err == nil {
			if nextCaller, ok := appToolsPipeCaller(ctx, connection, targetThreadID); ok {
				d.mu.Lock()
				d.callerThreadID = nextCaller
				d.targetThreadID = targetThreadID
				d.mu.Unlock()
				return &appToolsConnection{ReadWriteCloser: connection, callerThreadID: nextCaller}, nil
			}
			_ = connection.Close()
		}
	}

	if err := ctx.Err(); err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(appToolsPipeDirectory)
	if err != nil {
		return nil, fmt.Errorf("list Windows named pipes: %w", err)
	}
	for _, entry := range entries {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if !strings.HasPrefix(entry.Name(), "codex-browser-use-") {
			continue
		}
		path := appToolsPipeDirectory + entry.Name()
		connection, err := openAppToolsPipe(ctx, path)
		if err != nil {
			continue
		}
		if callerThreadID, ok := appToolsPipeCaller(ctx, connection, targetThreadID); ok {
			d.mu.Lock()
			d.pipePath = path
			d.callerThreadID = callerThreadID
			d.targetThreadID = targetThreadID
			d.mu.Unlock()
			return &appToolsConnection{ReadWriteCloser: connection, callerThreadID: callerThreadID}, nil
		}
		_ = connection.Close()
	}
	return nil, fmt.Errorf("Codex Desktop app-tools pipe was not found")
}

func (d *desktopAppToolsDialer) cachedConnection(targetThreadID string) (string, string, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.pipePath, d.callerThreadID,
		d.pipePath != "" && d.callerThreadID != "" && d.targetThreadID == targetThreadID
}

func openAppToolsPipe(ctx context.Context, path string) (*os.File, error) {
	type result struct {
		file *os.File
		err  error
	}
	opened := make(chan result, 1)
	go func() {
		file, err := os.OpenFile(path, os.O_RDWR, 0)
		if err == nil && !isTrustedAppToolsPipe(file) {
			_ = file.Close()
			file = nil
			err = fmt.Errorf("named pipe server is not Codex Desktop")
		}
		select {
		case opened <- result{file: file, err: err}:
		case <-ctx.Done():
			if file != nil {
				_ = file.Close()
			}
		}
	}()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case result := <-opened:
		return result.file, result.err
	}
}

func isTrustedAppToolsPipe(file *os.File) bool {
	var processID uint32
	if err := windows.GetNamedPipeServerProcessId(windows.Handle(file.Fd()), &processID); err != nil || processID == 0 {
		return false
	}
	process, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, processID)
	if err != nil {
		return false
	}
	defer windows.CloseHandle(process)
	buffer := make([]uint16, 32768)
	size := uint32(len(buffer))
	if err := windows.QueryFullProcessImageName(process, 0, &buffer[0], &size); err != nil || size == 0 {
		return false
	}
	return isTrustedAppToolsServerPath(windows.UTF16ToString(buffer[:size]), os.Getenv("ProgramFiles"))
}

func isTrustedAppToolsServerPath(path, programFiles string) bool {
	if path == "" || programFiles == "" || !strings.EqualFold(filepath.Base(path), "ChatGPT.exe") {
		return false
	}
	candidate := strings.ToLower(filepath.Clean(path))
	packageRoot := strings.ToLower(filepath.Clean(filepath.Join(programFiles, "WindowsApps", "OpenAI.Codex_")))
	appSuffix := strings.ToLower(filepath.Join("app", "ChatGPT.exe"))
	return strings.HasPrefix(candidate, packageRoot) && strings.HasSuffix(candidate, appSuffix)
}

func appToolsPipeCaller(ctx context.Context, connection io.ReadWriteCloser, targetThreadID string) (string, bool) {
	request := map[string]any{
		"id": 1, "jsonrpc": "2.0", "method": "tools/list",
		"params": map[string]any{"threadStartKind": "all"},
	}
	var response struct {
		Result struct {
			Tools []struct {
				Name      string `json:"name"`
				Namespace string `json:"namespace"`
			} `json:"tools"`
		} `json:"result"`
	}
	if err := callAppToolsFrame(ctx, connection, request, &response); err != nil {
		return "", false
	}
	for _, tool := range response.Result.Tools {
		if tool.Name == "send_message_to_thread" && tool.Namespace == "codex_app" {
			probeID := newAppToolID("codex-remote-probe")
			request := map[string]any{
				"id": 2, "jsonrpc": "2.0", "method": "tools/call",
				"params": map[string]any{
					"arguments": map[string]any{"limit": 50},
					"callId":    probeID, "namespace": "codex_app",
					"threadId": targetThreadID, "tool": "list_threads", "turnId": probeID + "-turn",
				},
			}
			var readResponse struct {
				Result struct {
					ContentItems []struct {
						Text string `json:"text"`
						Type string `json:"type"`
					} `json:"contentItems"`
					Success bool `json:"success"`
				} `json:"result"`
				Error any `json:"error"`
			}
			if callAppToolsFrame(ctx, connection, request, &readResponse) != nil ||
				readResponse.Error != nil || !readResponse.Result.Success {
				return "", false
			}
			callerThreadID := ""
			for _, item := range readResponse.Result.ContentItems {
				if item.Type != "inputText" || item.Text == "" {
					continue
				}
				var catalog struct {
					PinnedThreads []struct {
						ID string `json:"id"`
					} `json:"pinnedThreads"`
					Threads []struct {
						ID string `json:"id"`
					} `json:"threads"`
				}
				if json.Unmarshal([]byte(item.Text), &catalog) != nil {
					continue
				}
				for _, threads := range [][]struct {
					ID string `json:"id"`
				}{catalog.Threads, catalog.PinnedThreads} {
					for _, thread := range threads {
						if thread.ID == "" {
							continue
						}
						if callerThreadID == "" || callerThreadID == targetThreadID {
							callerThreadID = thread.ID
						}
						if callerThreadID != targetThreadID {
							break
						}
					}
				}
			}
			if callerThreadID == "" {
				return "", false
			}
			if appToolsPipeCanReadThread(ctx, connection, callerThreadID, targetThreadID) {
				return callerThreadID, true
			}
			return "", false
		}
	}
	return "", false
}

func appToolsPipeCanReadThread(ctx context.Context, connection io.ReadWriteCloser, callerThreadID, targetThreadID string) bool {
	probeID := newAppToolID("codex-remote-read-probe")
	request := map[string]any{
		"id": 3, "jsonrpc": "2.0", "method": "tools/call",
		"params": map[string]any{
			"arguments": map[string]any{"threadId": targetThreadID, "turnLimit": 1},
			"callId":    probeID, "namespace": "codex_app",
			"threadId": callerThreadID, "tool": "read_thread", "turnId": probeID + "-turn",
		},
	}
	var response struct {
		Result struct {
			Success bool `json:"success"`
		} `json:"result"`
		Error any `json:"error"`
	}
	return callAppToolsFrame(ctx, connection, request, &response) == nil && response.Error == nil && response.Result.Success
}
