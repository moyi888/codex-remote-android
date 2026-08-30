//go:build windows

package codex

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"testing"
)

func TestAppToolsPipeCallerConfirmsTargetBeforeSelection(t *testing.T) {
	tests := []struct {
		name       string
		catalog    string
		readResult string
		wantCaller string
		wantOK     bool
	}{
		{
			name:       "target and separate caller",
			catalog:    `{"threads":[{"id":"target"},{"id":"caller"}],"pinnedThreads":[]}`,
			readResult: `{"thread":{"id":"target"}}`,
			wantCaller: "caller",
			wantOK:     true,
		},
		{
			name:       "only target",
			catalog:    `{"threads":[{"id":"target"}],"pinnedThreads":[]}`,
			readResult: `{"thread":{"id":"target"}}`,
			wantCaller: "target",
			wantOK:     true,
		},
		{
			name:       "wrong window without target",
			catalog:    `{"threads":[{"id":"other"}],"pinnedThreads":[]}`,
			readResult: "",
			wantOK:     false,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			clientSide, serverSide := net.Pipe()
			defer serverSide.Close()
			go serveAppToolsProbe(clientSide, test.catalog, test.readResult)
			caller, ok := appToolsPipeCaller(context.Background(), serverSide, "target")
			if ok != test.wantOK || caller != test.wantCaller {
				t.Fatalf("caller=%q ok=%v", caller, ok)
			}
		})
	}
}

func serveAppToolsProbe(connection net.Conn, catalog, readResult string) {
	defer connection.Close()
	responses := [][]byte{
		[]byte(`{"id":1,"jsonrpc":"2.0","result":{"tools":[{"name":"send_message_to_thread","namespace":"codex_app"}]}}`),
		[]byte(`{"id":2,"jsonrpc":"2.0","result":{"contentItems":[{"type":"inputText","text":` + quoteJSONString(catalog) + `}],"success":true}}`),
	}
	if readResult == "" {
		responses = append(responses, []byte(`{"id":3,"jsonrpc":"2.0","error":{"code":-32000,"message":"not found"}}`))
	} else {
		responses = append(responses, []byte(`{"id":3,"jsonrpc":"2.0","result":{"contentItems":[{"type":"inputText","text":`+quoteJSONString(readResult)+`}],"success":true}}`))
	}
	for _, response := range responses {
		var size uint32
		if binary.Read(connection, binary.LittleEndian, &size) != nil {
			return
		}
		if _, err := io.CopyN(io.Discard, connection, int64(size)); err != nil {
			return
		}
		_ = binary.Write(connection, binary.LittleEndian, uint32(len(response)))
		_, _ = connection.Write(response)
	}
}

func quoteJSONString(value string) string {
	data, _ := json.Marshal(value)
	return string(data)
}

func TestTrustedAppToolsServerPath(t *testing.T) {
	programFiles := `C:\Program Files`
	if !isTrustedAppToolsServerPath(`C:\Program Files\WindowsApps\OpenAI.Codex_26.1_x64__publisher\app\ChatGPT.exe`, programFiles) {
		t.Fatal("packaged Codex Desktop host was rejected")
	}
	for _, path := range []string{
		`C:\Temp\codex.exe`,
		`C:\Users\tester\AppData\Local\OpenAI\Codex\bin\abc\codex.exe`,
		`C:\Users\tester\AppData\Local\OpenAI\Codex\bin\abc\fake.exe`,
		`C:\Users\tester\AppData\Local\OpenAI\Codex\bin-evil\codex.exe`,
	} {
		if isTrustedAppToolsServerPath(path, programFiles) {
			t.Fatalf("untrusted path accepted: %s", path)
		}
	}
}

func TestDesktopAppToolsCacheIsScopedToVerifiedTarget(t *testing.T) {
	dialer := &desktopAppToolsDialer{
		pipePath: `\\.\pipe\codex-browser-use-test`, callerThreadID: "caller", targetThreadID: "target-a",
	}
	if _, caller, ok := dialer.cachedConnection("target-a"); !ok || caller != "caller" {
		t.Fatalf("verified target cache miss: caller=%q ok=%v", caller, ok)
	}
	if _, _, ok := dialer.cachedConnection("target-b"); ok {
		t.Fatal("cache leaked across target threads")
	}
}
