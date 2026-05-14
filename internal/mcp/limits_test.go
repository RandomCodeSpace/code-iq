package mcp

import "testing"

func TestCapResultsHonorsCap(t *testing.T) {
	if got := CapResults(1000, 500); got != 500 {
		t.Fatalf("CapResults(1000, 500) = %d, want 500", got)
	}
	if got := CapResults(10, 500); got != 10 {
		t.Fatalf("CapResults(10, 500) = %d, want 10", got)
	}
	if got := CapResults(-5, 500); got != 1 {
		t.Fatalf("CapResults(-5, 500) = %d, want 1 (clamp floor)", got)
	}
	if got := CapResults(0, 500); got != 1 {
		t.Fatalf("CapResults(0, 500) = %d, want 1 (clamp floor)", got)
	}
	// Fallback when hardCap is unset.
	if got := CapResults(9999, 0); got != DefaultMaxResults {
		t.Fatalf("CapResults(9999, 0) = %d, want default %d", got, DefaultMaxResults)
	}
}

func TestCapDepthHonorsCap(t *testing.T) {
	if got := CapDepth(999, 10); got != 10 {
		t.Fatalf("CapDepth(999, 10) = %d, want 10", got)
	}
	if got := CapDepth(0, 10); got != 1 {
		t.Fatalf("CapDepth(0, 10) = %d, want 1 (clamp floor)", got)
	}
	if got := CapDepth(3, 10); got != 3 {
		t.Fatalf("CapDepth(3, 10) = %d, want 3", got)
	}
	// Fallback default.
	if got := CapDepth(50, 0); got != DefaultMaxDepth {
		t.Fatalf("CapDepth(50, 0) = %d, want default %d", got, DefaultMaxDepth)
	}
}
