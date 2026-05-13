// Tools wiring the four intelligence-facing MCP tools per spec §9.
//
// find_node           — fuzzy name lookup routed via the QueryPlanner.
// get_evidence_pack   — assembles an EvidencePack via the Assembler.
// get_artifact_metadata — returns the most recent provenance snapshot.
// get_capabilities    — returns the per-language capability matrix.
package mcp

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/intelligence/evidence"
	iqquery "github.com/randomcodespace/codeiq/go/internal/intelligence/query"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// intelligenceTools returns the slice of intelligence-facing Tool
// definitions for d.
func intelligenceTools(d *Deps) []Tool {
	return []Tool{
		toolFindNode(d),
		toolGetEvidencePack(d),
		toolGetArtifactMetadata(d),
		toolGetCapabilities(d),
	}
}

// RegisterIntelligence appends every intelligence-facing tool to srv.
// Symmetric with RegisterGraph / RegisterTopology / RegisterFlow.
func RegisterIntelligence(srv *Server, d *Deps) error {
	for _, t := range intelligenceTools(d) {
		if err := srv.Register(t); err != nil {
			return fmt.Errorf("mcp: register intelligence tool %q: %w", t.Name, err)
		}
	}
	return nil
}

// ---------- tool builders ----------

// toolFindNode performs fuzzy name lookup. Routing rules:
//
//   - Exact match (label == query, case-insensitive) takes priority.
//   - Otherwise, the QueryPlanner picks GRAPH_FIRST (label/fqn search)
//     vs LEXICAL_FIRST (doc-comment / config-key search) vs MERGED (both,
//     concatenated) vs DEGRADED (empty matches + note).
//   - Without a wired QueryPlanner the handler falls back to GRAPH_FIRST.
//
// Mirrors Java McpTools.findNode + TopologyService.findNode shape:
// returns `{ matches: [...], count: N }` with each match in the compact
// node-map form (id, kind, label, file_path, layer).
func toolFindNode(d *Deps) Tool {
	return Tool{
		Name: "find_node",
		Description: "Find a node by name with fuzzy matching — exact " +
			"match priority, then partial/contains match. Use as a " +
			"quick lookup when you have a name but not the full node " +
			"ID. Returns best-matching node with its properties and " +
			"connections.",
		Schema: json.RawMessage(`{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Query string `json:"query"`
			}
			_ = json.Unmarshal(raw, &p)
			if strings.TrimSpace(p.Query) == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("query is required"), RequestID(ctx)), nil
			}
			if d.Store == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("graph store not wired"), RequestID(ctx)), nil
			}
			limit := CapResults(50, d.MaxResults)

			route := iqquery.QueryRouteGraphFirst
			degradationNote := ""
			if d.QueryPlanner != nil {
				plan := d.QueryPlanner.Plan(iqquery.QueryFindSymbol, inferLanguageFromQuery(p.Query))
				route = plan.Route
				degradationNote = plan.DegradationNote
			}

			// `find_node` is a name lookup — it always runs the structural
			// search (label/fqn substring) because that is the only signal
			// strong enough to anchor downstream impact-tracing. The
			// planner's route is advisory and surfaces as
			// `degradation_note` so MCP clients know what to expect.
			//
			// LEXICAL_FIRST and MERGED augment the structural results with
			// a lexical pass (doc-comment / config-key match) since those
			// languages don't have full structural coverage and the user
			// may be searching for something that only appears in
			// comments.
			matches, err := d.Store.SearchByLabel(p.Query, limit)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			if route == iqquery.QueryRouteLexicalFirst || route == iqquery.QueryRouteMerged {
				more, err2 := d.Store.SearchLexical(p.Query, limit)
				if err2 == nil {
					matches = mergeUnique(matches, more)
				}
			}
			// Sort exact-label hits (case-insensitive) to the front;
			// partial matches keep relative order. Mirrors Java
			// TopologyService.findNode priority rule.
			sorted := sortExactFirst(matches, p.Query)
			out := map[string]any{
				"matches": nodesToCompact(sorted),
				"count":   len(sorted),
			}
			if degradationNote != "" {
				out["degradation_note"] = degradationNote
			}
			return out, nil
		},
	}
}

// toolGetEvidencePack assembles an EvidencePack. Returns the legacy
// `{ "error": "Evidence pack service unavailable. Run 'enrich' first." }`
// shape when Evidence is not wired — matches Java McpTools.getEvidencePack
// exactly so existing clients reading `error` keep working.
func toolGetEvidencePack(d *Deps) Tool {
	return Tool{
		Name: "get_evidence_pack",
		Description: "Assemble a comprehensive evidence pack for a " +
			"symbol (class, method, function) or file: matched graph " +
			"nodes, source code snippets, provenance metadata, analysis " +
			"confidence level, and any degradation notes. Use when " +
			"asked to explain or investigate a specific code element " +
			"in depth.",
		Schema: json.RawMessage(`{"type":"object","properties":{"symbol":{"type":"string"},"file_path":{"type":"string"},"max_snippet_lines":{"type":"integer"},"include_references":{"type":"boolean"}}}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			if d.Evidence == nil {
				return map[string]string{
					"error": "Evidence pack service unavailable. Run 'enrich' first.",
				}, nil
			}
			var req evidence.Request
			if err := json.Unmarshal(raw, &req); err != nil {
				return NewErrorEnvelope(CodeInvalidInput, err, RequestID(ctx)), nil
			}
			pack, err := d.Evidence.Assemble(ctx, req, d.ArtifactMeta)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return pack, nil
		},
	}
}

