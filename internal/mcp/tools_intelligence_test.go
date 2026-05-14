package mcp_test

import (
	"context"
	"path/filepath"
	"reflect"
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/graph"
	"github.com/randomcodespace/codeiq/internal/intelligence/evidence"
	"github.com/randomcodespace/codeiq/internal/intelligence/lexical"
	iqquery "github.com/randomcodespace/codeiq/internal/intelligence/query"
	"github.com/randomcodespace/codeiq/internal/mcp"
	"github.com/randomcodespace/codeiq/internal/model"
)

func TestRegisterIntelligenceRegistersFour(t *testing.T) {
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterIntelligence(srv, &mcp.Deps{}); err != nil {
		t.Fatalf("RegisterIntelligence: %v", err)
	}
	want := []string{"find_node", "get_evidence_pack", "get_artifact_metadata", "get_capabilities"}
	got := srv.Registry().Names()
	sort.Strings(got)
	sort.Strings(want)
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("intelligence tools:\n got=%v\nwant=%v", got, want)
	}
}

// intelFixtureDeps builds Deps with a Kuzu store containing two nodes
// (one exact-label "UserService" + one partial-label "UserServiceImpl"),
// a planner using the production capability matrix, and an empty
// metadata snapshot. The store-backed bits are required because
// SearchByLabel runs against Kuzu indexes.
func intelFixtureDeps(t *testing.T) *mcp.Deps {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "fx.kuzu")
	s, err := graph.Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = s.Close() })
	if err := s.ApplySchema(); err != nil {
		t.Fatalf("ApplySchema: %v", err)
	}
	nodes := []*model.CodeNode{
		{ID: "cls:UserService", Kind: model.NodeClass, Label: "UserService",
			Layer: model.LayerBackend, FilePath: "src/UserService.java"},
		{ID: "cls:UserServiceImpl", Kind: model.NodeClass, Label: "UserServiceImpl",
			Layer: model.LayerBackend, FilePath: "src/UserServiceImpl.java"},
		{ID: "cls:Other", Kind: model.NodeClass, Label: "Other",
			Layer: model.LayerBackend, FilePath: "src/Other.java"},
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatalf("BulkLoadNodes: %v", err)
	}
	return &mcp.Deps{
		Store:        s,
		QueryPlanner: iqquery.NewPlanner(iqquery.CapabilityMatrixFor),
		MaxResults:   50,
	}
}

