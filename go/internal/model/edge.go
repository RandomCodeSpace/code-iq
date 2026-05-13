package model

// CodeEdge mirrors src/main/java/.../model/CodeEdge.java.
//
// Unlike Java SDN, the Go side stores TargetID as a plain string, not a
// back-reference into a CodeNode. GraphBuilder reattaches edges to nodes
// during the flush phase.
type CodeEdge struct {
	ID         string         `json:"id"`
	Kind       EdgeKind       `json:"kind"`
	SourceID   string         `json:"source_id"`
	TargetID   string         `json:"target_id"`
	Confidence Confidence     `json:"confidence"`
	Source     string         `json:"source,omitempty"`
	Properties map[string]any `json:"properties"`
}

func NewCodeEdge(id string, kind EdgeKind, sourceID, targetID string) *CodeEdge {
	return &CodeEdge{
		ID:         id,
		Kind:       kind,
		SourceID:   sourceID,
		TargetID:   targetID,
		Confidence: ConfidenceLexical,
		Properties: map[string]any{},
	}
}
