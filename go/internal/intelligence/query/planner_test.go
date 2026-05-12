package query

import (
	"strings"
	"testing"
)

// allExact returns a CapabilityMatrix where every dimension is EXACT.
func allExact() CapabilityMatrix {
	out := make(CapabilityMatrix, len(allDimensions))
	for _, d := range allDimensions {
		out[d] = LevelExact
	}
	return out
}

// allLexical returns a CapabilityMatrix where every dimension is LEXICAL_ONLY.
func allLexical() CapabilityMatrix {
	out := make(CapabilityMatrix, len(allDimensions))
	for _, d := range allDimensions {
		out[d] = LevelLexicalOnly
	}
	return out
}

// allUnsupported returns a CapabilityMatrix where every dimension is UNSUPPORTED.
func allUnsupported() CapabilityMatrix {
	out := make(CapabilityMatrix, len(allDimensions))
	for _, d := range allDimensions {
		out[d] = LevelUnsupported
	}
	return out
}

// fixed returns a planner whose capability provider always returns m, regardless
// of language. Lets each test isolate routing logic from the per-language tables.
func fixed(m CapabilityMatrix) *Planner {
	return NewPlanner(func(string) CapabilityMatrix { return m })
}

func TestPlannerAllExactGraphFirst(t *testing.T) {
	p := fixed(allExact())
	plan := p.Plan(QueryFindSymbol, "java")
	if plan.Route != QueryRouteGraphFirst {
		t.Fatalf("route = %s, want GRAPH_FIRST", plan.Route)
	}
	if plan.DegradationNote != "" {
		t.Errorf("expected no degradation note for GRAPH_FIRST, got %q", plan.DegradationNote)
	}
}

func TestPlannerAnyUnsupportedDegraded(t *testing.T) {
	m := allExact()
	m[DimSymbolDefinitions] = LevelUnsupported
	p := fixed(m)
	plan := p.Plan(QueryFindSymbol, "wat")
	if plan.Route != QueryRouteDegraded {
		t.Fatalf("route = %s, want DEGRADED", plan.Route)
	}
	if plan.DegradationNote == "" {
		t.Errorf("expected degradation note for DEGRADED")
	}
	if !strings.Contains(plan.DegradationNote, "FIND_SYMBOL") {
		t.Errorf("degradation note should mention query type, got %q", plan.DegradationNote)
	}
}

func TestPlannerMixedExactAndLexicalMerged(t *testing.T) {
	// For FIND_DEPENDENCIES the relevant dim is IMPORT_RESOLUTION. Set it to
	// LEXICAL_ONLY plus another dim to EXACT — but the planner only inspects
	// the relevant dim list, so we need a multi-dimension query type. Use a
	// hand-rolled fixture where the planner sees both levels in its relevant
	// set: extend queryDimensions via a stub query type isn't possible (the
	// map is private), so instead test the helper selectRoute directly.
	got := selectRoute(map[CapabilityLevel]struct{}{
		LevelExact:       {},
		LevelLexicalOnly: {},
	})
	if got != QueryRouteMerged {
		t.Errorf("selectRoute mixed = %s, want MERGED", got)
	}
}

func TestPlannerPartialMerged(t *testing.T) {
	m := allExact()
	m[DimSymbolDefinitions] = LevelPartial
	p := fixed(m)
	plan := p.Plan(QueryFindSymbol, "typescript")
	if plan.Route != QueryRouteMerged {
		t.Fatalf("route = %s, want MERGED", plan.Route)
	}
	if plan.DegradationNote != "" {
		t.Errorf("expected no degradation note for MERGED, got %q", plan.DegradationNote)
	}
}

func TestPlannerAllLexicalLexicalFirst(t *testing.T) {
	p := fixed(allLexical())
	plan := p.Plan(QueryFindSymbol, "kotlin")
	if plan.Route != QueryRouteLexicalFirst {
		t.Fatalf("route = %s, want LEXICAL_FIRST", plan.Route)
	}
	if plan.DegradationNote == "" {
		t.Errorf("expected degradation note for LEXICAL_FIRST")
	}
	if !strings.Contains(plan.DegradationNote, "kotlin") {
		t.Errorf("degradation note should mention language, got %q", plan.DegradationNote)
	}
}

func TestPlannerSearchTextAlwaysLexicalFirst(t *testing.T) {
	// Even with all-EXACT capabilities, SEARCH_TEXT is special-cased.
	p := fixed(allExact())
	plan := p.Plan(QuerySearchText, "java")
	if plan.Route != QueryRouteLexicalFirst {
		t.Fatalf("route = %s, want LEXICAL_FIRST for SEARCH_TEXT", plan.Route)
	}
	if plan.DegradationNote != "" {
		t.Errorf("SEARCH_TEXT should not carry a degradation note, got %q", plan.DegradationNote)
	}
}

func TestPlannerUnknownQueryTypeDegraded(t *testing.T) {
	p := fixed(allExact())
	plan := p.Plan(QueryType("UNKNOWN_KIND"), "java")
	if plan.Route != QueryRouteDegraded {
		t.Fatalf("route = %s, want DEGRADED for unknown query type", plan.Route)
	}
	if !strings.Contains(plan.DegradationNote, "No capability dimensions") {
		t.Errorf("expected 'No capability dimensions' note, got %q", plan.DegradationNote)
	}
}

func TestPlannerCapabilitiesEchoedInPlan(t *testing.T) {
	caps := allExact()
	p := fixed(caps)
	plan := p.Plan(QueryFindSymbol, "java")
	if len(plan.Capabilities) != len(caps) {
		t.Fatalf("capabilities length = %d, want %d", len(plan.Capabilities), len(caps))
	}
	for k, v := range caps {
		if plan.Capabilities[k] != v {
			t.Errorf("capabilities[%s] = %s, want %s", k, plan.Capabilities[k], v)
		}
	}
}

func TestPlannerLexicalDegradationMentionsRelevantDims(t *testing.T) {
	p := fixed(allLexical())
	plan := p.Plan(QueryFindDependencies, "kotlin")
	if plan.Route != QueryRouteLexicalFirst {
		t.Fatalf("route = %s", plan.Route)
	}
	// FIND_DEPENDENCIES → IMPORT_RESOLUTION → "import resolution" (underscore→space, lower)
	if !strings.Contains(plan.DegradationNote, "import resolution") {
		t.Errorf("degradation note should mention 'import resolution', got %q", plan.DegradationNote)
	}
}

func TestPlannerBlankLanguageRendersAsThisLanguage(t *testing.T) {
	p := fixed(allLexical())
	plan := p.Plan(QueryFindSymbol, "   ")
	if !strings.Contains(plan.DegradationNote, "this language") {
		t.Errorf("blank language should be rendered as 'this language', got %q", plan.DegradationNote)
	}
}
