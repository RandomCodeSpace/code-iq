package lexical

import (
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// Property keys stamped onto CodeNode.Properties; persisted to the graph as
// prop_lex_* via the prop_* round-trip convention and indexed by the
// lexical_index full-text index.
const (
	KeyLexComment    = "lex_comment"
	KeyLexConfigKeys = "lex_config_keys"
)

// Enricher populates lexical metadata on CodeNodes prior to graph bulk-load.
// Mirrors LexicalEnricher.java.
type Enricher struct{}

// NewEnricher returns a stateless lexical enricher.
func NewEnricher() *Enricher { return &Enricher{} }

// Enrich populates `lex_comment` on doc-comment candidate nodes and
// `lex_config_keys` on config nodes. Source files are grouped by filePath so
// each file is read at most once across the input slice. File-group iteration
// order is sorted for deterministic output.
func (e *Enricher) Enrich(nodes []*model.CodeNode, root string) {
	absRoot, err := filepath.Abs(root)
	if err != nil {
		return
	}
	// Config keys are pure node-property work — no file I/O.
	for _, n := range nodes {
		if n == nil || !isConfigKind(n.Kind) {
			continue
		}
		key := n.FQN
		if key == "" {
			key = n.Label
		}
		if strings.TrimSpace(key) == "" {
			continue
		}
		if n.Properties == nil {
			n.Properties = map[string]any{}
		}
		n.Properties[KeyLexConfigKeys] = key
	}
	// Doc comments: group candidate nodes by filePath so each source file is
	// read at most once.
	byFile := map[string][]*model.CodeNode{}
	for _, n := range nodes {
		if n == nil || !isDocCommentCandidate(n.Kind) {
			continue
		}
		if n.FilePath == "" || n.LineStart <= 0 {
			continue
		}
		byFile[n.FilePath] = append(byFile[n.FilePath], n)
	}
	paths := make([]string, 0, len(byFile))
	for p := range byFile {
		paths = append(paths, p)
	}
	sort.Strings(paths) // determinism across runs
	for _, fp := range paths {
		full := filepath.Clean(filepath.Join(absRoot, fp))
		rel, err := filepath.Rel(absRoot, full)
		if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			continue
		}
		data, err := os.ReadFile(full)
		if err != nil {
			continue
		}
		lines := strings.Split(string(data), "\n")
		lang := InferLanguage(fp)
		for _, n := range byFile[fp] {
			comment := Extract(lines, lang, n.LineStart)
			if strings.TrimSpace(comment) == "" {
				continue
			}
			if n.Properties == nil {
				n.Properties = map[string]any{}
			}
			n.Properties[KeyLexComment] = comment
		}
	}
}

// isConfigKind returns true for the three config-typed node kinds whose
// label/FQN encodes a config key path.
func isConfigKind(k model.NodeKind) bool {
	switch k {
	case model.NodeConfigKey, model.NodeConfigFile, model.NodeConfigDefinition:
		return true
	}
	return false
}

// isDocCommentCandidate returns true for node kinds that typically carry
// doc comments. Mirrors LexicalEnricher#isDocCommentCandidate.
func isDocCommentCandidate(k model.NodeKind) bool {
	switch k {
	case model.NodeClass, model.NodeAbstractClass, model.NodeInterface,
		model.NodeEnum, model.NodeAnnotationType,
		model.NodeMethod, model.NodeEndpoint, model.NodeEntity,
		model.NodeService, model.NodeRepository,
		model.NodeComponent, model.NodeGuard, model.NodeMiddleware:
		return true
	}
	return false
}
