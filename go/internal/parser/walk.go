package parser

import (
	"strings"

	sitter "github.com/smacker/go-tree-sitter"
)

// Node is a tree-sitter parse-tree node. Re-exported as a type alias so
// callers can write `parser.Node` without an extra import of the tree-sitter
// SDK. The underlying type is `sitter.Node`, so all its methods (Type,
// ChildByFieldName, StartPoint, ...) are available.
type Node = sitter.Node

// Tree-sitter Node.StartPoint().Row returns uint32; callers wanting an int
// line number should do `int(n.StartPoint().Row) + 1`.

// Walk does a pre-order DFS over n (inclusive). The visitor returns true to
// recurse into the current node's children, false to skip them. Walking stops
// when the visitor returns false at the root or when all descendants have
// been visited. nil-safe.
//
// Implementation uses tree-sitter's TreeCursor for iterative traversal.
// Compared to the previous recursive `n.Child(i)` form, the cursor avoids
// Go-level recursion frames per descent and matches the canonical
// tree-sitter walking idiom. Note: each visited node still flows through
// smacker's per-Tree node cache (allocates one *Node on first visit per
// node), so the allocation count is roughly the same as the recursive form
// — the win is in stack discipline and code clarity, not GC pressure.
func Walk(n *Node, visit func(*Node) bool) {
	if n == nil || visit == nil {
		return
	}
	cur := sitter.NewTreeCursor(n)
	defer cur.Close()
	// Visit root.
	descend := visit(cur.CurrentNode())
	for {
		if descend && cur.GoToFirstChild() {
			descend = visit(cur.CurrentNode())
			continue
		}
		// No children to descend into; advance to next sibling, climbing
		// out of the subtree as necessary. The loop terminates when
		// GoToParent returns false (we have climbed back above the
		// original root).
		for {
			if cur.GoToNextSibling() {
				descend = visit(cur.CurrentNode())
				break
			}
			if !cur.GoToParent() {
				return
			}
			// After climbing to parent, the parent itself was already
			// visited when we descended into it — do NOT re-visit. Continue
			// trying GoToNextSibling at the parent's level.
		}
	}
}

// ChildFieldText returns the source text of the named field of n, or "" if n
// has no such field. Convenience wrapper around ChildByFieldName + node text
// extraction; the caller passes the source string (not bytes) because most
// extractors hold their content as a string already.
func ChildFieldText(n *Node, field, source string) string {
	if n == nil || field == "" {
		return ""
	}
	c := n.ChildByFieldName(field)
	if c == nil {
		return ""
	}
	start, end := int(c.StartByte()), int(c.EndByte())
	if start < 0 || end > len(source) || start >= end {
		return ""
	}
	return source[start:end]
}

// NodeTextFromString is the string-source equivalent of NodeText. Returns ""
// if n is nil or its byte range is outside source.
func NodeTextFromString(n *Node, source string) string {
	if n == nil {
		return ""
	}
	start, end := int(n.StartByte()), int(n.EndByte())
	if start < 0 || end > len(source) || start >= end {
		return ""
	}
	return source[start:end]
}

// ParseByName routes a string language key ("java", "python", "typescript",
// "go") to the typed Parse(Language, ...) call. Returns (nil, error) for
// unknown keys. The string-keyed entry point exists for the intelligence
// extractors, which receive their language as a string off DetectLanguage.
func ParseByName(lang string, source []byte) (*Tree, error) {
	l, err := languageFromName(lang)
	if err != nil {
		return nil, err
	}
	return Parse(l, source)
}

func languageFromName(lang string) (Language, error) {
	// Adding new languages is just an extra case here plus an entry in
	// tsLanguage() and LanguageFromExtension().
	switch strings.ToLower(strings.TrimSpace(lang)) {
	case "java":
		return LanguageJava, nil
	case "python", "py":
		return LanguagePython, nil
	case "typescript", "ts", "tsx", "javascript", "js":
		return LanguageTypeScript, nil
	case "go", "golang":
		return LanguageGo, nil
	case "yaml", "yml":
		return LanguageYaml, nil
	case "json":
		return LanguageJSON, nil
	case "toml":
		return LanguageTOML, nil
	case "ini", "cfg":
		return LanguageINI, nil
	case "properties":
		return LanguageProperties, nil
	case "sql":
		return LanguageSQL, nil
	case "batch", "bat", "cmd":
		return LanguageBatch, nil
	case "vue":
		return LanguageVue, nil
	case "svelte":
		return LanguageSvelte, nil
	}
	return LanguageUnknown, errUnsupportedLanguageName{name: lang}
}

type errUnsupportedLanguageName struct{ name string }

func (e errUnsupportedLanguageName) Error() string {
	return "unsupported language name: " + e.name
}
