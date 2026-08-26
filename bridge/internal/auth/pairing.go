package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/store"
)

var (
	ErrInvalidPairingRequest = errors.New("invalid pairing request")
	ErrPairingTokenRejected  = errors.New("pairing token rejected")
)

type PairingService struct {
	store *store.Store
	now   func() time.Time
}

type PairingInvitation struct {
	URL       string
	ExpiresAt time.Time
}

func (s *PairingService) IssueInvitation(baseURL string, ttl time.Duration) (PairingInvitation, error) {
	token, err := s.Issue(ttl)
	if err != nil {
		return PairingInvitation{}, err
	}
	invitation := url.URL{Scheme: "codex-remote", Host: "pair"}
	query := invitation.Query()
	query.Set("baseUrl", baseURL)
	query.Set("token", token)
	invitation.RawQuery = query.Encode()
	return PairingInvitation{URL: invitation.String(), ExpiresAt: s.now().Add(ttl)}, nil
}

func NewPairingService(store *store.Store, now func() time.Time) *PairingService {
	return &PairingService{store: store, now: now}
}

func (s *PairingService) Issue(ttl time.Duration) (string, error) {
	if ttl <= 0 {
		return "", fmt.Errorf("pairing token ttl must be positive")
	}
	token, err := randomToken()
	if err != nil {
		return "", err
	}
	if err := s.store.CreatePairingToken(hash(token), s.now().Add(ttl)); err != nil {
		return "", err
	}
	return token, nil
}

func (s *PairingService) Exchange(token, deviceID, deviceName string) (string, error) {
	if token == "" || deviceID == "" || deviceName == "" {
		return "", ErrInvalidPairingRequest
	}
	credential, err := randomToken()
	if err != nil {
		return "", err
	}
	consumed, err := s.store.ExchangePairingToken(
		hash(token), s.now(), deviceID, deviceName, hash(credential),
	)
	if err != nil {
		return "", err
	}
	if !consumed {
		return "", ErrPairingTokenRejected
	}
	return credential, nil
}

func (s *PairingService) Authenticate(deviceID, credential string) (bool, error) {
	device, err := s.store.Device(deviceID)
	if err != nil {
		if err == store.ErrNotFound {
			return false, nil
		}
		return false, err
	}
	if device.Revoked {
		return false, nil
	}
	want, err := hex.DecodeString(device.CredentialHash)
	if err != nil {
		return false, fmt.Errorf("invalid stored credential hash: %w", err)
	}
	gotSum := sha256.Sum256([]byte(credential))
	authenticated := subtle.ConstantTimeCompare(want, gotSum[:]) == 1
	if !authenticated {
		return false, nil
	}
	if err := s.store.TouchDevice(deviceID, s.now()); err != nil {
		return false, err
	}
	return true, nil
}

func randomToken() (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

func hash(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}
