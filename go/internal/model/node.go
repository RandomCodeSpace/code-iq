package model

// CodeNode mirrors src/main/java/.../model/CodeNode.java.
//
// Field naming follows snake_case JSON for parity-diffing against a normalized
// SQLite dump. The Java side uses Jackson defaults (camelCase) but the parity
// harness normalizes both sides via a shared shape (see parity/normalize.go),
// so what matters is internal consistency on the Go side.
type CodeNode struct {
	ID          string         `json:"id"`
	Kind        NodeKind       `json:"kind"`
	Label       string         `json:"label"`
	FQN         string         `json:"fqn,omitempty"`
	Module      string         `json:"module,omitempty"`
	FilePath    string         `json:"file_path,omitempty"`
	LineStart   int            `json:"line_start,omitempty"`
	LineEnd     int            `json:"line_end,omitempty"`
	Layer       Layer          `json:"layer"`
	Confidence  Confidence     `json:"confidence"`
	Source      string         `json:"source,omitempty"`
	Annotations []string       `json:"annotations"`
	Properties  map[string]any `json:"properties"`
}

// NewCodeNode constructs a node with required fields populated and slices/maps
// pre-allocated. Defaults Confidence to LEXICAL and Layer to LayerUnknown,
// matching Java behaviour.
func NewCodeNode(id string, kind NodeKind, label string) *CodeNode {
	return &CodeNode{
		ID:          id,
		Kind:        kind,
		Label:       label,
		Layer:       LayerUnknown,
		Confidence:  ConfidenceLexical,
		Annotations: []string{},
		Properties:  map[string]any{},
	}
}
