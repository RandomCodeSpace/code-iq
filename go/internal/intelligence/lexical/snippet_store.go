package lexical

import (
	"os"
	"path/filepath"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// Snippet sizing — matches SnippetStore.java.
const (
	MaxSnippetLines     = 50
	DefaultContextLines = 3
)

// CodeSnippet is a bounded source extract for evidence packs / lexical results.
// Mirrors the Java CodeSnippet record (Provenance is intentionally omitted on
// the Go side until the intelligence/provenance port lands).
type CodeSnippet struct {
	Source    string
	FilePath  string
	LineStart int
	LineEnd   int
	Language  string
}

// SnippetStore is a stateless extractor. Mirrors SnippetStore.java; held as a
// type so the same shape can be DI'd into LexicalQueryService.
type SnippetStore struct{}

// NewSnippetStore returns a stateless snippet extractor.
func NewSnippetStore() *SnippetStore { return &SnippetStore{} }

// Extract returns a snippet centred on the node's line range with the default
// context (±DefaultContextLines lines). Returns ok=false when the node has no
// location, the file is missing, or the resolved path escapes root.
func (s *SnippetStore) Extract(node *model.CodeNode, root string) (CodeSnippet, bool) {
	return s.ExtractWithContext(node, root, DefaultContextLines)
}

// ExtractWithContext is Extract with a caller-supplied context-line count.
// When the symbol's natural span (with context) exceeds MaxSnippetLines, the
// window is recentred on the midpoint of the symbol range and clamped.
func (s *SnippetStore) ExtractWithContext(node *model.CodeNode, root string, ctx int) (CodeSnippet, bool) {
	if node == nil || node.FilePath == "" || node.LineStart <= 0 {
		return CodeSnippet{}, false
	}
	absRoot, err := filepath.Abs(root)
	if err != nil {
		return CodeSnippet{}, false
	}
	file := filepath.Clean(filepath.Join(absRoot, node.FilePath))
	rel, err := filepath.Rel(absRoot, file)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return CodeSnippet{}, false
	}
	info, err := os.Stat(file)
	if err != nil || !info.Mode().IsRegular() {
		return CodeSnippet{}, false
	}
	content, err := os.ReadFile(file)
	if err != nil {
		return CodeSnippet{}, false
	}
	lines := strings.Split(string(content), "\n")
	total := len(lines)
	symStart := node.LineStart
	symEnd := node.LineEnd
	if symEnd == 0 {
		symEnd = symStart
	}
	start := symStart - ctx
	if start < 1 {
		start = 1
	}
	end := symEnd + ctx
	if end > total {
		end = total
	}
	if end-start+1 > MaxSnippetLines {
		centre := (symStart + symEnd) / 2
		start = centre - MaxSnippetLines/2
		if start < 1 {
			start = 1
		}
		end = start + MaxSnippetLines - 1
		if end > total {
			end = total
		}
	}
	var sb strings.Builder
	for i := start - 1; i < end; i++ {
		sb.WriteString(lines[i])
		sb.WriteByte('\n')
	}
	return CodeSnippet{
		Source:    sb.String(),
		FilePath:  node.FilePath,
		LineStart: start,
		LineEnd:   end,
		Language:  InferLanguage(node.FilePath),
	}, true
}

// InferLanguage maps a file extension to a language identifier. Mirrors
// SnippetStore.inferLanguage on the Java side; returns "unknown" for unknown
// or missing extensions.
func InferLanguage(filePath string) string {
	dot := strings.LastIndex(filePath, ".")
	if dot < 0 {
		return "unknown"
	}
	switch strings.ToLower(filePath[dot+1:]) {
	case "java":
		return "java"
	case "ts", "tsx":
		return "typescript"
	case "js", "jsx":
		return "javascript"
	case "py":
		return "python"
	case "go":
		return "go"
	case "rs":
		return "rust"
	case "cs":
		return "csharp"
	case "cpp", "cc", "cxx", "h", "hpp":
		return "cpp"
	case "kt":
		return "kotlin"
	case "scala", "sc":
		return "scala"
	}
	return "unknown"
}
