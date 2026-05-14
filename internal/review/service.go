package review

import (
	"context"
	"fmt"
	"strings"
)

// GraphContext is the small graph-side dep ReviewService needs. The
// concrete implementation lives in mcp/cli code; this interface keeps the
// review package decoupled from the graph.Store. nil is acceptable —
// review will just send the diff without graph evidence.
type GraphContext interface {
	// EvidenceForFile returns a short, deterministic textual summary of
	// the graph context for a path: nodes-in-file, callers/dependents,
	// frameworks detected. Free-form; whatever the LLM finds useful.
	EvidenceForFile(path string) string
}

// Service orchestrates diff → graph evidence → LLM call. Stateless;
// pass-by-value safe.
type Service struct {
	Client *Client
	Graph  GraphContext // may be nil
}

// NewService wires the Service. Graph may be nil for non-enriched
// invocations (the LLM gets just the diff).
func NewService(client *Client, graph GraphContext) *Service {
	return &Service{Client: client, Graph: graph}
}

// Review runs the full pipeline against the given working tree and refs.
// Pass an empty base to compare against HEAD~1; empty head defaults to HEAD.
// FocusFiles, if non-empty, limits the review to those file paths.
func (s *Service) Review(ctx context.Context, cwd, base, head string, focusFiles []string) (*Report, error) {
	if base == "" {
		base = "HEAD~1"
	}
	if head == "" {
		head = "HEAD"
	}
	files, err := GitDiff(cwd, base, head)
	if err != nil {
		return nil, err
	}
	files = filterFocus(files, focusFiles)
	if maxF := maxFilesFromClient(s.Client); maxF > 0 && len(files) > maxF {
		files = files[:maxF]
	}
	prompt := s.buildPrompt(base, head, files)
	return s.Client.Review(ctx, prompt)
}

func filterFocus(files []ChangedFile, focus []string) []ChangedFile {
	if len(focus) == 0 {
		return files
	}
	want := make(map[string]struct{}, len(focus))
	for _, f := range focus {
		want[f] = struct{}{}
	}
	var out []ChangedFile
	for _, f := range files {
		if _, ok := want[f.Path]; ok {
			out = append(out, f)
		}
	}
	return out
}

func maxFilesFromClient(c *Client) int {
	if c == nil {
		return 0
	}
	return c.Config.MaxFiles
}

// buildPrompt assembles the user message: a header with base..head, then
// per-file blocks containing the diff hunks plus graph evidence (when
// Graph is wired). The whole prompt is plain text — Markdown-ish for
// readability but not strictly formatted.
func (s *Service) buildPrompt(base, head string, files []ChangedFile) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Reviewing %s..%s (%d changed file%s).\n\n", base, head, len(files), plural(len(files)))
	for _, f := range files {
		fmt.Fprintf(&b, "## File: %s\n", f.Path)
		fmt.Fprintf(&b, "+%d / -%d lines\n", f.AddedLines, f.RemovedLines)
		if s.Graph != nil {
			if ev := s.Graph.EvidenceForFile(f.Path); ev != "" {
				fmt.Fprintf(&b, "\nGraph evidence:\n%s\n", ev)
			}
		}
		b.WriteString("\nDiff:\n```\n")
		for _, h := range f.Hunks {
			b.WriteString(h)
		}
		b.WriteString("```\n\n")
	}
	return b.String()
}

func plural(n int) string {
	if n == 1 {
		return ""
	}
	return "s"
}
