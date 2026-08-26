package codex

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestAttentionFromURLMCPServerElicitation(t *testing.T) {
	now := time.Date(2026, 8, 26, 12, 0, 0, 0, time.UTC)
	threadID, attention, ok := AttentionFromNotification(Notification{
		Method: "mcpServer/elicitation/request",
		Params: json.RawMessage(`{
			"threadId":"thread-1",
			"serverName":"chrome-devtools",
			"mode":"url",
			"message":"Complete authorization",
			"url":"https://github.com/login/oauth/authorize?token=do-not-leak"
		}`),
	}, now)
	if !ok || threadID != "thread-1" {
		t.Fatalf("thread=%q attention=%+v ok=%v", threadID, attention, ok)
	}
	if attention.Category != "browser_authorization" || attention.Site != "github.com" || attention.Confidence < 0.9 {
		t.Fatalf("attention = %+v", attention)
	}
	encoded, err := json.Marshal(attention)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encoded), "do-not-leak") || strings.Contains(string(encoded), "/login/") {
		t.Fatalf("attention leaked URL details: %s", encoded)
	}
}

func TestAttentionFromFailedMCPBrowserLogin(t *testing.T) {
	threadID, attention, ok := AttentionFromNotification(Notification{
		Method: "item/completed",
		Params: json.RawMessage(`{
			"threadId":"thread-2",
			"item":{
				"type":"mcpToolCall",
				"server":"chrome_devtools_mcp",
				"status":"failed",
				"error":{"message":"Sign in at https://accounts.google.com/path?secret=do-not-leak"}
			}
		}`),
	}, time.Now())
	if !ok || threadID != "thread-2" {
		t.Fatalf("thread=%q attention=%+v ok=%v", threadID, attention, ok)
	}
	if attention.Category != "third_party_login" || attention.Site != "accounts.google.com" {
		t.Fatalf("attention = %+v", attention)
	}
}

func TestAttentionIgnoresOrdinaryCodexApproval(t *testing.T) {
	_, _, ok := AttentionFromNotification(Notification{
		Method: "item/commandExecution/requestApproval",
		Params: json.RawMessage(`{"threadId":"thread-3","reason":"write file"}`),
	}, time.Now())
	if ok {
		t.Fatal("普通 Codex 命令审批不应触发浏览器授权提醒")
	}
}
