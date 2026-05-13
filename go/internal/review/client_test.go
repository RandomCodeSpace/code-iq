package review

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestClient_Review_HappyPath(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Authorization"); got != "Bearer testkey" {
			t.Errorf("Authorization = %q, want Bearer testkey", got)
		}
		if got := r.Header.Get("Content-Type"); got != "application/json" {
			t.Errorf("Content-Type = %q", got)
		}
		var req chatRequest
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.Model != "gpt-oss:20b" {
			t.Errorf("model = %q", req.Model)
		}
		if len(req.Messages) != 2 || req.Messages[0].Role != "system" {
			t.Errorf("messages shape wrong: %+v", req.Messages)
		}
		// Reply with an embedded JSON report.
		_ = json.NewEncoder(w).Encode(chatResponse{
			Model: "gpt-oss:20b",
			Choices: []struct {
				Message chatMessage `json:"message"`
			}{
				{Message: chatMessage{Role: "assistant", Content: `{"summary":"all good","findings":[{"file":"a.go","line":3,"severity":"medium","comment":"check this"}]}`}},
			},
		})
	}))
	defer server.Close()

	c := NewClient(Config{
		Endpoint: server.URL,
		APIKey:   "testkey",
		Model:    "gpt-oss:20b",
		Timeout:  5 * time.Second,
	})
	rep, err := c.Review(context.Background(), "user prompt body")
	if err != nil {
		t.Fatalf("Review: %v", err)
	}
	if rep.Summary != "all good" {
		t.Errorf("summary = %q", rep.Summary)
	}
	if len(rep.Findings) != 1 || rep.Findings[0].File != "a.go" {
		t.Errorf("findings = %+v", rep.Findings)
	}
	if rep.Model != "gpt-oss:20b" {
		t.Errorf("model = %q", rep.Model)
	}
}

func TestClient_Review_NoBearerWhenKeyEmpty(t *testing.T) {
	called := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		if got := r.Header.Get("Authorization"); got != "" {
			t.Errorf("expected no Authorization header (local Ollama), got %q", got)
		}
		_ = json.NewEncoder(w).Encode(chatResponse{
			Choices: []struct {
				Message chatMessage `json:"message"`
			}{
				{Message: chatMessage{Content: `{"summary":"x","findings":[]}`}},
			},
		})
	}))
	defer server.Close()

	c := NewClient(Config{Endpoint: server.URL, Model: "x", Timeout: 5 * time.Second})
	_, err := c.Review(context.Background(), "u")
	if err != nil {
		t.Fatalf("Review: %v", err)
	}
	if !called {
		t.Error("server not called")
	}
}

func TestClient_Review_LLMReturnsNonJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(chatResponse{
			Choices: []struct {
				Message chatMessage `json:"message"`
			}{
				{Message: chatMessage{Content: "I'm sorry, I can't comply."}},
			},
		})
	}))
	defer server.Close()

	c := NewClient(Config{Endpoint: server.URL, Model: "x", Timeout: 5 * time.Second})
	_, err := c.Review(context.Background(), "u")
	if err == nil || !strings.Contains(err.Error(), "strict JSON") {
		t.Errorf("expected strict-JSON error, got %v", err)
	}
}

func TestClient_Review_HTTPError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "rate limit", http.StatusTooManyRequests)
	}))
	defer server.Close()

	c := NewClient(Config{Endpoint: server.URL, Model: "x", Timeout: 5 * time.Second})
	_, err := c.Review(context.Background(), "u")
	if err == nil || !strings.Contains(err.Error(), "LLM HTTP 429") {
		t.Errorf("expected 429 error, got %v", err)
	}
}
