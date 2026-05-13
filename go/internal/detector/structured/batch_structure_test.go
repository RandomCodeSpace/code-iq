package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestBatchStructureDetector_Positive(t *testing.T) {
	batch := `@ECHO OFF
REM Build script
SET PROJECT_DIR=src

:BUILD
echo Building...
CALL :TEST

:TEST
echo Testing...
`
	d := NewBatchStructureDetector()
	r := d.Detect(&detector.Context{FilePath: "build.bat", Language: "batch", Content: batch})
	// 1 module + 2 labels + 1 SET variable = 4 nodes
	if len(r.Nodes) != 4 {
		t.Fatalf("expected 4 nodes, got %d", len(r.Nodes))
	}
	var sawModule, sawMethod, sawCfgDef bool
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeModule:
			sawModule = true
		case model.NodeMethod:
			sawMethod = true
		case model.NodeConfigDefinition:
			sawCfgDef = true
		}
	}
	if !sawModule || !sawMethod || !sawCfgDef {
		t.Errorf("node kinds incomplete: module=%v method=%v cfgdef=%v", sawModule, sawMethod, sawCfgDef)
	}
	var sawCalls, sawContains bool
	for _, e := range r.Edges {
		if e.Kind == model.EdgeCalls {
			sawCalls = true
		}
		if e.Kind == model.EdgeContains {
			sawContains = true
		}
	}
	if !sawCalls || !sawContains {
		t.Errorf("edge kinds incomplete: calls=%v contains=%v", sawCalls, sawContains)
	}
}

func TestBatchStructureDetector_Negative(t *testing.T) {
	d := NewBatchStructureDetector()
	r := d.Detect(&detector.Context{FilePath: "empty.bat", Language: "batch", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestBatchStructureDetector_Deterministic(t *testing.T) {
	d := NewBatchStructureDetector()
	c := &detector.Context{FilePath: "t.bat", Language: "batch", Content: ":START\necho hello\nSET X=1\nCALL :START"}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatalf("non-deterministic")
	}
}
