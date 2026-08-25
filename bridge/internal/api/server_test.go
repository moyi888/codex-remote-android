package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/moyi888/codex-remote-android/bridge/internal/auth"
	"github.com/moyi888/codex-remote-android/bridge/internal/codex"
	"github.com/moyi888/codex-remote-android/bridge/internal/commands"
	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
	"github.com/moyi888/codex-remote-android/bridge/internal/events"
	"github.com/moyi888/codex-remote-android/bridge/internal/store"
)

func TestSnapshotRequiresAuthenticationAndPairingGrantsAccess(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	pairing := auth.NewPairingService(db, time.Now)
	token, err := pairing.Issue(5 * time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(NewServer(pairing, codex.NewFakeAdapter()).Handler())
	defer server.Close()

	response, err := http.Get(server.URL + "/v1/snapshot")
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthenticated status = %d", response.StatusCode)
	}

	body, _ := json.Marshal(map[string]string{"token": token, "deviceId": "phone-1", "deviceName": "Pixel"})
	response, err = http.Post(server.URL+"/v1/pair/exchange", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var paired struct {
		Credential string `json:"credential"`
	}
	if err := json.NewDecoder(response.Body).Decode(&paired); err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || paired.Credential == "" {
		t.Fatalf("pairing response status=%d body=%+v", response.StatusCode, paired)
	}

	request, _ := http.NewRequest(http.MethodGet, server.URL+"/v1/snapshot", nil)
	request.Header.Set("Authorization", "Device phone-1:"+paired.Credential)
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("authenticated status = %d", response.StatusCode)
	}
	var snapshot struct {
		ProtocolVersion int   `json:"protocolVersion"`
		Projects        []any `json:"projects"`
		Models          []any `json:"models"`
	}
	if err := json.NewDecoder(response.Body).Decode(&snapshot); err != nil {
		t.Fatal(err)
	}
	if snapshot.ProtocolVersion != 1 || len(snapshot.Projects) != 1 || len(snapshot.Models) != 1 {
		t.Fatalf("unexpected snapshot: %+v", snapshot)
	}
}

func TestEventWebSocketReplaysFromCursor(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	pairing := auth.NewPairingService(db, time.Now)
	token, _ := pairing.Issue(5 * time.Minute)
	broker := events.NewBroker(db, time.Now)
	server := httptest.NewServer(NewServer(pairing, codex.NewFakeAdapter(), WithEvents(broker)).Handler())
	defer server.Close()
	credential := exchangeCredential(t, server.URL, token)
	if _, err := broker.Publish("thread.updated", json.RawMessage(`{"id":"thread-1"}`)); err != nil {
		t.Fatal(err)
	}

	header := http.Header{}
	header.Set("Authorization", "Device phone-1:"+credential)
	wsURL := "ws" + strings.TrimPrefix(server.URL, "http") + "/v1/events?cursor=0"
	connection, _, err := websocket.Dial(t.Context(), wsURL, &websocket.DialOptions{HTTPHeader: header})
	if err != nil {
		t.Fatal(err)
	}
	defer connection.CloseNow()
	_, payload, err := connection.Read(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	var event domain.EventEnvelope[json.RawMessage]
	if err := json.Unmarshal(payload, &event); err != nil {
		t.Fatal(err)
	}
	if event.ProtocolVersion != domain.ProtocolVersion || event.EventCursor != 1 || event.Type != "thread.updated" {
		t.Fatalf("unexpected event: %+v", event)
	}
	var eventPayload struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(event.Payload, &eventPayload); err != nil {
		t.Fatal(err)
	}
	if eventPayload.ID != "thread-1" {
		t.Fatalf("unexpected event payload: %+v", eventPayload)
	}
	var wireFields map[string]json.RawMessage
	if err := json.Unmarshal(payload, &wireFields); err != nil {
		t.Fatal(err)
	}
	if _, exists := wireFields["cursor"]; exists {
		t.Fatalf("internal cursor leaked into wire event: %s", payload)
	}
	if _, exists := wireFields["createdAt"]; exists {
		t.Fatalf("internal createdAt leaked into wire event: %s", payload)
	}
}

func TestCommandEndpointStartsTaskOnce(t *testing.T) {
	db, err := store.Open(filepath.Join(t.TempDir(), "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	pairing := auth.NewPairingService(db, time.Now)
	token, _ := pairing.Issue(5 * time.Minute)
	adapter := codex.NewFakeAdapter()
	commandService := commands.NewService(db, codex.NewCommandExecutor(adapter))
	server := httptest.NewServer(NewServer(pairing, adapter, WithCommands(commandService)).Handler())
	defer server.Close()

	credential := exchangeCredential(t, server.URL, token)
	command := domain.CommandEnvelope{
		ProtocolVersion: domain.ProtocolVersion,
		RequestID:       "request-1", DeviceID: "phone-1", IdempotencyKey: "start-1", Type: "task.start",
		Payload: json.RawMessage(`{"projectId":"project-1","prompt":"检查状态","model":"gpt-test","reasoning":"high"}`),
		SentAt:  time.Now().UTC(),
	}
	body, _ := json.Marshal(command)
	for range 2 {
		request, _ := http.NewRequest(http.MethodPost, server.URL+"/v1/commands", bytes.NewReader(body))
		request.Header.Set("Authorization", "Device phone-1:"+credential)
		request.Header.Set("Content-Type", "application/json")
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != http.StatusOK {
			t.Fatalf("command status = %d", response.StatusCode)
		}
	}
	threads, _ := adapter.ListThreads(t.Context())
	if len(threads) != 1 {
		t.Fatalf("threads = %d, want 1", len(threads))
	}
}

func exchangeCredential(t *testing.T, serverURL, token string) string {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"token": token, "deviceId": "phone-1", "deviceName": "Pixel"})
	response, err := http.Post(serverURL+"/v1/pair/exchange", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var paired struct {
		Credential string `json:"credential"`
	}
	if err := json.NewDecoder(response.Body).Decode(&paired); err != nil {
		t.Fatal(err)
	}
	return paired.Credential
}
