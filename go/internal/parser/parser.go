package parser

import (
	"context"
	"fmt"
	"strings"

	sitter "github.com/smacker/go-tree-sitter"
)

// Language identifies a supported source language. Phase 1 supports only Java
// and Python; the rest land in phase 2 / phase 4.
type Language int

const (
	LanguageUnknown Language = iota
	LanguageJava
	LanguagePython
	LanguageTypeScript
	LanguageGo
)

func (l Language) String() string {
	switch l {
	case LanguageJava:
		return "java"
	case LanguagePython:
		return "python"
	case LanguageTypeScript:
		return "typescript"
	case LanguageGo:
		return "go"
	default:
		return "unknown"
	}
}

// LanguageFromExtension maps a file extension (including leading dot, e.g.
// ".java") to a Language. Returns LanguageUnknown for anything unsupported.
func LanguageFromExtension(ext string) Language {
	switch strings.ToLower(ext) {
	case ".java":
		return LanguageJava
	case ".py", ".pyw":
		return LanguagePython
	case ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs":
		return LanguageTypeScript
	case ".go":
		return LanguageGo
	default:
		return LanguageUnknown
	}
}

// Tree wraps a parsed *sitter.Tree along with the source bytes so detectors
// can pull node text via tree-sitter's byte-range API.
type Tree struct {
	Lang   Language
	Source []byte
	Root   *sitter.Tree
}

// Close releases the tree-sitter parse tree.
func (t *Tree) Close() {
	if t.Root != nil {
		t.Root.Close()
	}
}

// Parse parses the source bytes in the given language. The returned Tree must
// be Close()d.
func Parse(lang Language, source []byte) (*Tree, error) {
	tsLang, err := tsLanguage(lang)
	if err != nil {
		return nil, err
	}
	p := sitter.NewParser()
	p.SetLanguage(tsLang)
	root, err := p.ParseCtx(context.Background(), nil, source)
	if err != nil {
		return nil, fmt.Errorf("tree-sitter parse: %w", err)
	}
	return &Tree{Lang: lang, Source: source, Root: root}, nil
}

// NodeText returns the source text for a tree-sitter node.
func NodeText(n *sitter.Node, source []byte) string {
	return n.Content(source)
}

func tsLanguage(l Language) (*sitter.Language, error) {
	switch l {
	case LanguageJava:
		return javaLanguage(), nil
	case LanguagePython:
		return pythonLanguage(), nil
	case LanguageTypeScript:
		return typescriptLanguage(), nil
	case LanguageGo:
		return goLanguage(), nil
	default:
		return nil, fmt.Errorf("unsupported language: %v", l)
	}
}
