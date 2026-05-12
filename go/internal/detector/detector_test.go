package detector

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestEmptyResult(t *testing.T) {
	r := EmptyResult()
	if len(r.Nodes) != 0 || len(r.Edges) != 0 {
		t.Fatalf("EmptyResult should be empty: %+v", r)
	}
}

func TestResultOf(t *testing.T) {
	n := model.NewCodeNode("a", model.NodeClass, "A")
	e := model.NewCodeEdge("a->b", model.EdgeCalls, "a", "b")
	r := ResultOf([]*model.CodeNode{n}, []*model.CodeEdge{e})
	if len(r.Nodes) != 1 || len(r.Edges) != 1 {
		t.Fatalf("ResultOf mismatch: %+v", r)
	}
}

// A trivial test implementation that satisfies the Detector interface,
// ensuring the interface signature compiles.
type stubDetector struct{}

func (stubDetector) Name() string                         { return "stub" }
func (stubDetector) SupportedLanguages() []string         { return []string{"java"} }
func (stubDetector) DefaultConfidence() model.Confidence  { return model.ConfidenceLexical }
func (stubDetector) Detect(ctx *Context) *Result          { return EmptyResult() }

func TestDetectorInterfaceCompiles(t *testing.T) {
	var _ Detector = stubDetector{}
}
