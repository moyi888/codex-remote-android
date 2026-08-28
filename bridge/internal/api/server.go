package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
	"github.com/moyi888/codex-remote-android/bridge/internal/auth"
	"github.com/moyi888/codex-remote-android/bridge/internal/codex"
	"github.com/moyi888/codex-remote-android/bridge/internal/commands"
	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
	"github.com/moyi888/codex-remote-android/bridge/internal/events"
)

const maxRequestBytes = 1 << 20

type Server struct {
	pairing  *auth.PairingService
	adapter  codex.Adapter
	commands *commands.Service
	events   *events.Broker
	mux      *http.ServeMux
}

type Option func(*Server)

func WithCommands(service *commands.Service) Option {
	return func(server *Server) { server.commands = service }
}

func WithEvents(broker *events.Broker) Option {
	return func(server *Server) { server.events = broker }
}

func NewServer(pairing *auth.PairingService, adapter codex.Adapter, options ...Option) *Server {
	server := &Server{pairing: pairing, adapter: adapter, mux: http.NewServeMux()}
	for _, option := range options {
		option(server)
	}
	server.mux.HandleFunc("GET /v1/health", server.health)
	server.mux.HandleFunc("POST /v1/pair/exchange", server.pairExchange)
	server.mux.Handle("GET /v1/snapshot", server.requireDevice(http.HandlerFunc(server.snapshot)))
	server.mux.Handle("GET /v1/threads/{threadID}", server.requireDevice(http.HandlerFunc(server.threadRead)))
	server.mux.Handle("GET /v1/threads/{threadID}/turns", server.requireDevice(http.HandlerFunc(server.threadTurns)))
	server.mux.Handle("POST /v1/commands", server.requireDevice(http.HandlerFunc(server.command)))
	server.mux.Handle("GET /v1/events", server.requireDevice(http.HandlerFunc(server.eventStream)))
	return server
}

type threadReader interface {
	ReadThread(context.Context, string, bool) (json.RawMessage, error)
	ListThreadTurns(context.Context, string, string, int) (json.RawMessage, error)
}

func (s *Server) threadRead(writer http.ResponseWriter, request *http.Request) {
	reader, ok := s.adapter.(threadReader)
	if !ok {
		writeError(writer, http.StatusNotImplemented, "thread history is not supported")
		return
	}
	payload, err := reader.ReadThread(request.Context(), request.PathValue("threadID"), true)
	if err != nil {
		writeError(writer, http.StatusBadGateway, "failed to read thread")
		return
	}
	writeRawJSON(writer, http.StatusOK, payload)
}

func (s *Server) threadTurns(writer http.ResponseWriter, request *http.Request) {
	reader, ok := s.adapter.(threadReader)
	if !ok {
		writeError(writer, http.StatusNotImplemented, "thread history is not supported")
		return
	}
	limit := 50
	if raw := request.URL.Query().Get("limit"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 1 || parsed > 100 {
			writeError(writer, http.StatusBadRequest, "invalid turn limit")
			return
		}
		limit = parsed
	}
	payload, err := reader.ListThreadTurns(request.Context(), request.PathValue("threadID"), request.URL.Query().Get("cursor"), limit)
	if err != nil {
		writeError(writer, http.StatusBadGateway, "failed to list thread turns")
		return
	}
	writeRawJSON(writer, http.StatusOK, payload)
}

func (s *Server) eventStream(writer http.ResponseWriter, request *http.Request) {
	if s.events == nil {
		writeError(writer, http.StatusServiceUnavailable, "events are not configured")
		return
	}
	cursor, err := strconv.ParseUint(request.URL.Query().Get("cursor"), 10, 64)
	if err != nil || cursor > math.MaxInt64 {
		writeError(writer, http.StatusBadRequest, "invalid event cursor")
		return
	}
	connection, err := websocket.Accept(writer, request, nil)
	if err != nil {
		return
	}
	defer connection.Close(websocket.StatusNormalClosure, "event stream closed")

	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for {
		batch, snapshotRequired, err := s.events.After(cursor, 100)
		if err != nil {
			_ = connection.Close(websocket.StatusInternalError, "event store failed")
			return
		}
		if snapshotRequired {
			if err := wsjson.Write(request.Context(), connection, domain.EventEnvelope[json.RawMessage]{
				ProtocolVersion: domain.ProtocolVersion,
				EventCursor:     cursor,
				Type:            "snapshot.required",
				Payload:         json.RawMessage(`{}`),
			}); err != nil {
				return
			}
			return
		}
		for _, event := range batch {
			if err := wsjson.Write(request.Context(), connection, domain.EventEnvelope[json.RawMessage]{
				ProtocolVersion: domain.ProtocolVersion,
				EventCursor:     event.Cursor,
				Type:            event.Type,
				Payload:         event.Payload,
			}); err != nil {
				return
			}
			cursor = event.Cursor
		}
		select {
		case <-request.Context().Done():
			return
		case <-ticker.C:
		}
	}
}

type deviceContextKey struct{}

