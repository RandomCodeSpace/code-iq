// Package base provides shared helpers for detector implementations.
// Mirrors the Java Abstract* detector hierarchy collapsed for tree-sitter.
package base

import (
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// RegexDetectorDefaultConfidence is the floor for regex-only detectors.
// Java equivalent: AbstractRegexDetector.defaultConfidence() = LEXICAL.
const RegexDetectorDefaultConfidence = model.ConfidenceLexical

// FindLineNumber returns the 1-based line number for a character offset in
// text. Offsets past the end clamp to the last line; empty input returns 1.
// Mirrors Java's findLineNumber helper used throughout the regex detectors.
func FindLineNumber(text string, offset int) int {
	if offset < 0 {
		offset = 0
	}
	if offset > len(text) {
		offset = len(text)
	}
	line := 1
	for i := 0; i < offset; i++ {
		if text[i] == '\n' {
			line++
		}
	}
	return line
}

// FileName extracts just the filename component of a path (after the last
// '/' or '\\'). Mirrors Java AbstractRegexDetector.fileName().
func FileName(path string) string {
	if path == "" {
		return ""
	}
	lastSlash := -1
	for i := len(path) - 1; i >= 0; i-- {
		if path[i] == '/' || path[i] == '\\' {
			lastSlash = i
			break
		}
	}
	if lastSlash >= 0 {
		return path[lastSlash+1:]
	}
	return path
}
