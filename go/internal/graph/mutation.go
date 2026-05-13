package graph

import (
	"regexp"
	"strings"
)

// Blocked mutation keywords. Mirrors Java McpTools.runCypher BLOCKED_PATTERNS
// + a few Kuzu-specific writers (COPY). CALL is handled separately below
// because the read-only procedures (CALL db.*, CALL show_*) must be
// allowed while CALL <anything-else> must be blocked. Go's RE2 engine has
// no lookahead, so the CALL detector uses a two-stage match (CALL match
// → allow-list filter).
//
// Comments are stripped before matching so commented-out keywords inside
// `/* CREATE */` or `// CREATE` are ignored. Word boundaries (`\b`) prevent
// matching keywords inside identifiers like `CREATED_AT`.
// The DETACH-before-DELETE ordering matters: "MATCH (n) DETACH DELETE n"
// should surface "DETACH" as the matched keyword (the more specific
// signal), not "DELETE". MutationKeyword scans for the first match
// position across all patterns, so ordering inside the slice doesn't
// matter — the position-sort below is what makes DETACH win.
var blockedPatterns = []*regexp.Regexp{
	regexp.MustCompile(`(?i)\bCREATE\b`),
	regexp.MustCompile(`(?i)\bDELETE\b`),
	regexp.MustCompile(`(?i)\bDETACH\b`),
	regexp.MustCompile(`(?i)\bSET\b`),
	regexp.MustCompile(`(?i)\bREMOVE\b`),
	regexp.MustCompile(`(?i)\bMERGE\b`),
	regexp.MustCompile(`(?i)\bDROP\b`),
	regexp.MustCompile(`(?i)\bFOREACH\b`),
	regexp.MustCompile(`(?i)\bLOAD\s+CSV\b`),
	regexp.MustCompile(`(?i)\bCOPY\b`),
}

// callRE matches CALL followed by a procedure name. We then check the
// procedure name against the read-only allow-list — anything outside it
// is treated as a mutation.
var callRE = regexp.MustCompile(`(?i)\bCALL\s+(\w+(?:\.\w+)?)`)

// readOnlyCallPrefixes are case-insensitive procedure-name prefixes that
// are permitted under CALL. db.* covers Neo4j's read-only schema
// procedures (db.indexes, db.constraints, db.labels); show_/table_/
// current_setting/table_info cover Kuzu's introspection helpers.
var readOnlyCallPrefixes = []string{
	"db.",
	"show_",
	"table_",
	"current_setting",
	"table_info",
}

// blockCommentRE matches /* … */ and line comments. Both are stripped
// before keyword detection so commented-out writes don't trip the gate.
var (
	blockCommentRE = regexp.MustCompile(`/\*[\s\S]*?\*/`)
	lineCommentRE  = regexp.MustCompile(`//[^\n]*`)
)

// MutationKeyword returns the first matched blocked keyword in q (with
// comments stripped), or "" if the query is read-only. Used by the
// run_cypher MCP tool to reject write queries before they reach Kuzu —
// belt-and-braces alongside the OpenReadOnly system-flag.
func MutationKeyword(q string) string {
	stripped := blockCommentRE.ReplaceAllString(q, " ")
	stripped = lineCommentRE.ReplaceAllString(stripped, " ")
	// Find the earliest match across all blockedPatterns. Earliest wins so
	// "DETACH DELETE" surfaces "DETACH" (the more specific signal), not
	// the keyword that happens to be checked first in the slice.
	earliestStart := -1
	earliest := ""
	for _, p := range blockedPatterns {
		if loc := p.FindStringIndex(stripped); loc != nil {
			if earliestStart == -1 || loc[0] < earliestStart {
				earliestStart = loc[0]
				earliest = strings.TrimSpace(stripped[loc[0]:loc[1]])
			}
		}
	}
	if earliest != "" {
		return earliest
	}
	// CALL gate: every CALL site must reference a read-only prefix.
	for _, m := range callRE.FindAllStringSubmatchIndex(stripped, -1) {
		fullStart, fullEnd := m[0], m[1]
		procStart, procEnd := m[2], m[3]
		proc := strings.ToLower(stripped[procStart:procEnd])
		ok := false
		for _, pref := range readOnlyCallPrefixes {
			if strings.HasPrefix(proc, pref) || proc == strings.TrimSuffix(pref, ".") {
				ok = true
				break
			}
		}
		if !ok {
			return strings.TrimSpace(stripped[fullStart:fullEnd])
		}
	}
	return ""
}
