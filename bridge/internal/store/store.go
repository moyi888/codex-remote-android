package store

import (
	"database/sql"
	_ "embed"
	"encoding/json"
	"errors"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed migrations/001_initial.sql
var initialMigration string

var ErrNotFound = errors.New("not found")

type Store struct {
	db *sql.DB
}

type Device struct {
	ID             string
	Name           string
	CredentialHash string
	Revoked        bool
}

type EventRecord struct {
	Cursor    uint64
	Type      string
	Payload   json.RawMessage
	CreatedAt time.Time
}

type CommandRecord struct {
	Status string
	Result json.RawMessage
}

func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec("PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;" + initialMigration); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) CreateDevice(id, name, credentialHash string) error {
	_, err := s.db.Exec(
		`INSERT INTO devices (id, name, credential_hash, created_at) VALUES (?, ?, ?, ?)`,
		id, name, credentialHash, time.Now().UTC().Format(time.RFC3339Nano),
	)
	return err
}

func (s *Store) Device(id string) (Device, error) {
	var device Device
	var revokedAt sql.NullString
	err := s.db.QueryRow(
		`SELECT id, name, credential_hash, revoked_at FROM devices WHERE id = ?`, id,
	).Scan(&device.ID, &device.Name, &device.CredentialHash, &revokedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Device{}, ErrNotFound
	}
	device.Revoked = revokedAt.Valid
	return device, err
}

func (s *Store) RevokeDevice(id string) error {
	result, err := s.db.Exec(
		`UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`,
		time.Now().UTC().Format(time.RFC3339Nano), id,
	)
	if err != nil {
		return err
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *Store) CreatePairingToken(tokenHash string, expiresAt time.Time) error {
	_, err := s.db.Exec(
		`INSERT INTO pairing_tokens (token_hash, expires_at) VALUES (?, ?)`,
		tokenHash, expiresAt.UTC().Format(time.RFC3339Nano),
	)
	return err
}

func (s *Store) ConsumePairingToken(tokenHash string, now time.Time) (bool, error) {
	result, err := s.db.Exec(
		`UPDATE pairing_tokens SET consumed_at = ?
         WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > ?`,
		now.UTC().Format(time.RFC3339Nano), tokenHash, now.UTC().Format(time.RFC3339Nano),
	)
	if err != nil {
		return false, err
	}
	rows, err := result.RowsAffected()
	return rows == 1, err
}

func (s *Store) AppendEvent(eventType string, payload json.RawMessage, createdAt time.Time) (uint64, error) {
	result, err := s.db.Exec(
		`INSERT INTO events (event_type, payload_json, created_at) VALUES (?, ?, ?)`,
		eventType, []byte(payload), createdAt.UTC().Format(time.RFC3339Nano),
	)
	if err != nil {
		return 0, err
	}
	id, err := result.LastInsertId()
	return uint64(id), err
}

func (s *Store) EventsAfter(cursor uint64, limit int) ([]EventRecord, error) {
	if limit <= 0 || limit > 1000 {
		limit = 100
	}
	rows, err := s.db.Query(
		`SELECT cursor, event_type, payload_json, created_at
         FROM events WHERE cursor > ? ORDER BY cursor ASC LIMIT ?`, cursor, limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var records []EventRecord
	for rows.Next() {
		var record EventRecord
		var createdAt string
		if err := rows.Scan(&record.Cursor, &record.Type, &record.Payload, &createdAt); err != nil {
			return nil, err
		}
		parsed, err := time.Parse(time.RFC3339Nano, createdAt)
		if err != nil {
			return nil, err
		}
		record.CreatedAt = parsed
		records = append(records, record)
	}
	return records, rows.Err()
}

func (s *Store) BeginCommand(deviceID, idempotencyKey, requestID, commandType string, now time.Time) (bool, CommandRecord, error) {
	result, err := s.db.Exec(
		`INSERT INTO commands
         (device_id, idempotency_key, request_id, command_type, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, 'pending', ?, ?)
         ON CONFLICT(device_id, idempotency_key) DO NOTHING`,
		deviceID, idempotencyKey, requestID, commandType,
		now.UTC().Format(time.RFC3339Nano), now.UTC().Format(time.RFC3339Nano),
	)
	if err != nil {
		return false, CommandRecord{}, err
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return false, CommandRecord{}, err
	}
	if rows == 1 {
		return true, CommandRecord{Status: "pending"}, nil
	}
	record, err := s.Command(deviceID, idempotencyKey)
	return false, record, err
}

func (s *Store) Command(deviceID, idempotencyKey string) (CommandRecord, error) {
	var record CommandRecord
	var result []byte
	err := s.db.QueryRow(
		`SELECT status, COALESCE(result_json, '') FROM commands
         WHERE device_id = ? AND idempotency_key = ?`,
		deviceID, idempotencyKey,
	).Scan(&record.Status, &result)
	if errors.Is(err, sql.ErrNoRows) {
		return CommandRecord{}, ErrNotFound
	}
	record.Result = json.RawMessage(result)
	return record, err
}

func (s *Store) CompleteCommand(deviceID, idempotencyKey, status string, result json.RawMessage, now time.Time) error {
	_, err := s.db.Exec(
		`UPDATE commands SET status = ?, result_json = ?, updated_at = ?
         WHERE device_id = ? AND idempotency_key = ?`,
		status, []byte(result), now.UTC().Format(time.RFC3339Nano), deviceID, idempotencyKey,
	)
	return err
}
