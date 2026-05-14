package evidence

import (
	"context"
	"strings"

	"github.com/randomcodespace/codeiq/internal/intelligence/lexical"
	iqquery "github.com/randomcodespace/codeiq/internal/intelligence/query"
	"github.com/randomcodespace/codeiq/internal/model"
)

// LexFinder is the narrow interface the Assembler needs to retrieve lexical
// matches. *lexical.QueryService satisfies the by-identifier branch; the
// by-file-path branch is fulfilled by a graph-backed adapter wired at serve
// time. Defining it locally keeps the package CGo-free for unit tests and
// lets the MCP layer plug in a richer implementation without breaking this
// package's surface.
type LexFinder interface {
	// FindByIdentifier returns lexical matches whose label / fqn fuzzy-match
	// the given symbol name. Empty slice for no matches; error for IO faults.
	FindByIdentifier(ctx context.Context, symbol string) ([]lexical.Result, error)

	// FindByFilePath returns lexical matches whose CodeNode.FilePath equals
	// the given path. Returned in deterministic order.
	FindByFilePath(ctx context.Context, filePath string) ([]lexical.Result, error)
}

// GraphReader is the narrow interface the Assembler needs for cross-reference
// traversal (callers + dependents). *query.Service satisfies this in
// production via thin adapter functions; tests pass a hand-rolled fake.
//
// The interface is context-aware on the Go side even though the Java callees
// are not — keeps the door open for per-call cancellation once a Kuzu
// request budget lands.
type GraphReader interface {
	FindCallers(ctx context.Context, id string) ([]*model.CodeNode, error)
	FindDependents(ctx context.Context, id string) ([]*model.CodeNode, error)
}

// Assembler builds an EvidencePack from a query intent.
//
// Stateless and goroutine-safe — every field is set at construction time
// and only read thereafter. Mirrors Java EvidencePackAssembler.
type Assembler struct {
	lex             LexFinder
	snippets        *lexical.SnippetStore
	graph           GraphReader
	planner         *iqquery.Planner
	rootPath        string
	maxSnippetLines int
}

// NewAssembler constructs a stateless assembler. rootPath is the absolute
// repo root used by SnippetStore for path-traversal guards; maxSnippetLines
// is the upper bound applied when a request does not specify its own.
// Mirrors the Java EvidencePackAssembler constructor + the
// CodeIqConfig.getRootPath / getMaxSnippetLines wiring.
func NewAssembler(
	lex LexFinder,
	snippets *lexical.SnippetStore,
	graph GraphReader,
	planner *iqquery.Planner,
	rootPath string,
	maxSnippetLines int,
) *Assembler {
	if maxSnippetLines <= 0 {
		maxSnippetLines = lexical.MaxSnippetLines
	}
	return &Assembler{
		lex:             lex,
		snippets:        snippets,
		graph:           graph,
		planner:         planner,
		rootPath:        rootPath,
		maxSnippetLines: maxSnippetLines,
	}
}

