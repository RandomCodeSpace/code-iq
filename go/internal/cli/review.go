package cli

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/randomcodespace/codeiq/go/internal/graph"
	"github.com/randomcodespace/codeiq/go/internal/review"

	"github.com/spf13/cobra"
)

func init() {
	registerSubcommand(func() *cobra.Command {
		var (
			base    string
			head    string
			model   string
			outFile string
			format  string
			focus   []string
		)
		cmd := &cobra.Command{
			Use:   "review [path]",
			Short: "LLM-driven review of a PR diff against the indexed graph.",
			Long: `Run an LLM review of git diff base..head, using the codeiq graph
as evidence context. Defaults: base=HEAD~1, head=HEAD, model=gpt-oss:20b
via local Ollama (set OLLAMA_API_KEY for Ollama Cloud).

Output formats:
  --format=markdown   (default) human-readable review
  --format=json       structured Report for piping into other tools

Plan §3 — Phase 3 of the optimization plan.`,
			Example: `  codeiq review --base origin/main --head HEAD
  OLLAMA_API_KEY=... codeiq review --model gpt-oss:120b
  codeiq review --base v1.0 --head v1.1 --out review.md`,
			Args: cobra.MaximumNArgs(1),
			RunE: func(cmd *cobra.Command, args []string) error {
				path := "."
				if len(args) == 1 {
					path = args[0]
				}
				abs, err := filepath.Abs(path)
				if err != nil {
					return err
				}
				cfg := review.DefaultConfig()
				if model != "" {
					cfg.Model = model
				}
				client := review.NewClient(cfg)

				// Best-effort: open the enriched Kuzu store read-only so the
				// review prompt carries graph evidence per changed file. If
				// the store isn't present (no enrich yet) we fall back to
				// diff-only review with a stderr warning.
				var gctx review.GraphContext
				gdir := filepath.Join(abs, ".codeiq", "graph", "codeiq.kuzu")
				if store, err := graph.OpenReadOnly(gdir, 30*time.Second); err == nil {
					defer store.Close()
					gctx = review.NewKuzuGraphContext(store)
				} else {
					fmt.Fprintf(os.Stderr, "review: graph store not available (%v); falling back to diff-only review. Run 'codeiq enrich' first to include graph evidence.\n", err)
				}
				svc := review.NewService(client, gctx)

				ctx, cancel := context.WithTimeout(cmd.Context(), cfg.Timeout+30*time.Second)
				defer cancel()
				rep, err := svc.Review(ctx, abs, base, head, focus)
				if err != nil {
					return fmt.Errorf("review: %w", err)
				}

				var rendered string
				if format == "json" {
					b, _ := json.MarshalIndent(rep, "", "  ")
					rendered = string(b) + "\n"
				} else {
					rendered = renderMarkdown(rep)
				}
				if outFile == "" {
					fmt.Fprint(cmd.OutOrStdout(), rendered)
					return nil
				}
				return os.WriteFile(outFile, []byte(rendered), 0644)
			},
		}
		cmd.Flags().StringVar(&base, "base", "", "Base git ref (default: HEAD~1)")
		cmd.Flags().StringVar(&head, "head", "", "Head git ref (default: HEAD)")
		cmd.Flags().StringVar(&model, "model", "", "Override LLM model (default: from config)")
		cmd.Flags().StringVarP(&outFile, "out", "o", "", "Write output to file instead of stdout")
		cmd.Flags().StringVar(&format, "format", "markdown", "Output format: markdown | json")
		cmd.Flags().StringSliceVar(&focus, "focus", nil, "Limit review to these file paths")
		return cmd
	})
}

func renderMarkdown(rep *review.Report) string {
	var b strings.Builder
	fmt.Fprintf(&b, "# Code Review (model: %s)\n\n", rep.Model)
	fmt.Fprintf(&b, "## Summary\n\n%s\n\n", rep.Summary)
	if len(rep.Findings) == 0 {
		b.WriteString("## Findings\n\nNo findings.\n")
		return b.String()
	}
	b.WriteString("## Findings\n\n")
	for _, f := range rep.Findings {
		loc := f.File
		if f.Line > 0 {
			loc = fmt.Sprintf("%s:%d", f.File, f.Line)
		}
		fmt.Fprintf(&b, "- **[%s] %s** — %s\n", strings.ToUpper(f.Severity), loc, f.Comment)
	}
	return b.String()
}
