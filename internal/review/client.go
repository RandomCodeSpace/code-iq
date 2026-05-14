package review

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

// Finding is one structured review comment from the LLM.
type Finding struct {
	File     string `json:"file"`
	Line     int    `json:"line,omitempty"`
	Severity string `json:"severity"` // info | low | medium | high | critical
	Comment  string `json:"comment"`
}

// Report is the structured LLM output. Both CLI (markdown) and MCP (JSON)
// paths consume this shape.
type Report struct {
	Summary   string    `json:"summary"`
	Findings  []Finding `json:"findings"`
	Model     string    `json:"model"`
	RequestID string    `json:"request_id,omitempty"`
}

// SystemPrompt is the single system message we use for every review.
// Plan §3.1 — "use the structured graph evidence to find correctness,
// security, and architectural issues".
const SystemPrompt = `You are reviewing a pull request. Use the structured graph evidence to find correctness, security, and architectural issues. ` +
	`Return strictly JSON in this shape: ` +
	`{"summary": "<one-paragraph overview>", "findings": [{"file": "<path>", "line": <int>, "severity": "info|low|medium|high|critical", "comment": "<message>"}]}. ` +
	`No prose before or after the JSON. ` +
	`If the diff is trivial, return an empty findings array — do NOT invent issues.`

// Client wraps the OpenAI-compatible /chat/completions endpoint exposed
// by Ollama, Ollama Cloud, and proxies. The HTTPClient field is exported
// so tests can inject a stub.
type Client struct {
	Config     Config
	HTTPClient *http.Client
}

// NewClient returns a Client with cfg and a default *http.Client.
func NewClient(cfg Config) *Client {
	return &Client{
		Config:     cfg,
		HTTPClient: &http.Client{Timeout: cfg.Timeout},
	}
}

type chatRequest struct {
	Model    string        `json:"model"`
	Messages []chatMessage `json:"messages"`
	Stream   bool          `json:"stream"`
}

type chatMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type chatResponse struct {
	ID      string `json:"id"`
	Model   string `json:"model"`
	Choices []struct {
		Message chatMessage `json:"message"`
	} `json:"choices"`
}

// Review sends the assembled prompt to the LLM and parses the structured
// reply into a Report. The user prompt should already include the diff
// + evidence pack rendering.
func (c *Client) Review(ctx context.Context, userPrompt string) (*Report, error) {
	body, err := json.Marshal(chatRequest{
		Model:  c.Config.Model,
		Stream: false,
		Messages: []chatMessage{
			{Role: "system", Content: SystemPrompt},
			{Role: "user", Content: userPrompt},
		},
	})
	if err != nil {
		return nil, fmt.Errorf("marshal request: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.Config.Endpoint+"/chat/completions", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if c.Config.APIKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.Config.APIKey)
	}
	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("LLM call: %w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 400 {
		return nil, fmt.Errorf("LLM HTTP %d: %s", resp.StatusCode, snippet(string(raw)))
	}
	var cr chatResponse
	if err := json.Unmarshal(raw, &cr); err != nil {
		return nil, fmt.Errorf("decode chat response: %w (body: %s)", err, snippet(string(raw)))
	}
	if len(cr.Choices) == 0 {
		return nil, fmt.Errorf("LLM returned no choices: %s", snippet(string(raw)))
	}
	var rep Report
	content := cr.Choices[0].Message.Content
	if err := json.Unmarshal([]byte(content), &rep); err != nil {
		return nil, fmt.Errorf("LLM did not return strict JSON: %w (content: %s)", err, snippet(content))
	}
	if rep.Model == "" {
		rep.Model = cr.Model
	}
	return &rep, nil
}

func snippet(s string) string {
	const max = 500
	if len(s) > max {
		return s[:max] + "..."
	}
	return s
}
