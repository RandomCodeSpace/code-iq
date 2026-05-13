package mcp

// Default per-call limits. Mirror McpLimitsConfig defaults in the Java
// side (ConfigDefaults.builtIn): perToolTimeoutMs=15000, maxResults=500,
// maxPayloadBytes=2_000_000, ratePerMinute=300, maxDepth=10. The Go side
// owns its own defaults today and will read codeiq.yml `mcp.limits.*`
// once the unified config port lands.
const (
	DefaultMaxResults   = 500
	DefaultMaxDepth     = 10
	DefaultQueryTimeout = 30 // seconds — DBMS-level wall-clock cap, mirrors Neo4jConfig
)

// CapResults clamps a caller-supplied result-count to [1, hardCap].
// Mirrors Java McpTools `Math.min(limit, maxResults)` with a positive
// floor. The cap is enforced in each tool's iteration loop (NOT injected
// as `LIMIT N` into Cypher), per the spec §8 gotcha.
//
// hardCap <= 0 falls back to DefaultMaxResults so callers that haven't
// loaded a config yet still get sane behaviour.
func CapResults(requested, hardCap int) int {
	if hardCap <= 0 {
		hardCap = DefaultMaxResults
	}
	if requested < 1 {
		return 1
	}
	if requested > hardCap {
		return hardCap
	}
	return requested
}

// CapDepth clamps a traversal-depth to [1, hardCap]. Mirrors Java
// `Math.min(depth, maxDepth)` with a positive floor. Phase 3 default
// hardCap is McpLimitsConfig.MaxDepth (10) loaded from codeiq.yml at
// server boot.
//
// hardCap <= 0 falls back to DefaultMaxDepth.
func CapDepth(requested, hardCap int) int {
	if hardCap <= 0 {
		hardCap = DefaultMaxDepth
	}
	if requested < 1 {
		return 1
	}
	if requested > hardCap {
		return hardCap
	}
	return requested
}
