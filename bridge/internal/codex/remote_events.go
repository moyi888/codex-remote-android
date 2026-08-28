package codex

import (
	"encoding/json"
)

// RemoteEventType exposes low-volume app-server lifecycle events to remote clients.
// Streaming deltas are intentionally excluded to avoid turning the event log into a
// high-frequency transcript store; clients poll thread history for complete content.
func RemoteEventType(method string) (string, bool) {
	switch method {
	case "thread/started", "thread/created", "thread/updated":
		return "thread.updated", true
	case "turn/started":
		return "turn.started", true
	case "turn/completed", "turn/failed", "turn/interrupted":
		return "turn.completed", true
	case "item/started":
		return "item.started", true
	case "item/completed":
		return "item.completed", true
	default:
		return "", false
	}
}

// RemoteEventPayload keeps the app-server fields intact and adds no secrets.
func RemoteEventPayload(notification Notification) json.RawMessage {
	if len(notification.Params) == 0 {
		return json.RawMessage(`{}`)
	}
	return append(json.RawMessage(nil), notification.Params...)
}
