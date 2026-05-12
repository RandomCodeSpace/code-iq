package flow_test

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/flow"
)

// TestKnownViews asserts every documented view is recognised by IsKnownView
// and that obvious typos are rejected. This is the contract the `flow` CLI
// relies on for input validation.
func TestKnownViews(t *testing.T) {
	for _, v := range []string{"overview", "ci", "deploy", "runtime", "auth"} {
		if !flow.IsKnownView(v) {
			t.Errorf("view %q must be known", v)
		}
	}
	for _, v := range []string{"", "bogus", "Overview", "AUTH"} {
		if flow.IsKnownView(v) {
			t.Errorf("view %q must NOT be known (case-sensitive)", v)
		}
	}
}

// TestAllViewsOrder asserts the declaration order of AllViews matches the
// Java side `FlowEngine.AVAILABLE_VIEWS` constant — overview/ci/deploy/
// runtime/auth. Order matters for the generate_flow MCP tool's `views`
// listing.
func TestAllViewsOrder(t *testing.T) {
	got := flow.AllViews()
	want := []flow.View{flow.ViewOverview, flow.ViewCI, flow.ViewDeploy, flow.ViewRuntime, flow.ViewAuth}
	if len(got) != len(want) {
		t.Fatalf("AllViews len = %d, want %d", len(got), len(want))
	}
	for i, v := range got {
		if v != want[i] {
			t.Errorf("AllViews[%d] = %q, want %q", i, v, want[i])
		}
	}
}
