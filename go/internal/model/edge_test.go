package model

import (
	"encoding/json"
	"testing"
)

func TestCodeEdgeNew(t *testing.T) {
	e := NewCodeEdge("e1", EdgeCalls, "src1", "tgt1")
	if e.ID != "e1" || e.Kind != EdgeCalls || e.SourceID != "src1" || e.TargetID != "tgt1" {
		t.Fatalf("constructor mismatch: %+v", e)
	}
	if e.Confidence != ConfidenceLexical {
		t.Fatalf("default Confidence = %v", e.Confidence)
	}
	if e.Properties == nil {
		t.Fatal("Properties must be non-nil")
	}
}

func TestCodeEdgeJSONRoundTrip(t *testing.T) {
	e := NewCodeEdge("e2", EdgeImports, "fileA", "fileB")
	e.Confidence = ConfidenceSyntactic
	e.Source = "GenericImportsDetector"
	e.Properties["module"] = "django.db"

	data, err := json.Marshal(e)
	if err != nil {
		t.Fatal(err)
	}
	var out CodeEdge
	if err := json.Unmarshal(data, &out); err != nil {
		t.Fatal(err)
	}
	if out.ID != e.ID || out.Kind != e.Kind || out.SourceID != e.SourceID || out.TargetID != e.TargetID {
		t.Fatalf("round-trip mismatch: %+v vs %+v", out, e)
	}
	if out.Properties["module"] != "django.db" {
		t.Fatalf("Properties round-trip: %v", out.Properties)
	}
}
