package review

import (
	"os"
	"time"
)

// Config selects the LLM endpoint + model + timeouts for the review path.
// Defaults target local Ollama; setting OLLAMA_API_KEY flips to Ollama
// Cloud (mirrors the Java ReviewConfig contract).
type Config struct {
	// Endpoint is the OpenAI-compatible chat completions URL. Common
	// values:
	//   - https://ollama.com/api/v1            (Ollama Cloud)
	//   - http://localhost:11434/v1            (local Ollama)
	//   - https://api.openai.com/v1            (OpenAI proxy / compatible)
	Endpoint string

	// APIKey is the bearer token. Empty for local Ollama.
	APIKey string

	// Model is the model name, e.g. "gpt-oss:20b".
	Model string

	// Timeout caps a single LLM HTTP call.
	Timeout time.Duration

	// MaxFiles caps the per-review change set. 0 = unlimited.
	MaxFiles int

	// MaxContextTokens is a soft per-call budget the prompt builder
	// honors when assembling evidence (truncates oldest evidence first).
	MaxContextTokens int
}

// DefaultConfig returns the standard Ollama defaults. When OLLAMA_API_KEY
// is set the endpoint flips to Ollama Cloud.
func DefaultConfig() Config {
	cfg := Config{
		Endpoint:         "http://localhost:11434/v1",
		Model:            "gpt-oss:20b",
		Timeout:          120 * time.Second,
		MaxFiles:         30,
		MaxContextTokens: 16000,
	}
	if key := os.Getenv("OLLAMA_API_KEY"); key != "" {
		cfg.Endpoint = "https://ollama.com/api/v1"
		cfg.APIKey = key
	}
	return cfg
}
