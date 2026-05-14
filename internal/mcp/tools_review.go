package mcp

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/randomcodespace/codeiq/internal/review"
)

// toolReviewChanges — Plan §3.3. LLM-driven review of base..head against
// the indexed graph. Strictly read-only against the graph: the tool does
// not mutate the cache or Kuzu store.
//
// The graph must already be enriched at the PR HEAD before this tool is
// called — for that flow, use `codeiq review` from the CLI which runs
// index + enrich + review in sequence.
func toolReviewChanges(d *Deps) Tool {
	return Tool{
		Name: "review_changes",
		Description: "Run an LLM-driven review of a git diff (base_ref..head_ref) " +
			"against the indexed code graph. Computes evidence per changed " +
			"file (graph blast radius, dependencies) and asks the configured " +
			"LLM (default: local Ollama, set OLLAMA_API_KEY for Ollama Cloud) " +
			"for review comments. The graph must already be enriched at the PR " +
			"HEAD — run 'codeiq review' for the end-to-end flow.",
		Schema: json.RawMessage(`{"type":"object","properties":{"base_ref":{"type":"string"},"head_ref":{"type":"string"},"model":{"type":"string"},"focus_files":{"type":"array","items":{"type":"string"}}}}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				BaseRef    string   `json:"base_ref"`
				HeadRef    string   `json:"head_ref"`
				Model      string   `json:"model"`
				FocusFiles []string `json:"focus_files"`
			}
			_ = json.Unmarshal(raw, &p)
			if d.RootPath == "" {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("review_changes requires a working tree root; set RootPath when wiring the MCP server"), RequestID(ctx)), nil
			}
			cfg := review.DefaultConfig()
			if p.Model != "" {
				cfg.Model = p.Model
			}
			// Reuse the MCP server's already-open Kuzu store for evidence.
			var gctx review.GraphContext
			if d.Store != nil {
				gctx = review.NewKuzuGraphContext(d.Store)
			}
			svc := review.NewService(review.NewClient(cfg), gctx)
			rep, err := svc.Review(ctx, d.RootPath, p.BaseRef, p.HeadRef, p.FocusFiles)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("review: %w", err), RequestID(ctx)), nil
			}
			rep.RequestID = RequestID(ctx)
			return rep, nil
		},
	}
}
