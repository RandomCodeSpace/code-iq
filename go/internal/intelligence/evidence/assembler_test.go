package evidence

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/intelligence/lexical"
	iqquery "github.com/randomcodespace/codeiq/go/internal/intelligence/query"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// ---------- fakes ----------

// fakeLexFinder stubs LexFinder. By-symbol and by-file lookups return canned
// results indexed by query string / file path. limit captures the most
// recently observed limit so tests can assert the clamping behaviour.
type fakeLexFinder struct {
	bySymbol   map[string][]lexical.Result
	byFilePath map[string][]lexical.Result
}

func (f *fakeLexFinder) FindByIdentifier(_ context.Context, symbol string) ([]lexical.Result, error) {
	return f.bySymbol[symbol], nil
}

func (f *fakeLexFinder) FindByFilePath(_ context.Context, filePath string) ([]lexical.Result, error) {
	return f.byFilePath[filePath], nil
}

// fakeGraphReader stubs the GraphReader interface. Callers / dependents
// lookups return canned nodes indexed by source id.
type fakeGraphReader struct {
	callers    map[string][]*model.CodeNode
	dependents map[string][]*model.CodeNode
}

func (f *fakeGraphReader) FindCallers(_ context.Context, id string) ([]*model.CodeNode, error) {
	return f.callers[id], nil
}

func (f *fakeGraphReader) FindDependents(_ context.Context, id string) ([]*model.CodeNode, error) {
	return f.dependents[id], nil
}

// fixedPlanner builds a planner that always returns a CapabilityMatrix with
// every dimension set to the given level — handy for nailing down the route
// of a test plan deterministically.
func fixedPlanner(level iqquery.CapabilityLevel) *iqquery.Planner {
	return iqquery.NewPlanner(func(string) iqquery.CapabilityMatrix {
		out := make(iqquery.CapabilityMatrix)
		for _, d := range iqquery.AllDimensions() {
			out[d] = level
		}
		return out
	})
}

// writeFile is the standard test fixture helper.
func writeFile(t *testing.T, dir, name, content string) string {
	t.Helper()
	full := filepath.Join(dir, name)
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return full
}

// ---------- tests ----------

func TestAssembleEmptyRequest(t *testing.T) {
	a := NewAssembler(&fakeLexFinder{}, lexical.NewSnippetStore(),
		&fakeGraphReader{}, fixedPlanner(iqquery.LevelExact), "/tmp", 50)
	pack, err := a.Assemble(context.Background(), Request{}, nil)
	if err != nil {
		t.Fatalf("Assemble: %v", err)
	}
	if pack.CapabilityLevel != CapUnsupported {
		t.Errorf("empty req → %s, want UNSUPPORTED", pack.CapabilityLevel)
	}
	if len(pack.DegradationNotes) != 1 ||
		!strings.Contains(pack.DegradationNotes[0], "No symbol or file path") {
		t.Errorf("expected 'No symbol or file path' note, got %v", pack.DegradationNotes)
	}
}

func TestAssembleSymbolMissingProducesEmptyPackWithNote(t *testing.T) {
	a := NewAssembler(&fakeLexFinder{}, lexical.NewSnippetStore(),
		&fakeGraphReader{}, fixedPlanner(iqquery.LevelExact), "/tmp", 50)
	pack, err := a.Assemble(context.Background(),
		Request{Symbol: "DoesNotExist"}, nil)
	if err != nil {
		t.Fatalf("Assemble: %v", err)
	}
	if len(pack.MatchedSymbols) != 0 {
		t.Errorf("expected 0 matches, got %d", len(pack.MatchedSymbols))
	}
	if pack.CapabilityLevel != CapUnsupported {
		t.Errorf("empty-result CapabilityLevel = %s, want UNSUPPORTED", pack.CapabilityLevel)
	}
	if len(pack.DegradationNotes) != 1 {
		t.Fatalf("expected 1 note, got %v", pack.DegradationNotes)
	}
	if !strings.Contains(pack.DegradationNotes[0], "DoesNotExist") {
		t.Errorf("note should mention subject, got %q", pack.DegradationNotes[0])
	}
}

