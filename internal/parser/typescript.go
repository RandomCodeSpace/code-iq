package parser

import (
	sitter "github.com/smacker/go-tree-sitter"
	"github.com/smacker/go-tree-sitter/typescript/typescript"
)

// typescriptLanguage returns the tree-sitter TypeScript grammar.
//
// TS and TSX both parse cleanly with this grammar; the grammar is a superset
// of plain JavaScript so .js/.mjs/.cjs files also parse correctly. The
// `typescript/typescript` import path is intentional — the upstream smacker
// package exposes the grammar as a nested directory `typescript/typescript`
// (and `typescript/tsx` for the TSX-specific variant).
func typescriptLanguage() *sitter.Language {
	return typescript.GetLanguage()
}
