package review

import (
	"fmt"
	"strings"

	"github.com/randomcodespace/codeiq/internal/graph"
)

// KuzuGraphContext implements GraphContext by querying an open Kuzu store.
// Wire from CLI: open store, NewKuzuGraphContext(store), pass to NewService.
//
// EvidenceForFile returns a compact textual summary that the LLM finds
// useful: nodes-in-file with kind + layer, plus 1-hop blast radius node
// IDs. Strictly read-only.
type KuzuGraphContext struct {
	Store *graph.Store
}

// NewKuzuGraphContext returns a context backed by store. nil Store yields
// empty evidence (the LLM degrades gracefully to diff-only review).
func NewKuzuGraphContext(store *graph.Store) *KuzuGraphContext {
	return &KuzuGraphContext{Store: store}
}

// EvidenceForFile satisfies GraphContext. Empty string when the store is
// missing or the file has no graph nodes.
func (k *KuzuGraphContext) EvidenceForFile(path string) string {
	if k == nil || k.Store == nil || path == "" {
		return ""
	}
	rows, err := k.Store.Cypher(`
		MATCH (n:CodeNode) WHERE n.file_path = $f
		RETURN n.id AS id, n.kind AS kind, n.label AS label, n.layer AS layer
		ORDER BY n.id LIMIT 25`, map[string]any{"f": path})
	if err != nil || len(rows) == 0 {
		return ""
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%d node(s) defined in this file:\n", len(rows))
	for _, r := range rows {
		id, _ := r["id"].(string)
		kind, _ := r["kind"].(string)
		label, _ := r["label"].(string)
		layer, _ := r["layer"].(string)
		fmt.Fprintf(&b, "- [%s/%s] %s (%s)\n", kind, layer, label, id)
	}
	// 1-hop blast radius: who depends on these nodes?
	ids := make([]any, 0, len(rows))
	for _, r := range rows {
		if id, ok := r["id"].(string); ok {
			ids = append(ids, id)
		}
	}
	if len(ids) == 0 {
		return b.String()
	}
	deps, err := k.Store.Cypher(`
		MATCH (caller:CodeNode)-[r]->(target:CodeNode)
		WHERE target.id IN $ids
		RETURN DISTINCT caller.id AS id, caller.kind AS kind, caller.label AS label
		ORDER BY caller.id LIMIT 15`, map[string]any{"ids": ids})
	if err == nil && len(deps) > 0 {
		fmt.Fprintf(&b, "\nBlast radius (1 hop, upstream callers): %d node(s)\n", len(deps))
		for _, d := range deps {
			id, _ := d["id"].(string)
			kind, _ := d["kind"].(string)
			label, _ := d["label"].(string)
			fmt.Fprintf(&b, "- [%s] %s (%s)\n", kind, label, id)
		}
	}
	return b.String()
}
