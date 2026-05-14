package mcp

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
)

func TestErrorEnvelopeShape(t *testing.T) {
	env := NewErrorEnvelope(CodeInvalidInput, errors.New("kind is required"), "req-abc123")
	body, err := json.Marshal(env)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var got map[string]any
	if err := json.Unmarshal(body, &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	for _, key := range []string{"code", "message", "request_id", "error"} {
		if _, ok := got[key]; !ok {
			t.Fatalf("envelope missing %q: %s", key, body)
		}
	}
	if got["code"] != CodeInvalidInput {
		t.Fatalf("code = %v, want %s", got["code"], CodeInvalidInput)
	}
	if got["message"] != "kind is required" {
		t.Fatalf("message = %v, want kind is required", got["message"])
	}
	if got["error"] != "kind is required" {
		t.Fatalf("error legacy mirror = %v, want kind is required", got["error"])
	}
}

func TestErrorEnvelopeCodes(t *testing.T) {
	for _, c := range []string{
		CodeInternalError, CodeInvalidInput, CodeFileReadFailed, CodeSerializationFailed,
	} {
		if c == "" {
			t.Fatalf("empty error code constant")
		}
	}
}

func TestErrorEnvelopeNilError(t *testing.T) {
	env := NewErrorEnvelope(CodeInternalError, nil, "rid-1")
	if env.Message != "(no message)" {
		t.Fatalf("message = %q, want (no message)", env.Message)
	}
	if env.Error != "(no message)" {
		t.Fatalf("legacy error = %q, want (no message)", env.Error)
	}
}

func TestRequestIDRoundTrip(t *testing.T) {
	ctx := WithRequestID(context.Background(), "req-xyz")
	if got := RequestID(ctx); got != "req-xyz" {
		t.Fatalf("RequestID = %q, want req-xyz", got)
	}
	if got := RequestID(context.Background()); got != "" {
		t.Fatalf("empty ctx RequestID = %q, want \"\"", got)
	}
}

func TestNewRequestIDUUIDShape(t *testing.T) {
	id := NewRequestID()
	if len(id) != 36 {
		t.Fatalf("NewRequestID returned %q (len=%d), want UUID-shape len 36", id, len(id))
	}
}