func TestAssembleByKnownSymbolPopulatesMatchesAndRelatedFiles(t *testing.T) {
	dir := t.TempDir()
	src := strings.Join([]string{
		"package x;",
		"public class UserService {",
		"    public void greet() {}",
		"}",
	}, "\n")
	writeFile(t, dir, "src/x/UserService.java", src)

	node := model.NewCodeNode("u:UserService", model.NodeService, "UserService")
	node.FilePath = "src/x/UserService.java"
	node.LineStart = 2
	node.LineEnd = 4

	lex := &fakeLexFinder{
		bySymbol: map[string][]lexical.Result{
			"UserService": {{Node: node, Source: "identifier"}},
		},
	}
	a := NewAssembler(lex, lexical.NewSnippetStore(),
		&fakeGraphReader{}, fixedPlanner(iqquery.LevelExact), dir, 50)

	pack, err := a.Assemble(context.Background(),
		Request{Symbol: "UserService"}, nil)
	if err != nil {
		t.Fatalf("Assemble: %v", err)
	}
	if len(pack.MatchedSymbols) != 1 {
		t.Fatalf("matched = %d, want 1", len(pack.MatchedSymbols))
	}
	if pack.MatchedSymbols[0].Label != "UserService" {
		t.Errorf("matched label = %q", pack.MatchedSymbols[0].Label)
	}
	if len(pack.RelatedFiles) != 1 || pack.RelatedFiles[0] != "src/x/UserService.java" {
		t.Errorf("related_files = %v", pack.RelatedFiles)
	}
	if len(pack.Snippets) != 1 {
		t.Fatalf("snippets = %d, want 1", len(pack.Snippets))
	}
	if !strings.Contains(pack.Snippets[0].Source, "UserService") {
		t.Errorf("snippet should include symbol body, got %q", pack.Snippets[0].Source)
	}
	// GRAPH_FIRST (all-EXACT) → CapExact, no degradation notes.
	if pack.CapabilityLevel != CapExact {
		t.Errorf("CapabilityLevel = %s, want EXACT", pack.CapabilityLevel)
	}
	if len(pack.DegradationNotes) != 0 {
		t.Errorf("expected no degradation notes for GRAPH_FIRST, got %v", pack.DegradationNotes)
	}
}

func TestAssembleIncludesReferencesWhenRequested(t *testing.T) {
	target := model.NewCodeNode("svc:UserService", model.NodeService, "UserService")
	target.FilePath = "x.java"
	target.LineStart = 1

	caller := model.NewCodeNode("ctrl:UserController", model.NodeClass, "UserController")
	caller.FilePath = "y.java"

	dependent := model.NewCodeNode("app:App", model.NodeClass, "App")
	dependent.FilePath = "z.java"

	lex := &fakeLexFinder{
		bySymbol: map[string][]lexical.Result{
			"UserService": {{Node: target, Source: "identifier"}},
		},
	}
	gr := &fakeGraphReader{
		callers:    map[string][]*model.CodeNode{"svc:UserService": {caller}},
		dependents: map[string][]*model.CodeNode{"svc:UserService": {dependent}},
	}
	a := NewAssembler(lex, lexical.NewSnippetStore(),
		gr, fixedPlanner(iqquery.LevelExact), t.TempDir(), 50)

	pack, err := a.Assemble(context.Background(),
		Request{Symbol: "UserService", IncludeReferences: true}, nil)
	if err != nil {
		t.Fatalf("Assemble: %v", err)
	}
	if len(pack.References) != 2 {
		t.Fatalf("references = %d, want 2 (caller + dependent), got %v",
			len(pack.References), pack.References)
	}
	gotIDs := map[string]bool{}
	for _, r := range pack.References {
		gotIDs[r.ID] = true
	}
	if !gotIDs["ctrl:UserController"] || !gotIDs["app:App"] {
		t.Errorf("references missing expected ids, got %v", gotIDs)
	}
}

