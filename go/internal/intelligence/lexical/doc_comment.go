// Package lexical extracts doc comments and bounded source snippets from
// already-discovered files, populating CodeNode properties used by the
// lexical intelligence layer.
package lexical

import (
	"regexp"
	"strings"
)

// Extract returns the doc comment for the symbol declared at lineStart
// (1-based) in the given source lines. The language string selects extraction
// style: "python" -> triple-quoted docstring, "go"/"rust" -> contiguous //,
// anything else -> block /** ... */ immediately preceding the declaration.
//
// Mirrors src/main/java/.../intelligence/lexical/DocCommentExtractor.java.
func Extract(lines []string, language string, lineStart int) string {
	if len(lines) == 0 || lineStart <= 0 || lineStart > len(lines) {
		return ""
	}
	switch language {
	case "python":
		return extractPythonDocstring(lines, lineStart)
	case "go", "rust":
		return extractLineComments(lines, lineStart)
	default:
		return extractBlockComment(lines, lineStart)
	}
}

var (
	reOpenBlock   = regexp.MustCompile(`^/\*+\s*`)
	reCloseBlock  = regexp.MustCompile(`\s*\*/$`)
	reInnerBlock  = regexp.MustCompile(`^\*\s?`)
	reLineComment = regexp.MustCompile(`^//[!/]?\s*`)
)

// extractBlockComment walks back from the declaration line, skipping blanks
// and annotation lines (lines starting with `@`), then collects a contiguous
// /** ... */ block immediately preceding the declaration.
func extractBlockComment(lines []string, lineStart int) string {
	scan := lineStart - 2 // 0-based index of line preceding the declaration
	for scan >= 0 {
		t := strings.TrimSpace(lines[scan])
		if t == "" || strings.HasPrefix(t, "@") {
			scan--
			continue
		}
		break
	}
	if scan < 0 {
		return ""
	}
	end := strings.TrimSpace(lines[scan])
	if !strings.HasSuffix(end, "*/") {
		return ""
	}
	open := scan
	for open >= 0 && !strings.HasPrefix(strings.TrimSpace(lines[open]), "/*") {
		open--
	}
	if open < 0 {
		return ""
	}
	var sb strings.Builder
	for i := open; i <= scan; i++ {
		t := strings.TrimSpace(lines[i])
		t = reOpenBlock.ReplaceAllString(t, "")
		t = reCloseBlock.ReplaceAllString(t, "")
		t = reInnerBlock.ReplaceAllString(t, "")
		t = strings.TrimSpace(t)
		if t == "" {
			continue
		}
		if sb.Len() > 0 {
			sb.WriteByte(' ')
		}
		sb.WriteString(t)
	}
	return sb.String()
}

// extractLineComments collects contiguous `//` line comments immediately
// before the declaration, after skipping blank lines.
func extractLineComments(lines []string, lineStart int) string {
	scan := lineStart - 2
	for scan >= 0 && strings.TrimSpace(lines[scan]) == "" {
		scan--
	}
	if scan < 0 {
		return ""
	}
	end := scan
	for scan >= 0 && strings.HasPrefix(strings.TrimSpace(lines[scan]), "//") {
		scan--
	}
	start := scan + 1
	if start > end {
		return ""
	}
	var sb strings.Builder
	for i := start; i <= end; i++ {
		t := strings.TrimSpace(lines[i])
		t = reLineComment.ReplaceAllString(t, "")
		if t == "" {
			continue
		}
		if sb.Len() > 0 {
			sb.WriteByte(' ')
		}
		sb.WriteString(t)
	}
	return sb.String()
}

// extractPythonDocstring reads the first triple-quoted string literal inside
// the function/class body. lineStart is the 1-based def/class line; the body
// starts at lineStart (0-based) — i.e. the line immediately after.
func extractPythonDocstring(lines []string, lineStart int) string {
	var sb strings.Builder
	openQuote := ""
	maxIdx := lineStart + 15
	if maxIdx > len(lines) {
		maxIdx = len(lines)
	}
	for i := lineStart; i < maxIdx; i++ {
		line := strings.TrimSpace(lines[i])
		if openQuote == "" {
			idxD := strings.Index(line, `"""`)
			idxS := strings.Index(line, `'''`)
			var idx int
			var quote string
			switch {
			case idxD >= 0 && (idxS < 0 || idxD <= idxS):
				idx = idxD
				quote = `"""`
			case idxS >= 0:
				idx = idxS
				quote = `'''`
			default:
				return ""
			}
			after := line[idx+3:]
			if closeIdx := strings.Index(after, quote); closeIdx >= 0 {
				return strings.TrimSpace(after[:closeIdx])
			}
			openQuote = quote
			if rest := strings.TrimSpace(after); rest != "" {
				sb.WriteString(rest)
			}
			continue
		}
		if closeIdx := strings.Index(line, openQuote); closeIdx >= 0 {
			before := strings.TrimSpace(line[:closeIdx])
			if before != "" {
				if sb.Len() > 0 {
					sb.WriteByte(' ')
				}
				sb.WriteString(before)
			}
			return strings.TrimSpace(sb.String())
		}
		if line != "" {
			if sb.Len() > 0 {
				sb.WriteByte(' ')
			}
			sb.WriteString(line)
		}
	}
	return ""
}
