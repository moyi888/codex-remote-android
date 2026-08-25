package events

import (
	"encoding/json"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/store"
)

type Event struct {
	Cursor    uint64          `json:"cursor"`
	Type      string          `json:"type"`
	Payload   json.RawMessage `json:"payload"`
	CreatedAt time.Time       `json:"createdAt"`
}

type Broker struct {
	store *store.Store
	now   func() time.Time
}

func NewBroker(store *store.Store, now func() time.Time) *Broker {
	return &Broker{store: store, now: now}
}

func (b *Broker) Publish(eventType string, payload json.RawMessage) (Event, error) {
	createdAt := b.now().UTC()
	cursor, err := b.store.AppendEvent(eventType, payload, createdAt)
	if err != nil {
		return Event{}, err
	}
	return Event{Cursor: cursor, Type: eventType, Payload: payload, CreatedAt: createdAt}, nil
}

func (b *Broker) LatestCursor() (uint64, error) {
	return b.store.LatestEventCursor()
}

func (b *Broker) After(cursor uint64, limit int) ([]Event, bool, error) {
	records, err := b.store.EventsAfter(cursor, limit)
	if err != nil {
		return nil, false, err
	}
	if len(records) > 0 && records[0].Cursor > cursor+1 {
		return nil, true, nil
	}
	result := make([]Event, 0, len(records))
	for _, record := range records {
		result = append(result, Event{
			Cursor: record.Cursor, Type: record.Type, Payload: record.Payload, CreatedAt: record.CreatedAt,
		})
	}
	return result, false, nil
}
