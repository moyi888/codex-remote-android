package codex

import (
	"encoding/json"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/moyi888/codex-remote-android/bridge/internal/domain"
)

var attentionURLPattern = regexp.MustCompile(`https?://[^\s"'<>]+`)

func AttentionFromNotification(notification Notification, detectedAt time.Time) (string, domain.Attention, bool) {
	switch notification.Method {
	case "mcpServer/elicitation/request":
		var params struct {
			ThreadID string `json:"threadId"`
			Mode     string `json:"mode"`
			URL      string `json:"url"`
		}
		if json.Unmarshal(notification.Params, &params) != nil || params.ThreadID == "" || params.Mode != "url" {
			return "", domain.Attention{}, false
		}
		return params.ThreadID, domain.Attention{
			Category: "browser_authorization", Site: safeAttentionSite(params.URL),
			Confidence: 1, DetectedAt: detectedAt.UTC(),
		}, true
	case "item/completed":
		var params struct {
			ThreadID string `json:"threadId"`
			Item     struct {
				Type   string `json:"type"`
				Status string `json:"status"`
				Error  struct {
					Message string `json:"message"`
				} `json:"error"`
			} `json:"item"`
		}
		if json.Unmarshal(notification.Params, &params) != nil || params.ThreadID == "" ||
			params.Item.Type != "mcpToolCall" || params.Item.Status != "failed" ||
			!looksLikeBrowserAuthorization(params.Item.Error.Message) {
			return "", domain.Attention{}, false
		}
		return params.ThreadID, domain.Attention{
			Category: "third_party_login", Site: safeAttentionSite(params.Item.Error.Message),
			Confidence: 0.9, DetectedAt: detectedAt.UTC(),
		}, true
	default:
		return "", domain.Attention{}, false
	}
}

func looksLikeBrowserAuthorization(message string) bool {
	lower := strings.ToLower(message)
	for _, marker := range []string{"sign in", "log in", "login", "oauth", "authoriz", "captcha", "verification code"} {
		if strings.Contains(lower, marker) {
			return true
		}
	}
	return false
}

func safeAttentionSite(value string) string {
	match := attentionURLPattern.FindString(value)
	if match == "" && strings.HasPrefix(strings.ToLower(value), "http") {
		match = value
	}
	parsed, err := url.Parse(match)
	if err != nil {
		return ""
	}
	return strings.ToLower(parsed.Hostname())
}
