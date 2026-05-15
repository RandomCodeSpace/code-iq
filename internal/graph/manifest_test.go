package graph_test

import (
	"path/filepath"
	"testing"

	"github.com/randomcodespace/codeiq/internal/graph"
)

func TestReadManifestEmptyOnFreshGraph(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	got, err := s.ReadManifest()
	if err != nil {
		t.Fatalf("ReadManifest: %v", err)
	}
	if got != "" {
		t.Fatalf("fresh manifest = %q, want \"\"", got)
	}
}

func TestWriteThenReadManifestRoundTrip(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	want := "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	if err := s.WriteManifest(want); err != nil {
		t.Fatalf("WriteManifest: %v", err)
	}
	got, err := s.ReadManifest()
	if err != nil {
		t.Fatalf("ReadManifest: %v", err)
	}
	if got != want {
		t.Fatalf("ReadManifest = %q, want %q", got, want)
	}
}

func TestWriteManifestOverwrites(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	if err := s.WriteManifest("h1"); err != nil {
		t.Fatal(err)
	}
	if err := s.WriteManifest("h2"); err != nil {
		t.Fatal(err)
	}
	got, _ := s.ReadManifest()
	if got != "h2" {
		t.Fatalf("got %q after overwrite, want h2", got)
	}
}

func TestManifestRejectedOnReadOnly(t *testing.T) {
	path := filepath.Join(t.TempDir(), "g.kuzu")
	w, err := graph.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := w.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	w.Close()

	ro, err := graph.OpenReadOnly(path, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer ro.Close()
	if err := ro.WriteManifest("blocked"); err == nil {
		t.Fatal("WriteManifest should fail on read-only store")
	}
}