// Assemble produces an EvidencePack (or an empty one with a note) for the
// request. Mirrors Java EvidencePackAssembler.assemble:
//
//   - same ordering rules (insertion order of lexical results, sorted
//     unique files);
//   - same degradation-note semantics;
//   - same provenance shape (filePath, lineStart, lineEnd, kind +
//     prov_* properties).
func (a *Assembler) Assemble(ctx context.Context, req Request, meta *ArtifactMetadata) (Pack, error) {
	symbol := strings.TrimSpace(req.Symbol)
	filePath := strings.TrimSpace(req.FilePath)

	if symbol == "" && filePath == "" {
		return EmptyPack(meta, "No symbol or file path provided."), nil
	}
	subject := symbol
	if subject == "" {
		subject = filePath
	}

	language := "unknown"
	if filePath != "" {
		language = inferLanguage(filePath)
	}
	plan := a.planner.Plan(iqquery.QueryFindSymbol, language)

	var lexResults []lexical.Result
	var err error
	if symbol != "" {
		lexResults, err = a.lex.FindByIdentifier(ctx, symbol)
	} else {
		lexResults, err = a.lex.FindByFilePath(ctx, filePath)
	}
	if err != nil {
		return Pack{}, err
	}
	if len(lexResults) == 0 {
		return EmptyPack(meta, buildEmptyNote(subject, plan)), nil
	}

	matched := make([]*model.CodeNode, 0, len(lexResults))
	for _, r := range lexResults {
		if r.Node != nil {
			matched = append(matched, r.Node)
		}
	}

	maxLines := resolveMaxLines(req.MaxSnippetLines, a.maxSnippetLines)
	snippets := make([]lexical.CodeSnippet, 0, len(matched))
	for _, n := range matched {
		if a.snippets == nil || a.rootPath == "" {
			continue
		}
		if cs, ok := a.snippets.Extract(n, a.rootPath); ok {
			snippets = append(snippets, boundSnippet(cs, maxLines))
		}
	}

	relatedFiles := uniqueSortedFiles(matched)

	references := []*model.CodeNode{}
	if req.IncludeReferences {
		references, err = a.fetchReferences(ctx, matched)
		if err != nil {
			return Pack{}, err
		}
	}

	provenance := make([]map[string]any, 0, len(matched))
	for _, n := range matched {
		provenance = append(provenance, provenanceFor(n))
	}

	degradationNotes := []string{}
	if plan.DegradationNote != "" {
		degradationNotes = append(degradationNotes, plan.DegradationNote)
	}

	return Pack{
		MatchedSymbols:   matched,
		RelatedFiles:     relatedFiles,
		References:       references,
		Snippets:         snippets,
		Provenance:       provenance,
		DegradationNotes: degradationNotes,
		ArtifactMetadata: meta,
		CapabilityLevel:  deriveCapability(plan.Route),
	}, nil
}

// fetchReferences traverses CALLS + DEPENDS_ON edges via the GraphReader,
// deduplicating by id while preserving discovery order. Matches Java
// EvidencePackAssembler.fetchReferences.
func (a *Assembler) fetchReferences(ctx context.Context, matched []*model.CodeNode) ([]*model.CodeNode, error) {
	matchedIDs := make(map[string]struct{}, len(matched))
	for _, n := range matched {
		if n.ID != "" {
			matchedIDs[n.ID] = struct{}{}
		}
	}
	seen := make(map[string]struct{}, len(matched))
	for id := range matchedIDs {
		seen[id] = struct{}{}
	}
	out := []*model.CodeNode{}
	for _, n := range matched {
		if n.ID == "" {
			continue
		}
		callers, err := a.graph.FindCallers(ctx, n.ID)
		if err != nil {
			return nil, err
		}
		for _, c := range callers {
			if c == nil || c.ID == "" {
				continue
			}
			if _, dup := seen[c.ID]; dup {
				continue
			}
			seen[c.ID] = struct{}{}
			out = append(out, c)
		}
		deps, err := a.graph.FindDependents(ctx, n.ID)
		if err != nil {
			return nil, err
		}
		for _, d := range deps {
			if d == nil || d.ID == "" {
				continue
			}
			if _, dup := seen[d.ID]; dup {
				continue
			}
			seen[d.ID] = struct{}{}
			out = append(out, d)
		}
	}
	return out, nil
}

// buildEmptyNote produces the degradation note used when a query returns no
// matches. Mirrors EvidencePackAssembler.buildEmptyNote — DEGRADED plans
// reuse the planner's note; everything else falls back to a generic message
// citing the subject.
func buildEmptyNote(subject string, plan iqquery.Plan) string {
	if plan.Route == iqquery.QueryRouteDegraded {
		if plan.DegradationNote != "" {
			return plan.DegradationNote
		}
		return "Symbol '" + subject + "' not found. Language is not fully supported."
	}
	return "Symbol '" + subject + "' was not found in the indexed graph."
}
