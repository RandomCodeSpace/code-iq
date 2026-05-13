package lexical

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestEnrichMethodGetsJavadocComment(t *testing.T) {
	dir := t.TempDir()
	src := strings.Join([]string{
		"package x;",
		"",
		"public class Svc {",
		"    /**",
		"     * Returns the user.",
		"     */",
		"    public User get(int id) {",
		"        return null;",
		"    }",
		"}",
	}, "\n")
	if err := os.WriteFile(filepath.Join(dir, "Svc.java"), []byte(src), 0o644); err != nil {
		t.Fatal(err)
	}
	n := model.NewCodeNode("svc:get", model.NodeMethod, "get")
	n.FilePath = "Svc.java"
	n.LineStart = 7

	NewEnricher().Enrich([]*model.CodeNode{n}, dir)

	got, _ := n.Properties[KeyLexComment].(string)
	if got != "Returns the user." {
		t.Fatalf("lex_comment = %q, want %q", got, "Returns the user.")
	}
}

func TestEnrichDocCommentCandidates(t *testing.T) {
	// Spec §10 / LexicalEnricher.java#isDocCommentCandidate:
	// CLASS, ABSTRACT_CLASS, INTERFACE, ENUM, ANNOTATION_TYPE,
	// METHOD, ENDPOINT, ENTITY, SERVICE, REPOSITORY,
	// COMPONENT, GUARD, MIDDLEWARE
	candidates := []model.NodeKind{
		model.NodeClass, model.NodeAbstractClass, model.NodeInterface,
		model.NodeEnum, model.NodeAnnotationType,
		model.NodeMethod, model.NodeEndpoint, model.NodeEntity,
		model.NodeService, model.NodeRepository,
		model.NodeComponent, model.NodeGuard, model.NodeMiddleware,
	}
	for _, k := range candidates {
		if !isDocCommentCandidate(k) {
			t.Errorf("%s should be a doc-comment candidate", k)
		}
	}
	nonCandidates := []model.NodeKind{
		model.NodeModule, model.NodePackage, model.NodeTopic,
		model.NodeConfigFile, model.NodeConfigKey, model.NodeConfigDefinition,
		model.NodeQuery, model.NodeMigration, model.NodeQueue,
	}
	for _, k := range nonCandidates {
		if isDocCommentCandidate(k) {
			t.Errorf("%s should NOT be a doc-comment candidate", k)
		}
	}
}

func TestEnrichConfigNodesGetConfigKeysFqnPreferred(t *testing.T) {
	cfgKey := model.NewCodeNode("k1", model.NodeConfigKey, "datasource")
	cfgKey.FQN = "spring.datasource.url"
	cfgFile := model.NewCodeNode("f1", model.NodeConfigFile, "application.yml")
	cfgFile.FQN = "" // fallback to label
	cfgDef := model.NewCodeNode("d1", model.NodeConfigDefinition, "feature.flag")
	cfgDef.FQN = "feature.flag.enabled"

	dir := t.TempDir()
	NewEnricher().Enrich([]*model.CodeNode{cfgKey, cfgFile, cfgDef}, dir)

	if got := cfgKey.Properties[KeyLexConfigKeys]; got != "spring.datasource.url" {
		t.Errorf("config_key fqn-preferred = %v", got)
	}
	if got := cfgFile.Properties[KeyLexConfigKeys]; got != "application.yml" {
		t.Errorf("config_file label-fallback = %v", got)
	}
	if got := cfgDef.Properties[KeyLexConfigKeys]; got != "feature.flag.enabled" {
		t.Errorf("config_definition fqn = %v", got)
	}
}

func TestEnrichConfigNodesSkipBlankKeys(t *testing.T) {
	blank := model.NewCodeNode("b", model.NodeConfigKey, "   ")
	blank.FQN = ""
	NewEnricher().Enrich([]*model.CodeNode{blank}, t.TempDir())
	if _, ok := blank.Properties[KeyLexConfigKeys]; ok {
		t.Fatal("blank label+fqn should NOT emit lex_config_keys")
	}
}

