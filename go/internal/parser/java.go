package parser

import (
	sitter "github.com/smacker/go-tree-sitter"
	"github.com/smacker/go-tree-sitter/java"
)

// javaLanguage returns the tree-sitter Java grammar.
func javaLanguage() *sitter.Language {
	return java.GetLanguage()
}
