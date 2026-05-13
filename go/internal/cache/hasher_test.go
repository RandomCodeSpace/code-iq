package cache

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestHashStringKnownVector(t *testing.T) {
	// "hello" → SHA-256: 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
	got := HashString("hello")
	want := "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
	if got != want {
		t.Fatalf("HashString(\"hello\") = %q, want %q", got, want)
	}
	if len(got) != 64 {
		t.Fatalf("expected 64 hex chars, got %d", len(got))
	}
	if strings.ToLower(got) != got {
		t.Fatal("hash must be lowercase")
	}
}

func TestHashFile(t *testing.T) {
	dir := t.TempDir()
	f := filepath.Join(dir, "x.txt")
	if err := os.WriteFile(f, []byte("hello"), 0644); err != nil {
		t.Fatal(err)
	}
	got, err := HashFile(f)
	if err != nil {
		t.Fatal(err)
	}
	if got != "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824" {
		t.Fatalf("HashFile = %q", got)
	}
}

func TestHashFileMissingReturnsError(t *testing.T) {
	_, err := HashFile("/nonexistent/path/zzzz")
	if err == nil {
		t.Fatal("expected error on missing file")
	}
}

func TestHashEmpty(t *testing.T) {
	// SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
	got := HashString("")
	want := "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	if got != want {
		t.Fatalf("HashString(\"\") = %q, want %q", got, want)
	}
}