// toolGetArtifactMetadata returns the provenance metadata snapshot. The
// `{ "error": "..." }` envelope when nil mirrors Java McpTools.
func toolGetArtifactMetadata(d *Deps) Tool {
	return Tool{
		Name: "get_artifact_metadata",
		Description: "Return provenance metadata about the analyzed " +
			"codebase: repository identity, commit SHA, build " +
			"timestamp, analysis tool versions, capability matrix " +
			"snapshot, and integrity hash. Use when asked about " +
			"analysis freshness, data provenance, or 'when was this " +
			"last scanned?'.",
		Schema: json.RawMessage(`{"type":"object","properties":{}}`),
		Handler: func(ctx context.Context, _ json.RawMessage) (any, error) {
			if d.ArtifactMeta == nil {
				return map[string]string{
					"error": "Artifact metadata unavailable. Run 'enrich' first.",
				}, nil
			}
			return d.ArtifactMeta, nil
		},
	}
}

// toolGetCapabilities returns the per-language capability matrix. With
// no params: every language's matrix under `matrix.<lang>`. With
// `language=<name>`: that one row under `language` + `capabilities`.
//
// Mirrors Java McpTools.getCapabilities — identical key names so client
// parsing logic transfers verbatim.
func toolGetCapabilities(d *Deps) Tool {
	return Tool{
		Name: "get_capabilities",
		Description: "Show the analysis capability matrix: what " +
			"codeiq can detect per language (Java, Python, " +
			"TypeScript, Go, etc.) across dimensions like call graph, " +
			"type hierarchy, framework detection. Levels: EXACT, " +
			"PARTIAL, LEXICAL_ONLY, UNSUPPORTED. Use when asked 'what " +
			"languages do you support?' or 'how accurate is the " +
			"analysis?'.",
		Schema: json.RawMessage(`{"type":"object","properties":{"language":{"type":"string"}}}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Language string `json:"language"`
			}
			_ = json.Unmarshal(raw, &p)
			lang := strings.ToLower(strings.TrimSpace(p.Language))
			if lang != "" {
				caps := iqquery.CapabilityMatrixFor(lang)
				return map[string]any{
					"language":     lang,
					"capabilities": caps,
				}, nil
			}
			return map[string]any{"matrix": iqquery.AllCapabilities()}, nil
		},
	}
}

// ---------- helpers ----------

// inferLanguageFromQuery heuristically classifies a free-text query as
// either a java-flavoured FQN (>=2 dots and identifier-only) or
// "unknown". Mirrors the §9 task plan — keeps the routing decision
// fully deterministic without parsing the graph.
func inferLanguageFromQuery(q string) string {
	dots := strings.Count(q, ".")
	if dots >= 2 && isIdentifierish(q) {
		return "java"
	}
	return "unknown"
}

// isIdentifierish reports whether every rune in q is an ASCII letter,
// digit, underscore, dot, or dollar — the union of valid characters in a
// Java FQN. Used to filter out free-text queries that just happen to
// contain dots (e.g. "log4j2.xml is missing").
func isIdentifierish(q string) bool {
	for _, r := range q {
		switch {
		case r >= 'a' && r <= 'z':
		case r >= 'A' && r <= 'Z':
		case r >= '0' && r <= '9':
		case r == '_' || r == '.' || r == '$':
		default:
			return false
		}
	}
	return true
}

// mergeUnique appends nodes from `more` to `base`, dropping any node
// whose ID is already present. Preserves base order followed by
// first-seen new IDs from `more`.
func mergeUnique(base, more []*model.CodeNode) []*model.CodeNode {
	seen := make(map[string]struct{}, len(base)+len(more))
	for _, n := range base {
		if n != nil {
			seen[n.ID] = struct{}{}
		}
	}
	out := make([]*model.CodeNode, 0, len(base)+len(more))
	out = append(out, base...)
	for _, n := range more {
		if n == nil {
			continue
		}
		if _, dup := seen[n.ID]; dup {
			continue
		}
		seen[n.ID] = struct{}{}
		out = append(out, n)
	}
	return out
}

// sortExactFirst returns nodes ordered with exact label matches (case-
// insensitive) first, then partial matches in their input order.
// Mirrors Java TopologyService.findNode where the exact bucket is built
// first and the partial bucket appended afterward.
func sortExactFirst(nodes []*model.CodeNode, query string) []*model.CodeNode {
	lower := strings.ToLower(query)
	out := append([]*model.CodeNode(nil), nodes...)
	sort.SliceStable(out, func(i, j int) bool {
		ai := exactRank(out[i], lower)
		aj := exactRank(out[j], lower)
		return ai < aj
	})
	return out
}

// exactRank returns 0 for exact label match, 1 otherwise — used as the
// sort key by sortExactFirst.
func exactRank(n *model.CodeNode, lowerQuery string) int {
	if n == nil {
		return 2
	}
	if strings.EqualFold(n.Label, lowerQuery) {
		return 0
	}
	return 1
}

// nodesToCompact projects a slice of nodes into the compact-map shape
// Java TopologyService.nodeToCompact emits. Used by find_node so the
// JSON envelope matches the Java side.
func nodesToCompact(nodes []*model.CodeNode) []map[string]any {
	out := make([]map[string]any, 0, len(nodes))
	for _, n := range nodes {
		if n == nil {
			continue
		}
		out = append(out, map[string]any{
			"id":        n.ID,
			"kind":      n.Kind.String(),
			"label":     n.Label,
			"file_path": n.FilePath,
			"layer":     n.Layer.String(),
		})
	}
	return out
}