func TestFindNodeRequiresQuery(t *testing.T) {
	d := intelFixtureDeps(t)
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterIntelligence(srv, d); err != nil {
		t.Fatalf("RegisterIntelligence: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()
	ctx, cancel := contextDeadline(t)
	defer cancel()
	res, err := sess.CallTool(ctx, sdkCallToolParams("find_node", nil))
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	tc := res.Content[0].(textContent)
	out := unmarshalJSON(t, tc.Text)
	if out["code"] != mcp.CodeInvalidInput {
		t.Fatalf("code = %v, want INVALID_INPUT", out["code"])
	}
}

func TestFindNodeExactMatchPriority(t *testing.T) {
	d := intelFixtureDeps(t)
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterIntelligence(srv, d); err != nil {
		t.Fatalf("RegisterIntelligence: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()
	ctx, cancel := contextDeadline(t)
	defer cancel()
	res, err := sess.CallTool(ctx, sdkCallToolParams("find_node", map[string]any{"query": "UserService"}))
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	tc := res.Content[0].(textContent)
	out := unmarshalJSON(t, tc.Text)
	matches, ok := out["matches"].([]any)
	if !ok || len(matches) < 1 {
		t.Fatalf("matches missing/empty: %v", out)
	}
	// First match should be the exact-label one — "UserService".
	first, _ := matches[0].(map[string]any)
	if first["label"] != "UserService" {
		t.Fatalf("first match label = %v, want UserService", first["label"])
	}
}

func TestFindNodeNoResults(t *testing.T) {
	d := intelFixtureDeps(t)
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterIntelligence(srv, d); err != nil {
		t.Fatalf("RegisterIntelligence: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()
	ctx, cancel := contextDeadline(t)
	defer cancel()
	res, err := sess.CallTool(ctx, sdkCallToolParams("find_node", map[string]any{"query": "DefinitelyNotPresent"}))
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	tc := res.Content[0].(textContent)
	out := unmarshalJSON(t, tc.Text)
	matches, _ := out["matches"].([]any)
	if len(matches) != 0 {
		t.Fatalf("expected empty matches, got %v", out)
	}
}

func TestGetCapabilitiesAllLanguages(t *testing.T) {
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterIntelligence(srv, &mcp.Deps{}); err != nil {
		t.Fatalf("RegisterIntelligence: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()
	ctx, cancel := contextDeadline(t)
	defer cancel()
	res, err := sess.CallTool(ctx, sdkCallToolParams("get_capabilities", nil))
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	tc := res.Content[0].(textContent)
	out := unmarshalJSON(t, tc.Text)
	mat, ok := out["matrix"].(map[string]any)
	if !ok || len(mat) == 0 {
		t.Fatalf("matrix missing/empty: %v", out)
	}
	if _, hasJava := mat["java"]; !hasJava {
		t.Fatalf("matrix missing java row: %v", mat)
	}
}

func TestGetCapabilitiesSpecificLanguage(t *testing.T) {
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterIntelligence(srv, &mcp.Deps{}); err != nil {
		t.Fatalf("RegisterIntelligence: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()
	ctx, cancel := contextDeadline(t)
	defer cancel()
	res, err := sess.CallTool(ctx, sdkCallToolParams("get_capabilities", map[string]any{"language": "python"}))
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	tc := res.Content[0].(textContent)
	out := unmarshalJSON(t, tc.Text)
	if out["language"] != "python" {
		t.Fatalf("language = %v, want python", out["language"])
	}
	caps, ok := out["capabilities"].(map[string]any)
	if !ok || len(caps) == 0 {
		t.Fatalf("capabilities missing: %v", out)
	}
}

func TestGetArtifactMetadataMissing(t *testing.T) {
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterIntelligence(srv, &mcp.Deps{}); err != nil {
		t.Fatalf("RegisterIntelligence: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()
	ctx, cancel := contextDeadline(t)
	defer cancel()
	res, err := sess.CallTool(ctx, sdkCallToolParams("get_artifact_metadata", nil))
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	tc := res.Content[0].(textContent)
	out := unmarshalJSON(t, tc.Text)
	if _, hasErr := out["error"]; !hasErr {
		t.Fatalf("expected error envelope when metadata missing, got %v", out)
	}
}

func TestGetArtifactMetadataPresent(t *testing.T) {
	meta := &evidence.ArtifactMetadata{
		Repository: "github.com/foo/bar",
		Commit:     "abc123",
	}
	d := &mcp.Deps{ArtifactMeta: meta}
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterIntelligence(srv, d); err != nil {
		t.Fatalf("RegisterIntelligence: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()
	ctx, cancel := contextDeadline(t)
	defer cancel()
	res, err := sess.CallTool(ctx, sdkCallToolParams("get_artifact_metadata", nil))
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	tc := res.Content[0].(textContent)
	out := unmarshalJSON(t, tc.Text)
	if out["repository"] != "github.com/foo/bar" {
		t.Fatalf("repository = %v, want github.com/foo/bar", out["repository"])
	}
	if out["commit"] != "abc123" {
		t.Fatalf("commit = %v, want abc123", out["commit"])
	}
}

func TestGetEvidencePackUnwired(t *testing.T) {
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterIntelligence(srv, &mcp.Deps{}); err != nil {
		t.Fatalf("RegisterIntelligence: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()
	ctx, cancel := contextDeadline(t)
	defer cancel()
	res, err := sess.CallTool(ctx, sdkCallToolParams("get_evidence_pack", map[string]any{"symbol": "x"}))
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	tc := res.Content[0].(textContent)
	out := unmarshalJSON(t, tc.Text)
	if _, hasErr := out["error"]; !hasErr {
		t.Fatalf("expected error envelope without Evidence wired, got %v", out)
	}
}

// stubLexFinder + stubGraphReader satisfy the assembler interfaces for
// the evidence-pack integration test without standing up the full graph.
type stubLexFinder struct {
	byIdent map[string][]lexical.Result
}

func (s *stubLexFinder) FindByIdentifier(_ context.Context, symbol string) ([]lexical.Result, error) {
	return s.byIdent[symbol], nil
}
func (s *stubLexFinder) FindByFilePath(_ context.Context, _ string) ([]lexical.Result, error) {
	return nil, nil
}

type stubGraphReader struct{}

func (s *stubGraphReader) FindCallers(_ context.Context, _ string) ([]*model.CodeNode, error) {
	return nil, nil
}
func (s *stubGraphReader) FindDependents(_ context.Context, _ string) ([]*model.CodeNode, error) {
	return nil, nil
}

func TestGetEvidencePackReturnsPack(t *testing.T) {
	node := &model.CodeNode{
		ID: "cls:UserService", Kind: model.NodeClass, Label: "UserService",
		Layer: model.LayerBackend, FilePath: "src/UserService.java",
	}
	lex := &stubLexFinder{byIdent: map[string][]lexical.Result{
		"UserService": {{Node: node, Source: "identifier"}},
	}}
	planner := iqquery.NewPlanner(iqquery.CapabilityMatrixFor)
	asm := evidence.NewAssembler(lex, lexical.NewSnippetStore(), &stubGraphReader{}, planner, "", 50)
	d := &mcp.Deps{Evidence: asm}

	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterIntelligence(srv, d); err != nil {
		t.Fatalf("RegisterIntelligence: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()
	ctx, cancel := contextDeadline(t)
	defer cancel()
	res, err := sess.CallTool(ctx, sdkCallToolParams("get_evidence_pack", map[string]any{"symbol": "UserService"}))
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	tc := res.Content[0].(textContent)
	out := unmarshalJSON(t, tc.Text)
	matched, _ := out["matched_symbols"].([]any)
	if len(matched) != 1 {
		t.Fatalf("matched_symbols len = %d, want 1. body=%v", len(matched), out)
	}
}
