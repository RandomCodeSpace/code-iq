// Package query implements the intelligence-layer query planner.
//
// The planner maps (QueryType, language) → QueryPlan so callers (the MCP
// find_node tool, the evidence-pack assembler) know which retrieval path
// to take and which capability gaps to surface as degradation notes.
//
// Mirrors src/main/java/io/github/randomcodespace/iq/intelligence/query/.
package query

// QueryRoute is the retrieval path picked by the Planner for a query intent
// + language. Mirrors Java intelligence/query/QueryRoute.java 1:1 — same
// names so the JSON envelope is structurally identical to the Java side.
type QueryRoute string

const (
	// QueryRouteGraphFirst — primary path: query the structural graph (Kuzu).
	// Used when every relevant CapabilityDimension is EXACT — AST-level
	// analysis is available.
	QueryRouteGraphFirst QueryRoute = "GRAPH_FIRST"

	// QueryRouteLexicalFirst — fallback path: lexical / fulltext search only.
	// Used when every relevant CapabilityDimension is LEXICAL_ONLY — no
	// structural analysis is available for this language.
	QueryRouteLexicalFirst QueryRoute = "LEXICAL_FIRST"

	// QueryRouteMerged — combined path: graph results augmented with lexical
	// search. Used when at least one dimension is PARTIAL, or when a mix of
	// EXACT and LEXICAL_ONLY dimensions makes either alone insufficient.
	QueryRouteMerged QueryRoute = "MERGED"

	// QueryRouteDegraded — the feature is unsupported for this language.
	// QueryPlan.DegradationNote explains what is missing.
	QueryRouteDegraded QueryRoute = "DEGRADED"
)

// String returns the underlying identifier, which doubles as the JSON wire
// value. Required so the type satisfies fmt.Stringer for log output.
func (q QueryRoute) String() string { return string(q) }

// QueryType captures the caller's intent. Mirrors the Java QueryType enum,
// same identifiers so degradation-note strings match byte-for-byte.
type QueryType string

const (
	// QueryFindSymbol locates symbol definitions (classes, functions,
	// methods, variables) by name.
	QueryFindSymbol QueryType = "FIND_SYMBOL"

	// QueryFindReferences finds all usages / references of a symbol across
	// the indexed codebase.
	QueryFindReferences QueryType = "FIND_REFERENCES"

	// QueryFindCallers finds callers of a function or method.
	QueryFindCallers QueryType = "FIND_CALLERS"

	// QueryFindDependencies finds modules or packages a given module
	// depends on (via import / require / use resolution).
	QueryFindDependencies QueryType = "FIND_DEPENDENCIES"

	// QuerySearchText runs a full-text / lexical search across source files.
	// Always routes via QueryRouteLexicalFirst regardless of language.
	QuerySearchText QueryType = "SEARCH_TEXT"

	// QueryFindConfig locates configuration files and structured config
	// values (.env, application.yml, etc.).
	QueryFindConfig QueryType = "FIND_CONFIG"
)
