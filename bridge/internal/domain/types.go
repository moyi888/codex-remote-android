package domain

import (
	"encoding/json"
	"time"
)

const ProtocolVersion = 1

type ThreadSource string

const (
	ThreadSourceDesktop   ThreadSource = "desktop"
	ThreadSourceAppServer ThreadSource = "app_server"
)

type ThreadState string

const (
	ThreadIdle         ThreadState = "idle"
	ThreadRunning      ThreadState = "running"
	ThreadCompleted    ThreadState = "completed"
	ThreadFailed       ThreadState = "failed"
	ThreadDisconnected ThreadState = "disconnected"
)

type Attention struct {
	Category   string    `json:"category"`
	Site       string    `json:"site,omitempty"`
	Confidence float64   `json:"confidence"`
	DetectedAt time.Time `json:"detectedAt"`
}

type ThreadSummary struct {
	ID          string       `json:"id"`
	Title       string       `json:"title"`
	ProjectID   string       `json:"projectId"`
	ProjectName string       `json:"projectName"`
	Source      ThreadSource `json:"source"`
	State       ThreadState  `json:"state"`
	UpdatedAt   time.Time    `json:"updatedAt"`
	Attention   *Attention   `json:"attention,omitempty"`
}

type ProjectOption struct {
	ID          string `json:"id"`
	DisplayName string `json:"displayName"`
}

type ReasoningOption struct {
	ID          string `json:"id"`
	DisplayName string `json:"displayName"`
}

type ModelOption struct {
	ID               string            `json:"id"`
	DisplayName      string            `json:"displayName"`
	ReasoningOptions []ReasoningOption `json:"reasoningOptions"`
}

type EventEnvelope[T any] struct {
	ProtocolVersion int    `json:"protocolVersion"`
	EventCursor     uint64 `json:"eventCursor"`
	Type            string `json:"type"`
	Payload         T      `json:"payload"`
}

type CommandEnvelope struct {
	ProtocolVersion int             `json:"protocolVersion"`
	RequestID       string          `json:"requestId"`
	DeviceID        string          `json:"deviceId"`
	IdempotencyKey  string          `json:"idempotencyKey"`
	Type            string          `json:"type"`
	Payload         json.RawMessage `json:"payload"`
	SentAt          time.Time       `json:"sentAt"`
}
