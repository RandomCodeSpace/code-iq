package evidence

import (
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/internal/intelligence/lexical"
	iqquery "github.com/randomcodespace/codeiq/internal/intelligence/query"
	"github.com/randomcodespace/codeiq/internal/model"
)

// resolveMaxLines clamps the per-request snippet-line cap against the
// assembler's configured ceiling. Nil request → use configured. Negative
// or zero requested → coerced to 1. Above configured → clamped to
// configured. Mirrors EvidencePackAssembler.resolveMaxLines.
func resolveMaxLines(requested *int, configured int) int {
	if requested == nil {
		return configured
	}
	v := *requested
	if v < 1 {
		v = 1
	}
	if v > configured {
		v = configured
	}
	return v
}

// boundSnippet truncates the source to at most maxLines lines, taking the
// first maxLines lines (matches the Java side's deterministic truncation
// rather than re-centring). Snippets already within bounds are returned
// unchanged. line_end is adjusted to reflect the truncated window.
func boundSnippet(s lexical.CodeSnippet, maxLines int) lexical.CodeSnippet {
	if maxLines <= 0 {
		return s
	}
	lines := strings.Split(s.Source, "\n")
	if len(lines) <= maxLines {
		return s
	}
	var sb strings.Builder
	for i := 0; i < maxLines; i++ {
		sb.WriteString(lines[i])
		sb.WriteByte('\n')
	}
	return lexical.CodeSnippet{
		Source:    sb.String(),
		FilePath:  s.FilePath,
		LineStart: s.LineStart,
		LineEnd:   s.LineStart + maxLines - 1,
		Language:  s.Language,
	}
}

// inferLanguage maps a file extension to the canonical language identifier
// used by the planner. Mirrors EvidencePackAssembler.inferLanguage. The
// lexical-side InferLanguage has wider coverage (kotlin/scala/cpp); we
// deliberately keep the assembler-side mapping narrower to match the Java
// shape — anything outside the planner's recognised languages returns
// "unknown" so the QueryPlanner falls back to UNSUPPORTED.
func inferLanguage(filePath string) string {
	dot := strings.LastIndex(filePath, ".")
	if dot < 0 {
		return "unknown"
	}
	switch strings.ToLower(filePath[dot+1:]) {
	case "java":
		return "java"
	case "ts", "tsx":
		return "typescript"
	case "js", "jsx":
		return "javascript"
	case "py":
		return "python"
	case "go":
		return "go"
	case "rs":
		return "rust"
	case "cs":
		return "csharp"
	default:
		return "unknown"
	}
}

// uniqueSortedFiles collects the unique non-empty file paths from a list of
// nodes and returns them sorted lexicographically. Mirrors the Java
// LinkedHashSet → ArrayList → sort pattern.
func uniqueSortedFiles(nodes []*model.CodeNode) []string {
	seen := make(map[string]struct{}, len(nodes))
	for _, n := range nodes {
		if n == nil || n.FilePath == "" {
			continue
		}
		seen[n.FilePath] = struct{}{}
	}
	out := make([]string, 0, len(seen))
	for fp := range seen {
		out = append(out, fp)
	}
	sort.Strings(out)
	return out
}

// provenanceFor builds the per-node provenance map: filePath / lineStart /
// lineEnd / kind plus every property whose key starts with "prov_".
// Mirrors EvidencePackAssembler.provenance lambda. Snake-case keys to
// match the JSON envelope.
func provenanceFor(n *model.CodeNode) map[string]any {
	m := make(map[string]any)
	if n == nil {
		return m
	}
	if n.FilePath != "" {
		m["file_path"] = n.FilePath
	}
	if n.LineStart != 0 {
		m["line_start"] = n.LineStart
	}
	if n.LineEnd != 0 {
		m["line_end"] = n.LineEnd
	}
	// CodeNode.Kind is a typed int enum; render it via the canonical
	// String() value (matches the Java NodeKind#getValue mapping) so the
	// JSON provenance shape is the same as the Java side.
	m["kind"] = n.Kind.String()
	for k, v := range n.Properties {
		if v == nil {
			continue
		}
		if strings.HasPrefix(k, "prov_") {
			m[k] = v
		}
	}
	return m
}

// deriveCapability maps the planner's route to the pack-level capability
// level. Mirrors EvidencePackAssembler.deriveCapabilityLevel switch.
func deriveCapability(route iqquery.QueryRoute) Capability {
	switch route {
	case iqquery.QueryRouteGraphFirst:
		return CapExact
	case iqquery.QueryRouteMerged:
		return CapPartial
	case iqquery.QueryRouteLexicalFirst:
		return CapLexicalOnly
	case iqquery.QueryRouteDegraded:
		return CapUnsupported
	default:
		return CapUnsupported
	}
}
