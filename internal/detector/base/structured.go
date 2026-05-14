// Package base structured.go provides shared helpers for structured-data
// detectors (YAML / JSON / TOML / INI / properties). Mirrors the Java
// AbstractStructuredDetector helpers.
package base

import (
	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

// StructuredDetectorDefaultConfidence is the floor for structured detectors.
// Structured parsing produces a parsed shape, not just a regex match, so the
// confidence floor is SYNTACTIC (matches Java
// AbstractStructuredDetector.defaultConfidence()).
const StructuredDetectorDefaultConfidence = model.ConfidenceSyntactic

// AsMap returns obj coerced to map[string]any. Returns nil when obj is nil or
// not a map. Used by structured detectors to navigate parsed data.
func AsMap(obj any) map[string]any {
	if m, ok := obj.(map[string]any); ok {
		return m
	}
	return nil
}

// GetMap returns the nested map at key in container. Returns nil when key is
// missing or the value is not a map.
func GetMap(container any, key string) map[string]any {
	m := AsMap(container)
	if m == nil {
		return nil
	}
	v, ok := m[key]
	if !ok {
		return nil
	}
	return AsMap(v)
}

// GetList returns the nested list at key in container. Returns nil when key
// is missing or the value is not a list.
func GetList(container any, key string) []any {
	m := AsMap(container)
	if m == nil {
		return nil
	}
	v, ok := m[key]
	if !ok {
		return nil
	}
	l, ok := v.([]any)
	if !ok {
		return nil
	}
	return l
}

// GetString returns the string at key in container. Returns "" when the key
// is missing or the value is not a string.
func GetString(container any, key string) string {
	m := AsMap(container)
	if m == nil {
		return ""
	}
	v, ok := m[key]
	if !ok {
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

// GetStringOrDefault returns the string at key or fallback when missing or
// non-string.
func GetStringOrDefault(container any, key, fallback string) string {
	s := GetString(container, key)
	if s == "" {
		return fallback
	}
	return s
}

// BuildFileNode constructs a CONFIG_FILE node for ctx's file. Mirrors the
// Java buildFileNode helper; callers append the returned node themselves.
func BuildFileNode(ctx *detector.Context, format string) *model.CodeNode {
	fp := ctx.FilePath
	fileID := format + ":" + fp
	n := model.NewCodeNode(fileID, model.NodeConfigFile, fp)
	n.FQN = fp
	n.Module = ctx.ModuleName
	n.FilePath = fp
	n.LineStart = 1
	n.Confidence = StructuredDetectorDefaultConfidence
	n.Properties["format"] = format
	return n
}

// AddKeyNode appends a CONFIG_KEY node and a CONTAINS edge from fileID to it.
// Mirrors Java addKeyNode.
func AddKeyNode(fileID, fp, key, format string, ctx *detector.Context,
	nodes *[]*model.CodeNode, edges *[]*model.CodeEdge) {
	keyID := format + ":" + fp + ":" + key
	n := model.NewCodeNode(keyID, model.NodeConfigKey, key)
	n.FQN = fp + ":" + key
	n.Module = ctx.ModuleName
	n.FilePath = fp
	n.Confidence = StructuredDetectorDefaultConfidence
	*nodes = append(*nodes, n)
	e := model.NewCodeEdge(fileID+"->"+keyID, model.EdgeContains, fileID, keyID)
	e.Confidence = StructuredDetectorDefaultConfidence
	*edges = append(*edges, e)
}
