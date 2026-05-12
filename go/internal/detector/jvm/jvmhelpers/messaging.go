package jvmhelpers

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// classRE mirrors AbstractJavaMessagingDetector.CLASS_RE.
var classRE = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)

// ExtractClassName returns the first matching class name in text, or "".
// Mirrors AbstractJavaMessagingDetector.extractClassName (returns null in Java,
// "" in Go — callers must check IsEmpty).
func ExtractClassName(text string) string {
	for _, line := range strings.Split(text, "\n") {
		if m := classRE.FindStringSubmatch(line); m != nil {
			return m[1]
		}
	}
	return ""
}

// AddMessagingEdge mirrors AbstractJavaMessagingDetector.addMessagingEdge.
// Java messaging detectors' defaultConfidence() bumps the regex-default
// LEXICAL up to SYNTACTIC — but that floor is stamped at the orchestration
// boundary (DetectorEmissionDefaults), not here. The helper just returns the
// edge with default ConfidenceLexical; the orchestration layer rewrites it.
func AddMessagingEdge(sourceID, targetID string, kind model.EdgeKind, label string,
	props map[string]any, edges []*model.CodeEdge,
) []*model.CodeEdge {
	e := model.NewCodeEdge(sourceID+"->"+kind.String()+"->"+targetID, kind, sourceID, targetID)
	for k, v := range props {
		e.Properties[k] = v
	}
	return append(edges, e)
}
