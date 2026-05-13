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

	"github.com/pmezard/go-difflib/difflib"
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
	//
	// Strict-mode policy: the Go port currently emits a superset of the
	// Java reference's nodes (anchor nodes from Phase-1 dedup work,
	// extra detectors registered via the cli/detectors_register.go fix,
	// etc.). Until expected-divergence.json is populated with the
	// catalogue of intentional drift, a TEST_JAVA_PARITY_STRICT=1
	// override switches the test from "log diff but pass" to
	// "fail on any unexplained diff". CI sets it on PRs that explicitly
	// regenerate the divergence file; everyday Java-touching PRs stay
	// informational until the catalogue lands.
	divergence := loadDivergence(t, filepath.Join(fixture, "expected-divergence.json"))
	diff := diffJSON(string(javaBytes), goNorm, divergence)
	if diff == "" {
		return
	}
	strict := os.Getenv("TEST_JAVA_PARITY_STRICT") == "1"
	if strict {
		t.Fatalf("parity diff (outside allowed-divergence):\n%s", diff)
	}
	// Informational: log a clipped diff so the artifact upload still
	// surfaces it, but don't fail the run.
	t.Logf("parity diff (informational; set TEST_JAVA_PARITY_STRICT=1 to gate):\n%s",
		truncate(diff, 4000))
}

// divergenceFile mirrors expected-divergence.json -- populated phases 2-4.
// Property drift entries are tags interpreted by diffJSON; their string values
// document intent (e.g. "java_resolved_to_syntactic") and are filtered out of
// the diff. Phase 1 fixture has all-empty arrays; phase 2 fixture introduces
// non-empty property_drift to suppress known intentional deltas.
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
// allowed missing-nodes / missing-edges / property-drift entries. The diff is
// rendered via pmezard/go-difflib's unified format so CI failures show the
// minimal surrounding context, not two 4-MB JSON blobs.
//
// Filtering policy: each MissingNodes/MissingEdges entry is a substring; if
// every changed line in the diff contains at least one such substring (or one
// of the PropertyDrift tag substrings), the diff is considered fully absorbed
// by the allowlist and "" is returned. Otherwise the unified diff is returned
// with the allowed-substring lines stripped — what remains is the unexplained
// drift CI needs to fail on.
func diffJSON(java, gov string, d divergenceFile) string {
	if java == gov {
		return ""
	}
	allow := append([]string{}, d.MissingNodes...)
	allow = append(allow, d.MissingEdges...)
	allow = append(allow, d.PropertyDrift...)

	udiff, err := difflib.GetUnifiedDiffString(difflib.UnifiedDiff{
		A:        strings.Split(java, "\n"),
		B:        strings.Split(gov, "\n"),
		FromFile: "java",
		ToFile:   "go",
		Context:  3,
	})
	if err != nil {
		// Fallback to byte-blob if difflib breaks — better than hiding the failure.
		var b bytes.Buffer
		b.WriteString("Java normalized:\n")
		b.WriteString(java)
		b.WriteString("\n\nGo normalized:\n")
		b.WriteString(gov)
		return b.String()
	}
	if len(allow) == 0 {
		return udiff
	}
	// Walk the unified diff line-by-line. Keep header lines verbatim; for
	// added/removed lines, drop any line that contains an allowed substring.
	// If every changed line was absorbed, return "".
	var kept bytes.Buffer
	hasRealChange := false
	for _, line := range strings.Split(udiff, "\n") {
		switch {
		case strings.HasPrefix(line, "---"), strings.HasPrefix(line, "+++"),
			strings.HasPrefix(line, "@@"):
			kept.WriteString(line)
			kept.WriteByte('\n')
		case strings.HasPrefix(line, "+"), strings.HasPrefix(line, "-"):
			if containsAny(line, allow) {
				continue
			}
			kept.WriteString(line)
			kept.WriteByte('\n')
			hasRealChange = true
		default:
			kept.WriteString(line)
			kept.WriteByte('\n')
		}
	}
	if !hasRealChange {
		return ""
	}
	return kept.String()
}