func TestEnrichFileReadOnceForManyNodes(t *testing.T) {
	dir := t.TempDir()
	src := strings.Join([]string{
		"/** One. */",
		"class A {}",
		"/** Two. */",
		"class B {}",
		"/** Three. */",
		"class C {}",
		"/** Four. */",
		"class D {}",
		"/** Five. */",
		"class E {}",
	}, "\n")
	if err := os.WriteFile(filepath.Join(dir, "All.java"), []byte(src), 0o644); err != nil {
		t.Fatal(err)
	}

	mk := func(id string, line int) *model.CodeNode {
		n := model.NewCodeNode(id, model.NodeClass, id)
		n.FilePath = "All.java"
		n.LineStart = line
		return n
	}
	nodes := []*model.CodeNode{
		mk("A", 2), mk("B", 4), mk("C", 6), mk("D", 8), mk("E", 10),
	}

	// Read-once is hard to prove without instrumentation; we assert all 5
	// candidates are enriched in one pass (i.e. grouping by filePath works
	// — if it didn't, the implementation would either re-read or miss).
	NewEnricher().Enrich(nodes, dir)

	wantBy := map[string]string{
		"A": "One.", "B": "Two.", "C": "Three.", "D": "Four.", "E": "Five.",
	}
	for _, n := range nodes {
		got, _ := n.Properties[KeyLexComment].(string)
		if got != wantBy[n.ID] {
			t.Errorf("%s lex_comment = %q, want %q", n.ID, got, wantBy[n.ID])
		}
	}
}

func TestEnrichPathTraversalGuard(t *testing.T) {
	dir := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret.java"), []byte("/** secret. */\nclass S {}\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	n := model.NewCodeNode("s", model.NodeClass, "S")
	n.FilePath = filepath.Join("..", filepath.Base(outside), "secret.java")
	n.LineStart = 2

	NewEnricher().Enrich([]*model.CodeNode{n}, dir)
	if _, ok := n.Properties[KeyLexComment]; ok {
		t.Fatal("path-escape node must not be enriched")
	}
}

func TestEnrichDeterminism(t *testing.T) {
	dir := t.TempDir()
	src := strings.Join([]string{
		"/** First. */",
		"class A {}",
		"/** Second. */",
		"class B {}",
	}, "\n")
	if err := os.WriteFile(filepath.Join(dir, "T.java"), []byte(src), 0o644); err != nil {
		t.Fatal(err)
	}
	mk := func() []*model.CodeNode {
		a := model.NewCodeNode("A", model.NodeClass, "A")
		a.FilePath = "T.java"
		a.LineStart = 2
		b := model.NewCodeNode("B", model.NodeClass, "B")
		b.FilePath = "T.java"
		b.LineStart = 4
		return []*model.CodeNode{a, b}
	}
	run1 := mk()
	run2 := mk()
	enricher := NewEnricher()
	enricher.Enrich(run1, dir)
	enricher.Enrich(run2, dir)
	for i := range run1 {
		if !reflect.DeepEqual(run1[i].Properties, run2[i].Properties) {
			t.Fatalf("non-deterministic enrichment for %s: %v vs %v",
				run1[i].ID, run1[i].Properties, run2[i].Properties)
		}
	}
}

func TestEnrichSkipsNodesWithoutLineOrPath(t *testing.T) {
	noPath := model.NewCodeNode("p", model.NodeClass, "P")
	noPath.LineStart = 1
	noLine := model.NewCodeNode("l", model.NodeClass, "L")
	noLine.FilePath = "X.java"

	NewEnricher().Enrich([]*model.CodeNode{noPath, noLine}, t.TempDir())
	if _, ok := noPath.Properties[KeyLexComment]; ok {
		t.Fatal("no FilePath: should not be enriched")
	}
	if _, ok := noLine.Properties[KeyLexComment]; ok {
		t.Fatal("no LineStart: should not be enriched")
	}
}