func (s *Server) Handler() http.Handler { return s.mux }

func (s *Server) health(writer http.ResponseWriter, _ *http.Request) {
	writeJSON(writer, http.StatusOK, map[string]any{"status": "ok", "protocolVersion": domain.ProtocolVersion})
}

func (s *Server) pairExchange(writer http.ResponseWriter, request *http.Request) {
	request.Body = http.MaxBytesReader(writer, request.Body, maxRequestBytes)
	var input struct {
		Token      string `json:"token"`
		DeviceID   string `json:"deviceId"`
		DeviceName string `json:"deviceName"`
	}
	if err := json.NewDecoder(request.Body).Decode(&input); err != nil {
		writeError(writer, http.StatusBadRequest, "invalid pairing request")
		return
	}
	credential, err := s.pairing.Exchange(input.Token, input.DeviceID, input.DeviceName)
	if err != nil {
		if errors.Is(err, auth.ErrInvalidPairingRequest) {
			writeError(writer, http.StatusBadRequest, "invalid pairing request")
		} else if errors.Is(err, auth.ErrPairingTokenRejected) {
			writeError(writer, http.StatusUnauthorized, "pairing token rejected")
		} else {
			writeError(writer, http.StatusInternalServerError, "pairing failed")
		}
		return
	}
	writeJSON(writer, http.StatusOK, map[string]any{
		"protocolVersion": domain.ProtocolVersion,
		"deviceId":        input.DeviceID,
		"credential":      credential,
	})
}

func (s *Server) snapshot(writer http.ResponseWriter, request *http.Request) {
	var eventCursor uint64
	if s.events != nil {
		var err error
		eventCursor, err = s.events.LatestCursor()
		if err != nil {
			writeError(writer, http.StatusBadGateway, "failed to read event cursor")
			return
		}
	}
	projects, err := s.adapter.ListProjects(request.Context())
	if err != nil {
		writeError(writer, http.StatusBadGateway, "failed to list projects")
		return
	}
	models, err := s.adapter.ListModels(request.Context())
	if err != nil {
		writeError(writer, http.StatusBadGateway, "failed to list models")
		return
	}
	threads, err := s.adapter.ListThreads(request.Context())
	if err != nil {
		writeError(writer, http.StatusBadGateway, "failed to list threads")
		return
	}
	writeJSON(writer, http.StatusOK, map[string]any{
		"protocolVersion": domain.ProtocolVersion,
		"eventCursor":     eventCursor,
		"capabilities":    s.adapter.Capabilities(),
		"projects":        projects,
		"models":          models,
		"threads":         threads,
	})
}

func (s *Server) command(writer http.ResponseWriter, request *http.Request) {
	if s.commands == nil {
		writeError(writer, http.StatusServiceUnavailable, "commands are not configured")
		return
	}
	request.Body = http.MaxBytesReader(writer, request.Body, maxRequestBytes)
	var command domain.CommandEnvelope
	if err := json.NewDecoder(request.Body).Decode(&command); err != nil {
		writeError(writer, http.StatusBadRequest, "invalid command")
		return
	}
	deviceID, _ := request.Context().Value(deviceContextKey{}).(string)
	command.DeviceID = deviceID
	result, err := s.commands.Handle(request.Context(), command)
	if err != nil {
		writeError(writer, http.StatusBadRequest, "command rejected")
		return
	}
	writeJSON(writer, http.StatusOK, map[string]any{"status": "completed", "result": result})
}

func (s *Server) requireDevice(next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		deviceID, credential, ok := parseDeviceAuthorization(request.Header.Get("Authorization"))
		if !ok {
			writeError(writer, http.StatusUnauthorized, "device authentication required")
			return
		}
		authenticated, err := s.pairing.Authenticate(deviceID, credential)
		if err != nil || !authenticated {
			writeError(writer, http.StatusUnauthorized, "device authentication rejected")
			return
		}
		ctx := context.WithValue(request.Context(), deviceContextKey{}, deviceID)
		next.ServeHTTP(writer, request.WithContext(ctx))
	})
}

func parseDeviceAuthorization(value string) (string, string, bool) {
	const prefix = "Device "
	if !strings.HasPrefix(value, prefix) {
		return "", "", false
	}
	deviceID, credential, found := strings.Cut(strings.TrimPrefix(value, prefix), ":")
	return deviceID, credential, found && deviceID != "" && credential != ""
}

func writeError(writer http.ResponseWriter, status int, message string) {
	writeJSON(writer, status, map[string]string{"error": message})
}

func writeJSON(writer http.ResponseWriter, status int, value any) {
	writer.Header().Set("Content-Type", "application/json")
	writer.WriteHeader(status)
	if err := json.NewEncoder(writer).Encode(value); err != nil {
		_ = fmt.Errorf("encode response: %w", err)
	}
}

func writeRawJSON(writer http.ResponseWriter, status int, payload json.RawMessage) {
	writer.Header().Set("Content-Type", "application/json")
	writer.WriteHeader(status)
	_, _ = writer.Write(payload)
}
