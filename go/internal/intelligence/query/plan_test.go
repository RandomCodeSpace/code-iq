package query

import "testing"

func TestPlanUsesGraphUsesLexical(t *testing.T) {
	cases := []struct {
		route   QueryRoute
		graph   bool
		lexical bool
	}{
		{QueryRouteGraphFirst, true, false},
		{QueryRouteMerged, true, true},
		{QueryRouteLexicalFirst, false, true},
		{QueryRouteDegraded, false, false},
	}
	for _, c := range cases {
		p := Plan{Route: c.route}
		if p.UsesGraph() != c.graph {
			t.Errorf("Plan{%v}.UsesGraph() = %v, want %v", c.route, p.UsesGraph(), c.graph)
		}
		if p.UsesLexical() != c.lexical {
			t.Errorf("Plan{%v}.UsesLexical() = %v, want %v", c.route, p.UsesLexical(), c.lexical)
		}
	}
}

func TestCapabilityMatrixForJavaAllExact(t *testing.T) {
	caps := CapabilityMatrixFor("java")
	// SYMBOL_DEFINITIONS, SYMBOL_REFERENCES, IMPORT_RESOLUTION, TYPE_INFO,
	// CLASS_HIERARCHY, FRAMEWORK_SEMANTICS, ORM_ENTITY_MAPPING, AUTH_SECURITY
	// are EXACT on Java; ASYNC_PATTERNS is PARTIAL. Mirrors Java fixture.
	exactDims := []CapabilityDimension{
		DimSymbolDefinitions, DimSymbolReferences, DimImportResolution,
		DimTypeInfo, DimClassHierarchy, DimFrameworkSemantics,
		DimOrmEntityMapping, DimAuthSecurity,
	}
	for _, d := range exactDims {
		if got := caps[d]; got != LevelExact {
			t.Errorf("java cap[%s] = %s, want EXACT", d, got)
		}
	}
	if got := caps[DimAsyncPatterns]; got != LevelPartial {
		t.Errorf("java cap[ASYNC_PATTERNS] = %s, want PARTIAL", got)
	}
}

func TestCapabilityMatrixForTypeScriptAllPartial(t *testing.T) {
	caps := CapabilityMatrixFor("typescript")
	for _, d := range AllDimensions() {
		if got := caps[d]; got != LevelPartial {
			t.Errorf("typescript cap[%s] = %s, want PARTIAL", d, got)
		}
	}
}

func TestCapabilityMatrixForLexicalOnly(t *testing.T) {
	caps := CapabilityMatrixFor("kotlin")
	if got := caps[DimSymbolDefinitions]; got != LevelLexicalOnly {
		t.Errorf("kotlin SYMBOL_DEFINITIONS = %s, want LEXICAL_ONLY", got)
	}
	if got := caps[DimTypeInfo]; got != LevelUnsupported {
		t.Errorf("kotlin TYPE_INFO = %s, want UNSUPPORTED", got)
	}
}

func TestCapabilityMatrixForUnknownAllUnsupported(t *testing.T) {
	caps := CapabilityMatrixFor("dont-exist")
	for _, d := range AllDimensions() {
		if got := caps[d]; got != LevelUnsupported {
			t.Errorf("unknown lang cap[%s] = %s, want UNSUPPORTED", d, got)
		}
	}
}

func TestCapabilityMatrixForNormalisesCase(t *testing.T) {
	if a := CapabilityMatrixFor("Java"); a[DimSymbolDefinitions] != LevelExact {
		t.Errorf("CapabilityMatrixFor case-insensitive failed for Java")
	}
	if a := CapabilityMatrixFor("  python  "); a[DimSymbolDefinitions] != LevelPartial {
		t.Errorf("CapabilityMatrixFor trim failed for python")
	}
}

func TestAllCapabilitiesIncludesCoreLanguages(t *testing.T) {
	all := AllCapabilities()
	mustHave := []string{"java", "typescript", "javascript", "python", "go", "csharp", "rust", "cpp", "kotlin"}
	for _, lang := range mustHave {
		if _, ok := all[lang]; !ok {
			t.Errorf("AllCapabilities missing %q", lang)
		}
	}
}
