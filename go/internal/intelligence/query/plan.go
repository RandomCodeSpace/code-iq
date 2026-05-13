package query

// CapabilityDimension names a semantic dimension of language intelligence
// used by the capability matrix. Mirrors Java
// intelligence/query/CapabilityDimension.java 1:1 — same enum identifiers.
type CapabilityDimension string

const (
	// DimSymbolDefinitions — detection of symbol definitions (classes,
	// functions, methods, variables).
	DimSymbolDefinitions CapabilityDimension = "SYMBOL_DEFINITIONS"
	// DimSymbolReferences — detection of symbol references and usages
	// across files.
	DimSymbolReferences CapabilityDimension = "SYMBOL_REFERENCES"
	// DimImportResolution — resolution of import / require / use directives
	// to target symbols.
	DimImportResolution CapabilityDimension = "IMPORT_RESOLUTION"
	// DimTypeInfo — type information extraction (static types, inferred
	// types, generics).
	DimTypeInfo CapabilityDimension = "TYPE_INFO"
	// DimClassHierarchy — class hierarchy and interface / mixin
	// relationship detection.
	DimClassHierarchy CapabilityDimension = "CLASS_HIERARCHY"
	// DimFrameworkSemantics — framework-specific semantics (annotations,
	// decorators, conventions).
	DimFrameworkSemantics CapabilityDimension = "FRAMEWORK_SEMANTICS"
	// DimOrmEntityMapping — ORM entity and relationship mapping detection.
	DimOrmEntityMapping CapabilityDimension = "ORM_ENTITY_MAPPING"
	// DimAuthSecurity — authentication and authorization pattern detection.
	DimAuthSecurity CapabilityDimension = "AUTH_SECURITY"
	// DimAsyncPatterns — async, event-driven, and messaging pattern
	// detection.
	DimAsyncPatterns CapabilityDimension = "ASYNC_PATTERNS"
)

// allDimensions is the canonical declaration order; matches Java enum order.
var allDimensions = []CapabilityDimension{
	DimSymbolDefinitions,
	DimSymbolReferences,
	DimImportResolution,
	DimTypeInfo,
	DimClassHierarchy,
	DimFrameworkSemantics,
	DimOrmEntityMapping,
	DimAuthSecurity,
	DimAsyncPatterns,
}

// AllDimensions returns the full set of capability dimensions in declaration
// order. Returned as a defensive copy so callers can sort / mutate without
// touching package state.
func AllDimensions() []CapabilityDimension {
	out := make([]CapabilityDimension, len(allDimensions))
	copy(out, allDimensions)
	return out
}

// CapabilityLevel describes how well a given dimension is supported for a
// language. Mirrors Java intelligence/CapabilityLevel.java 1:1.
type CapabilityLevel string

const (
	// LevelExact — full AST-level analysis (e.g. Java via JavaParser).
	LevelExact CapabilityLevel = "EXACT"
	// LevelPartial — grammar-based analysis with some structural gaps
	// (e.g. ANTLR-based languages).
	LevelPartial CapabilityLevel = "PARTIAL"
	// LevelLexicalOnly — regex / text-only detection.
	LevelLexicalOnly CapabilityLevel = "LEXICAL_ONLY"
	// LevelUnsupported — no detection at all for this language / dimension.
	LevelUnsupported CapabilityLevel = "UNSUPPORTED"
)

// CapabilityMatrix is a typed snapshot of capability levels per dimension.
// Aliased to a map so JSON marshaling produces the expected
// {dimension: level} shape without a custom MarshalJSON.
type CapabilityMatrix map[CapabilityDimension]CapabilityLevel

// Plan is the planner's output for a given (queryType, language). Field
// names match the Java QueryPlan record so the JSON payload is structurally
// identical to the Java side.
type Plan struct {
	QueryType       QueryType        `json:"query_type"`
	Language        string           `json:"language"`
	Route           QueryRoute       `json:"route"`
	Capabilities    CapabilityMatrix `json:"capabilities"`
	DegradationNote string           `json:"degradation_note,omitempty"`
}

// UsesGraph reports whether the plan involves any graph traversal —
// true for GRAPH_FIRST and MERGED routes.
func (p Plan) UsesGraph() bool {
	return p.Route == QueryRouteGraphFirst || p.Route == QueryRouteMerged
}

// UsesLexical reports whether the plan involves lexical / text search —
// true for LEXICAL_FIRST and MERGED routes.
func (p Plan) UsesLexical() bool {
	return p.Route == QueryRouteLexicalFirst || p.Route == QueryRouteMerged
}
