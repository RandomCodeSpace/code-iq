package query

import "strings"

// Planner maps (queryType, language) → Plan deterministically. Mirrors
// Java intelligence/query/QueryPlanner.java — same priority chain:
// DEGRADED > LEXICAL_FIRST > MERGED > GRAPH_FIRST.
//
// SEARCH_TEXT is always routed via LEXICAL_FIRST regardless of language —
// the graph does not index raw text content.
//
// Planner is stateless and goroutine-safe — all state is captured in the
// capabilityFor closure passed at construction time.
type Planner struct {
	// capabilityFor returns the capability matrix for a normalised
	// (lowercased / trimmed) language identifier. In production this is
	// CapabilityMatrixFor; tests inject fixed matrices.
	capabilityFor func(language string) CapabilityMatrix
}

// NewPlanner constructs a Planner that calls capabilityFor on every Plan
// invocation. Construct once at server startup and reuse — no internal state.
func NewPlanner(capabilityFor func(string) CapabilityMatrix) *Planner {
	return &Planner{capabilityFor: capabilityFor}
}

// queryDimensions maps each QueryType to its relevant CapabilityDimensions.
// Mirrors the Java side QUERY_DIMENSIONS map. SEARCH_TEXT carries an empty
// slice because it is special-cased in Plan.
var queryDimensions = map[QueryType][]CapabilityDimension{
	QueryFindSymbol:       {DimSymbolDefinitions},
	QueryFindReferences:   {DimSymbolReferences},
	QueryFindCallers:      {DimSymbolReferences},
	QueryFindDependencies: {DimImportResolution},
	QuerySearchText:       {},
	QueryFindConfig:       {DimFrameworkSemantics},
}

// Plan produces a QueryPlan for the given queryType + language. Result is
// fully deterministic for the same input.
func (p *Planner) Plan(qt QueryType, language string) Plan {
	caps := p.capabilityFor(language)

	if qt == QuerySearchText {
		return Plan{
			QueryType:    qt,
			Language:     language,
			Route:        QueryRouteLexicalFirst,
			Capabilities: caps,
		}
	}

	relevant, ok := queryDimensions[qt]
	if !ok || len(relevant) == 0 {
		return Plan{
			QueryType:    qt,
			Language:     language,
			Route:        QueryRouteDegraded,
			Capabilities: caps,
			DegradationNote: "No capability dimensions are mapped for query type " +
				string(qt) + ". This query type may not be supported yet.",
		}
	}

	levels := make(map[CapabilityLevel]struct{})
	for _, d := range relevant {
		lvl, present := caps[d]
		if !present {
			lvl = LevelUnsupported
		}
		levels[lvl] = struct{}{}
	}

	route := selectRoute(levels)
	return Plan{
		QueryType:       qt,
		Language:        language,
		Route:           route,
		Capabilities:    caps,
		DegradationNote: buildDegradationNote(route, qt, language, relevant),
	}
}

// selectRoute applies the deterministic routing rules:
//
//   - any UNSUPPORTED      → DEGRADED
//   - EXACT + LEXICAL_ONLY → MERGED (mixed coverage)
//   - any PARTIAL          → MERGED
//   - any LEXICAL_ONLY     → LEXICAL_FIRST
//   - all EXACT (default)  → GRAPH_FIRST
//
// Priority: DEGRADED > LEXICAL_FIRST > MERGED > GRAPH_FIRST.
func selectRoute(levels map[CapabilityLevel]struct{}) QueryRoute {
	if _, ok := levels[LevelUnsupported]; ok {
		return QueryRouteDegraded
	}
	_, hasLex := levels[LevelLexicalOnly]
	_, hasExact := levels[LevelExact]
	if hasLex && hasExact {
		return QueryRouteMerged
	}
	if _, ok := levels[LevelPartial]; ok {
		return QueryRouteMerged
	}
	if hasLex {
		return QueryRouteLexicalFirst
	}
	return QueryRouteGraphFirst
}

// buildDegradationNote produces a human-readable explanation for
// LEXICAL_FIRST and DEGRADED routes. Returns "" for GRAPH_FIRST and
// MERGED — no explanation needed. The text mirrors the Java side
// byte-for-byte so cross-port regression diffs stay clean.
func buildDegradationNote(route QueryRoute, qt QueryType, language string, dims []CapabilityDimension) string {
	if route == QueryRouteGraphFirst || route == QueryRouteMerged {
		return ""
	}
	lang := "'" + language + "'"
	if strings.TrimSpace(language) == "" {
		lang = "this language"
	}
	names := make([]string, 0, len(dims))
	for _, d := range dims {
		names = append(names, strings.ToLower(strings.ReplaceAll(string(d), "_", " ")))
	}
	dimText := strings.Join(names, ", ")

	if route == QueryRouteDegraded {
		return "Query type " + string(qt) + " is not supported for " + lang + ". " +
			"The current extractor suite has no structural analysis for " + dimText + ". " +
			"Consider running the analysis on a supported language (java, typescript, " +
			"javascript, python, go, csharp, rust) or use SEARCH_TEXT for lexical fallback."
	}
	// LEXICAL_FIRST
	return "Query type " + string(qt) + " for " + lang + " uses lexical search only. " +
		"Structural graph analysis is unavailable for " + dimText + " in " + lang + ". " +
		"Results may be less precise than for fully-supported languages."
}