func TestAssembleByFilePathDelegatesToGraphLookup(t *testing.T) {
	node := model.NewCodeNode("c:X", model.NodeClass, "X")
	node.FilePath = "src/x/X.java"
	node.LineStart = 1

	lex := &fakeLexFinder{
		byFilePath: map[string][]lexical.Result{
			"src/x/X.java": {{Node: node, Source: "file_path"}},
		},
	}
	a := NewAssembler(lex, lexical.NewSnippetStore(),
		&fakeGraphReader{}, fixedPlanner(iqquery.LevelExact),
		t.TempDir(), 50)

	pack, err := a.Assemble(context.Background(),
		Request{FilePath: "src/x/X.java"}, nil)
	if err != nil {
		t.Fatalf("Assemble: %v", err)
	}
	if len(pack.MatchedSymbols) != 1 || pack.MatchedSymbols[0].ID != "c:X" {
		t.Errorf("matched = %v", pack.MatchedSymbols)
	}
}

func TestAssemblePropagatesPlannerDegradationNote(t *testing.T) {
	node := model.NewCodeNode("k:M", model.NodeClass, "M")
	node.FilePath = "M.kt"
	node.LineStart = 1
	lex := &fakeLexFinder{
		bySymbol: map[string][]lexical.Result{"M": {{Node: node, Source: "identifier"}}},
	}
	// LEXICAL_ONLY planner so the FIND_SYMBOL plan goes LEXICAL_FIRST, which
	// carries a degradation note that should be echoed into the pack.
	a := NewAssembler(lex, lexical.NewSnippetStore(),
		&fakeGraphReader{}, fixedPlanner(iqquery.LevelLexicalOnly),
		t.TempDir(), 50)

	pack, err := a.Assemble(context.Background(),
		Request{Symbol: "M", FilePath: "M.kt"}, nil)
	if err != nil {
		t.Fatalf("Assemble: %v", err)
	}
	if pack.CapabilityLevel != CapLexicalOnly {
		t.Errorf("CapabilityLevel = %s, want LEXICAL_ONLY", pack.CapabilityLevel)
	}
	if len(pack.DegradationNotes) != 1 {
		t.Fatalf("expected 1 degradation note, got %v", pack.DegradationNotes)
	}
	if !strings.Contains(pack.DegradationNotes[0], "FIND_SYMBOL") {
		t.Errorf("note should mention query type, got %q", pack.DegradationNotes[0])
	}
}

func TestAssembleProvenanceParallelToMatched(t *testing.T) {
	n := &model.CodeNode{
		ID:        "x",
		Kind:      model.NodeClass,
		Label:     "X",
		FilePath:  "X.java",
		LineStart: 1,
		LineEnd:   1,
		Properties: map[string]any{
			"prov_commit": "abc",
		},
	}
	lex := &fakeLexFinder{
		bySymbol: map[string][]lexical.Result{"X": {{Node: n, Source: "identifier"}}},
	}
	a := NewAssembler(lex, lexical.NewSnippetStore(),
		&fakeGraphReader{}, fixedPlanner(iqquery.LevelExact),
		t.TempDir(), 50)

	pack, err := a.Assemble(context.Background(),
		Request{Symbol: "X"}, nil)
	if err != nil {
		t.Fatalf("Assemble: %v", err)
	}
	if len(pack.Provenance) != 1 {
		t.Fatalf("provenance entries = %d, want 1", len(pack.Provenance))
	}
	if pack.Provenance[0]["prov_commit"] != "abc" {
		t.Errorf("missing prov_commit in provenance, got %v", pack.Provenance[0])
	}
}
