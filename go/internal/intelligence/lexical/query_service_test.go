package lexical

import (
	"errors"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// fakeSearchStore is a hand-rolled stub satisfying the FullTextStore
// interface. The Java parity test exercises a real Kuzu fixture; on the
// Go side the Kuzu fts indexes (Task 7) belong to a separate change set,
// so we test the bridging logic through this stub. Wiring against a real
// *graph.Store is exercised via the package-level integration test in
// internal/graph once Task 7 lands.
type fakeSearchStore struct {
	byLabel    map[string][]*model.CodeNode
	byLexical  map[string][]*model.CodeNode
	labelErr   error
	lexicalErr error

	gotLabelLimits   []int
	gotLexicalLimits []int
}

func (f *fakeSearchStore) SearchByLabel(q string, limit int) ([]*model.CodeNode, error) {
	f.gotLabelLimits = append(f.gotLabelLimits, limit)
	if f.labelErr != nil {
		return nil, f.labelErr
	}
	return f.byLabel[q], nil
}

func (f *fakeSearchStore) SearchLexical(q string, limit int) ([]*model.CodeNode, error) {
	f.gotLexicalLimits = append(f.gotLexicalLimits, limit)
	if f.lexicalErr != nil {
		return nil, f.lexicalErr
	}
	return f.byLexical[q], nil
}

func mkNode(id, label string, kind model.NodeKind) *model.CodeNode {
	n := model.NewCodeNode(id, kind, label)
	return n
}

func TestFindByIdentifierMaps(t *testing.T) {
	store := &fakeSearchStore{
		byLabel: map[string][]*model.CodeNode{
			"UserService": {mkNode("u:UserService", "UserService", model.NodeService)},
		},
	}
	qs := NewQueryService(store, nil, "")
	results := qs.FindByIdentifier("UserService", 10)
	if len(results) != 1 {
		t.Fatalf("got %d results, want 1", len(results))
	}
	r := results[0]
	if r.Node.Label != "UserService" {
		t.Errorf("node label = %q", r.Node.Label)
	}
	if r.Source != "identifier" {
		t.Errorf("source = %q, want identifier", r.Source)
	}
}

func TestFindByDocCommentMapsAndSourcesLexComment(t *testing.T) {
	store := &fakeSearchStore{
		byLexical: map[string][]*model.CodeNode{
			"shopping": {mkNode("o:OrderRepository", "OrderRepository", model.NodeRepository)},
		},
	}
	qs := NewQueryService(store, nil, "")
	results := qs.FindByDocComment("shopping", 10)
	if len(results) != 1 {
		t.Fatalf("got %d, want 1", len(results))
	}
	if results[0].Source != KeyLexComment {
		t.Errorf("source = %q, want %q", results[0].Source, KeyLexComment)
	}
}

func TestFindByConfigKeyFiltersToConfigKinds(t *testing.T) {
	cfg := mkNode("c1", "datasource.url", model.NodeConfigKey)
	notCfg := mkNode("s1", "UserService", model.NodeService)
	store := &fakeSearchStore{
		byLexical: map[string][]*model.CodeNode{
			"spring.datasource": {cfg, notCfg},
		},
	}
	qs := NewQueryService(store, nil, "")
	results := qs.FindByConfigKey("spring.datasource", 10)
	if len(results) != 1 {
		t.Fatalf("got %d, want 1 (config only)", len(results))
	}
	if results[0].Node.ID != "c1" {
		t.Errorf("expected config node, got %v", results[0].Node)
	}
	if results[0].Source != KeyLexConfigKeys {
		t.Errorf("source = %q, want %q", results[0].Source, KeyLexConfigKeys)
	}
}

func TestQueryServiceClampLimit(t *testing.T) {
	store := &fakeSearchStore{}
	qs := NewQueryService(store, nil, "")

	qs.FindByIdentifier("x", 0)   // → defaultLimit (50)
	qs.FindByIdentifier("x", -5)  // → defaultLimit
	qs.FindByIdentifier("x", 75)  // → 75 (passes through)
	qs.FindByIdentifier("x", 500) // → maxLimit (200)

	wantLabel := []int{50, 50, 75, 200}
	if len(store.gotLabelLimits) != len(wantLabel) {
		t.Fatalf("recorded %d label limits, want %d", len(store.gotLabelLimits), len(wantLabel))
	}
	for i, w := range wantLabel {
		if store.gotLabelLimits[i] != w {
			t.Errorf("call %d label limit = %d, want %d", i, store.gotLabelLimits[i], w)
		}
	}
}

func TestQueryServiceErrorReturnsNil(t *testing.T) {
	store := &fakeSearchStore{labelErr: errors.New("boom")}
	qs := NewQueryService(store, nil, "")
	if got := qs.FindByIdentifier("x", 10); got != nil {
		t.Fatalf("error path must return nil, got %v", got)
	}
	store2 := &fakeSearchStore{lexicalErr: errors.New("boom")}
	qs2 := NewQueryService(store2, nil, "")
	if got := qs2.FindByDocComment("x", 10); got != nil {
		t.Fatalf("doc-comment error path must return nil, got %v", got)
	}
	if got := qs2.FindByConfigKey("x", 10); got != nil {
		t.Fatalf("config-key error path must return nil, got %v", got)
	}
}

func TestFindByDocCommentAttachesSnippetWhenSnippetStoreSet(t *testing.T) {
	// We don't write a real file fixture here — when root is empty the
	// snippet block is skipped, but we exercise the non-nil snippets path
	// by passing a SnippetStore plus root, then assert the result still
	// rolls up cleanly even though the underlying file is absent.
	store := &fakeSearchStore{
		byLexical: map[string][]*model.CodeNode{
			"x": {mkNode("a", "A", model.NodeClass)},
		},
	}
	qs := NewQueryService(store, NewSnippetStore(), t.TempDir())
	results := qs.FindByDocComment("x", 10)
	if len(results) != 1 {
		t.Fatalf("got %d", len(results))
	}
	if results[0].Snippet != nil {
		t.Errorf("snippet should be nil for absent file, got %+v", results[0].Snippet)
	}
}
