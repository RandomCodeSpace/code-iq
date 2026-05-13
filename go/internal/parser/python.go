package parser

import (
	sitter "github.com/smacker/go-tree-sitter"
	"github.com/smacker/go-tree-sitter/python"
)

func pythonLanguage() *sitter.Language {
	return python.GetLanguage()
}
