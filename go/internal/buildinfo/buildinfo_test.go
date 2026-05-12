package buildinfo

import (
	"runtime"
	"strings"
	"testing"
)

func TestDefaultsWithoutLdflags(t *testing.T) {
	if Version != "dev" {
		t.Fatalf("default Version = %q, want \"dev\"", Version)
	}
	if Commit != "unknown" {
		t.Fatalf("default Commit = %q, want \"unknown\"", Commit)
	}
	if Date != "unknown" {
		t.Fatalf("default Date = %q, want \"unknown\"", Date)
	}
	if Dirty != "false" {
		t.Fatalf("default Dirty = %q, want \"false\"", Dirty)
	}
}

func TestPlatform(t *testing.T) {
	got := Platform()
	want := runtime.GOOS + "/" + runtime.GOARCH
	if got != want {
		t.Fatalf("Platform() = %q, want %q", got, want)
	}
}

func TestGoVersion(t *testing.T) {
	if !strings.HasPrefix(GoVersion(), "go") {
		t.Fatalf("GoVersion() = %q, want prefix \"go\"", GoVersion())
	}
}

func TestFeatures(t *testing.T) {
	f := Features()
	wantContains := []string{"cgo", "kuzu", "sqlite", "tree-sitter"}
	for _, w := range wantContains {
		found := false
		for _, got := range f {
			if got == w {
				found = true
				break
			}
		}
		if !found {
			t.Fatalf("Features() = %v, missing %q", f, w)
		}
	}
}

func TestDirtyBool(t *testing.T) {
	Dirty = "true"
	t.Cleanup(func() { Dirty = "false" })
	if !DirtyBool() {
		t.Fatal("DirtyBool() = false, want true when Dirty == \"true\"")
	}
}
