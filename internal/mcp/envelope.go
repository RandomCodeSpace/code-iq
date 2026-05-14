package mcp

import (
	"context"

	"github.com/google/uuid"
)

// Error code constants. Mirror the four codes the Java McpTools.errorEnvelope
// emits today. New codes must be added on both sides — the legacy `error`
// field is kept verbatim for backwards compatibility with MCP clients that
// read `error` directly (see the McpTools envelope gotcha in CLAUDE.md).
const (
	CodeInternalError       = "INTERNAL_ERROR"
	CodeInvalidInput        = "INVALID_INPUT"
	CodeFileReadFailed      = "FILE_READ_FAILED"
	CodeSerializationFailed = "SERIALIZATION_FAILED"
)

// ErrorEnvelope is the structured failure shape returned by every MCP
// tool. The legacy `error` field is preserved as a mirror of `message`
// for backwards compatibility with tool clients reading `error` directly.
// Do not drop it without grepping downstream consumers first.
type ErrorEnvelope struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	RequestID string `json:"request_id,omitempty"`
	Error     string `json:"error,omitempty"`
}

// NewErrorEnvelope packages a code + error + request id into the standard
// shape. err.Error() is surfaced as both `message` and `error` (legacy
// mirror). A nil err is replaced with "(no message)" so the field is
// never empty in the wire payload — matches Java's McpTools.errorEnvelope
// behaviour exactly.
func NewErrorEnvelope(code string, err error, requestID string) ErrorEnvelope {
	msg := "(no message)"
	if err != nil && err.Error() != "" {
		msg = err.Error()
	}
	return ErrorEnvelope{
		Code:      code,
		Message:   msg,
		RequestID: requestID,
		Error:     msg,
	}
}

// requestIDKey is the unexported context key under which the per-call
// UUID is stored by the server before invoking a tool handler. Keep the
// type unexported so external packages cannot accidentally collide.
type requestIDKey struct{}

// WithRequestID returns ctx augmented with a request id. Used by tool
// dispatch wrappers; tests may also call this directly.
func WithRequestID(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, requestIDKey{}, id)
}

// RequestID returns the request id stored on ctx, or "" if unset.
// Tool handlers call this to populate the `request_id` field on the
// envelope when they return an error.
func RequestID(ctx context.Context) string {
	v, _ := ctx.Value(requestIDKey{}).(string)
	return v
}

// NewRequestID generates a fresh UUIDv4 request id. Server middleware
// calls this once per tool invocation before dispatch.
func NewRequestID() string { return uuid.NewString() }
