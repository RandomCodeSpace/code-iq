package graph_test

import (
	"path/filepath"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/graph"
)

func TestStoreOpenAndClose(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "codeiq.kuzu")
	s, err := graph.Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
}

func TestStoreOpenAtExistingPathSucceeds(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "codeiq.kuzu")
	for i := 0; i < 2; i++ {
		s, err := graph.Open(dir)
		if err != nil {
			t.Fatalf("iter %d: %v", i, err)
		}
		if err := s.Close(); err != nil {
			t.Fatalf("iter %d: %v", i, err)
		}
	}
}
