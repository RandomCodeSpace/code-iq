package parser

import (
	sitter "github.com/smacker/go-tree-sitter"
	"github.com/smacker/go-tree-sitter/golang"
)

// goLanguage returns the tree-sitter Go grammar.
//
// The smacker package exposes the Go grammar at `.../golang`, NOT `.../go`,
// because the latter would collide with the `go` keyword in the import path.
// Our string-keyed parser API still accepts "go" and "golang" for callers.
func goLanguage() *sitter.Language {
	return golang.GetLanguage()
}
