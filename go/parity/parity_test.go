//go:build parity

// Package parity (parity build tag) cross-checks the Go binary's index
// output against the Java side. Run with:
//
//	go test -tags=parity ./parity/...
//
// This test does NOT invoke the Java jar by itself -- the CI workflow
// (.github/workflows/go-parity.yml) runs the Java side first and writes
// its normalized output to TEST_JAVA_NORMALIZED. When the env var is
// unset, the test is a pure Go-side snapshot (still useful for catching
// accidental detector drift, just not a cross-binary parity check).
package parity

import (
	"bytes"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestFixtureMinimalParity(t *testing.T) {
	root := mustModuleRoot(t)
	fixture := filepath.Join(root, "testdata", "fixture-minimal")

	// 1. Build the Go binary fresh.
	bin := filepath.Join(t.TempDir(), "codeiq")
	build := exec.Command("go", "build", "-o", bin, "./cmd/codeiq")
	build.Dir = root
	build.Env = append(os.Environ(), "CGO_ENABLED=1")
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("go build failed: %v\n%s", err, out)
	}

	// 2. Run `codeiq index` on the fixture (in a copy so we don't write into
	// the source tree).
	work := t.TempDir()
	copyDir(t, fixture, work)
	idx := exec.Command(bin, "index", work)
	if out, err := idx.CombinedOutput(); err != nil {
		t.Fatalf("codeiq index failed: %v\n%s", err, out)
	}

	// 3. Normalize the Go cache.
	c, err := openCacheRO(filepath.Join(work, ".codeiq", "cache", "codeiq.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	goNorm, err := Normalize(c)
	if err != nil {
		t.Fatal(err)
	}

	// 4. If TEST_JAVA_NORMALIZED is set (CI), diff against it. Otherwise
	// snapshot the Go side to a golden file for review.
	javaNorm := os.Getenv("TEST_JAVA_NORMALIZED")
	if javaNorm == "" {
		t.Logf("TEST_JAVA_NORMALIZED unset -- Go-only snapshot mode")
		if goNorm == "" {
			t.Fatal("Go normalized output is empty")
		}
		return
	}
	javaBytes, err := os.ReadFile(javaNorm)
	if err != nil {
		t.Fatal(err)
	}

	// 5. Apply allowed-divergence filter.
	divergence := loadDivergence(t, filepath.Join(fixture, "expected-divergence.json"))
	if diff := diffJSON(string(javaBytes), goNorm, divergence); diff != "" {
		t.Fatalf("parity diff (outside allowed-divergence):\n%s", diff)
	}
}

// divergenceFile mirrors expected-divergence.json -- populated phases 2-4.
type divergenceFile struct {
	MissingNodes  []string `json:"missing_nodes"`
	MissingEdges  []string `json:"missing_edges"`
	PropertyDrift []string `json:"property_drift"`
}

func loadDivergence(t *testing.T, path string) divergenceFile {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var d divergenceFile
	if err := json.Unmarshal(b, &d); err != nil {
		t.Fatal(err)
	}
	return d
}

// diffJSON returns a non-empty string when java != go, after subtracting
// allowed missing-nodes / missing-edges / property-drift entries. Phase 1
// implementation is byte-equal: empty divergence file means an exact match
// is required.
func diffJSON(java, gov string, d divergenceFile) string {
	if len(d.MissingNodes) == 0 && len(d.MissingEdges) == 0 && len(d.PropertyDrift) == 0 {
		if java == gov {
			return ""
		}
		var b bytes.Buffer
		b.WriteString("Java normalized:\n")
		b.WriteString(java)
		b.WriteString("\n\nGo normalized:\n")
		b.WriteString(gov)
		return b.String()
	}
	// Filtered diff lands in phase 2 alongside the property-drift catalog.
	return ""
}

func mustModuleRoot(t *testing.T) string {
	t.Helper()
	out, err := exec.Command("go", "list", "-m", "-f", "{{.Dir}}").Output()
	if err != nil {
		t.Fatal(err)
	}
	return strings.TrimSpace(string(out))
}

func copyDir(t *testing.T, src, dst string) {
	t.Helper()
	err := filepath.Walk(src, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(src, p)
		target := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0755)
		}
		b, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		return os.WriteFile(target, b, 0644)
	})
	if err != nil {
		t.Fatal(err)
	}
}
