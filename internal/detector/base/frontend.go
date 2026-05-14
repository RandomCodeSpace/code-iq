// Package base frontend.go provides shared helpers for frontend component
// detectors (Angular, React, Vue). Mirrors the Java FrontendDetectorHelper.
package base

import (
	"strings"

	"github.com/randomcodespace/codeiq/internal/model"
)

// CreateComponentNode constructs a frontend component / hook / service node
// with the standard fields populated. Equivalent to Java
// FrontendDetectorHelper.createComponentNode.
//
//	framework  e.g. "angular", "react", "vue"
//	filePath   source file path (forward-slash, relative to repo root)
//	idType     namespace segment for the ID ("component", "hook", "service")
//	name       component / class / function name
//	kind       model.NodeComponent | NodeHook | NodeMiddleware
//	line       1-based line number
func CreateComponentNode(framework, filePath, idType, name string, kind model.NodeKind, line int) *model.CodeNode {
	id := framework + ":" + filePath + ":" + idType + ":" + name
	n := model.NewCodeNode(id, kind, name)
	n.FQN = filePath + "::" + name
	n.FilePath = filePath
	n.LineStart = line
	n.Properties["framework"] = framework
	return n
}

// LineAt returns the 1-based line number for a byte offset in text. Mirrors
// the Java lineAt helper (counts \n characters up to offset and adds 1).
func LineAt(text string, offset int) int {
	if offset > len(text) {
		offset = len(text)
	}
	return strings.Count(text[:offset], "\n") + 1
}