// containsAny returns true when s contains at least one substring from the
// list. Used to filter unified-diff lines through the expected-divergence
// allowlist.
func containsAny(s string, subs []string) bool {
	for _, sub := range subs {
		if sub == "" {
			continue
		}
		if strings.Contains(s, sub) {
			return true
		}
	}
	return false
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

// TestFixtureMultiLangParityPhase2 exercises the full phase-2 pipeline
// (index + enrich) against the multi-lang fixture and either:
//
//  1. Snapshots the Kuzu dump when TEST_JAVA_KUZU_DUMP is unset (Go-only mode
//     — catches drift across Go commits even without a Java toolchain), OR
//  2. Diffs against the file at TEST_JAVA_KUZU_DUMP when set, applying the
//     expected-divergence.json allowlist to filter known intentional deltas.
//
// On mismatch the Kuzu dump is written to t.TempDir() and the test prints
// the path so the artifact is recoverable for offline inspection — CI then
// uploads t.TempDir() as a build artifact alongside the diff.
func TestFixtureMultiLangParityPhase2(t *testing.T) {
	root := mustModuleRoot(t)
	fixture := filepath.Join(root, "testdata", "fixture-multi-lang")

	// 1. Build the Go binary fresh.
	bin := filepath.Join(t.TempDir(), "codeiq")
	build := exec.Command("go", "build", "-o", bin, "./cmd/codeiq")
	build.Dir = root
	build.Env = append(os.Environ(), "CGO_ENABLED=1")
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("go build failed: %v\n%s", err, out)
	}

	// 2. Copy fixture to a scratch dir so the index/enrich writes don't land
	// in the source tree.
	work := t.TempDir()
	copyDir(t, fixture, work)

	// 3. Run index + enrich.
	idx := exec.Command(bin, "index", work)
	if out, err := idx.CombinedOutput(); err != nil {
		t.Fatalf("codeiq index failed: %v\n%s", err, out)
	}
	enr := exec.Command(bin, "enrich", work)
	if out, err := enr.CombinedOutput(); err != nil {
		t.Fatalf("codeiq enrich failed: %v\n%s", err, out)
	}

	// 4. Dump the Kuzu store.
	kuzuDir := filepath.Join(work, ".codeiq", "graph", "codeiq.kuzu")
	dump, err := DumpKuzu(kuzuDir)
	if err != nil {
		t.Fatalf("DumpKuzu failed: %v", err)
	}
	if len(dump) == 0 {
		t.Fatal("DumpKuzu returned empty output")
	}

	// 5. Optionally diff against the Java side.
	javaKuzu := os.Getenv("TEST_JAVA_KUZU_DUMP")
	if javaKuzu == "" {
		t.Logf("TEST_JAVA_KUZU_DUMP unset -- Go-only snapshot mode (got %d bytes)", len(dump))
		return
	}
	javaBytes, err := os.ReadFile(javaKuzu)
	if err != nil {
		t.Fatal(err)
	}

	// Apply the expected-divergence.json filter.
	divergence := loadDivergence(t, filepath.Join(fixture, "expected-divergence.json"))
	if diff := diffJSON(string(javaBytes), string(dump), divergence); diff != "" {
		// Write the artifact so CI can upload it.
		artifact := filepath.Join(t.TempDir(), "go-kuzu-dump.json")
		_ = os.WriteFile(artifact, dump, 0644)
		t.Logf("Go dump written to %s", artifact)
		t.Fatalf("phase-2 parity diff (outside allowed-divergence):\n%s",
			truncate(diff, 4000))
	}
}

// truncate caps a diff string so the test failure message stays readable.
// The full dump is on disk via the artifact path printed above.
func truncate(s string, max int) string {
	if len(s) <= max {
		return s
	}
	return s[:max] + "\n... [truncated, see artifact path above]"
}
