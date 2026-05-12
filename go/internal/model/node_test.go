package model

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestCodeNodeNewDefaultsConfidence(t *testing.T) {
	n := NewCodeNode("a:b:c", NodeClass, "B")
	if n.Confidence != ConfidenceLexical {
		t.Fatalf("new node Confidence = %v, want LEXICAL", n.Confidence)
	}
	if n.ID != "a:b:c" || n.Kind != NodeClass || n.Label != "B" {
		t.Fatalf("constructor field mismatch: %+v", n)
	}
	if n.Properties == nil {
		t.Fatal("Properties should be non-nil (empty map)")
	}
	if n.Annotations == nil {
		t.Fatal("Annotations should be non-nil (empty slice)")
	}
}

func TestCodeNodeJSONRoundTrip(t *testing.T) {
	n := NewCodeNode("file.py:Model", NodeEntity, "Model")
	n.FQN = "app.models.Model"
	n.FilePath = "file.py"
	n.LineStart = 10
	n.LineEnd = 30
	n.Layer = LayerBackend
	n.Confidence = ConfidenceSyntactic
	n.Source = "DjangoModelDetector"
	n.Annotations = []string{"@Entity"}
	n.Properties["framework"] = "django"

	data, err := json.Marshal(n)
	if err != nil {
		t.Fatal(err)
	}
	var out CodeNode
	if err := json.Unmarshal(data, &out); err != nil {
		t.Fatal(err)
	}
	if out.ID != n.ID || out.Kind != n.Kind || out.Label != n.Label {
		t.Fatalf("round-trip core mismatch: %+v vs %+v", out, n)
	}
	if out.Confidence != ConfidenceSyntactic {
		t.Fatalf("Confidence round-trip: %v", out.Confidence)
	}
	if out.Properties["framework"] != "django" {
		t.Fatalf("Properties round-trip: %v", out.Properties)
	}
}

func TestCodeNodeJSONFieldNames(t *testing.T) {
	n := NewCodeNode("id1", NodeMethod, "doit")
	data, _ := json.Marshal(n)
	// must use snake_case JSON keys so Java side's Jackson camelCase reader
	// is not what we target; we target the parity normalizer (see parity/).
	wantKeys := []string{`"id":"id1"`, `"kind":"method"`, `"label":"doit"`}
	for _, k := range wantKeys {
		if !strings.Contains(string(data), k) {
			t.Errorf("JSON missing key fragment %q in %s", k, data)
		}
	}
}
