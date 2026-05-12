package lexical

import (
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// FullTextStore is the small surface QueryService needs from the graph
// package. *graph.Store satisfies this interface once its SearchByLabel /
// SearchLexical helpers land (plan Task 7). Defining it here keeps this
// package compilable independently and lets tests stand up a fake without
// CGO/Kuzu.
type FullTextStore interface {
	SearchByLabel(query string, limit int) ([]*model.CodeNode, error)
	SearchLexical(query string, limit int) ([]*model.CodeNode, error)
}

// Query limits — mirror LexicalQueryService.java DEFAULT_LIMIT / MAX_LIMIT.
const (
	defaultLimit = 50
	maxLimit     = 200
)

// Result is a single lexical search hit with source attribution. Score is
// reserved for downstream integration with the underlying FTS index (Kuzu
// QUERY_FTS_INDEX returns a score column); the bridging logic does not
// populate it yet.
type Result struct {
	Node    *model.CodeNode
	Score   float32
	Snippet *CodeSnippet
	Source  string // "identifier" | "lex_comment" | "lex_config_keys"
}

// QueryService bridges the lexical layer to the FTS-backed search helpers.
// Mirrors LexicalQueryService.java.
type QueryService struct {
	store    FullTextStore
	snippets *SnippetStore
	root     string
}

// NewQueryService constructs a QueryService bound to a fulltext-capable
// store. The snippets store and root path may be nil/empty when snippet
// attachment is not needed (e.g. unit tests).
func NewQueryService(store FullTextStore, snippets *SnippetStore, root string) *QueryService {
	return &QueryService{store: store, snippets: snippets, root: root}
}

// clampLimit normalises caller-supplied limits to the [defaultLimit, maxLimit]
// guard band. Non-positive limits collapse to defaultLimit.
func clampLimit(n int) int {
	if n <= 0 {
		return defaultLimit
	}
	if n > maxLimit {
		return maxLimit
	}
	return n
}

// FindByIdentifier returns nodes matching the query against the label /
// fqn fulltext index. The Source attribution is "identifier".
func (q *QueryService) FindByIdentifier(name string, limit int) []Result {
	nodes, err := q.store.SearchByLabel(name, clampLimit(limit))
	if err != nil {
		return nil
	}
	out := make([]Result, 0, len(nodes))
	for _, n := range nodes {
		out = append(out, Result{Node: n, Source: "identifier"})
	}
	return out
}

// FindByDocComment returns nodes whose lex_comment matches the query.
// When the QueryService was constructed with a non-nil SnippetStore and a
// non-empty root, a bounded source snippet is attached to each result.
func (q *QueryService) FindByDocComment(query string, limit int) []Result {
	nodes, err := q.store.SearchLexical(query, clampLimit(limit))
	if err != nil {
		return nil
	}
	out := make([]Result, 0, len(nodes))
	for _, n := range nodes {
		var snip *CodeSnippet
		if q.snippets != nil && q.root != "" {
			if cs, ok := q.snippets.Extract(n, q.root); ok {
				snip = &cs
			}
		}
		out = append(out, Result{Node: n, Source: KeyLexComment, Snippet: snip})
	}
	return out
}

// FindByConfigKey returns config-typed nodes whose lex_config_keys match
// the query. The same lexical index is queried as FindByDocComment, then
// the result set is filtered to config kinds.
func (q *QueryService) FindByConfigKey(query string, limit int) []Result {
	nodes, err := q.store.SearchLexical(query, clampLimit(limit))
	if err != nil {
		return nil
	}
	out := make([]Result, 0)
	for _, n := range nodes {
		if isConfigKind(n.Kind) {
			out = append(out, Result{Node: n, Source: KeyLexConfigKeys})
		}
	}
	return out
}
