// Package review implements the MR-review pipeline: git diff → graph
// evidence → LLM review. Phase 3 of the optimization plan.
//
// Two entry points:
//   - CLI: `codeiq review --base <ref> --head <ref>` (cli/review.go)
//   - MCP: `review_changes` tool (mcp/tools_review.go)
//
// Both call ReviewService.Review which:
//   1. Shells out to `git diff` for the file list + hunks.
//   2. For each changed file, queries the graph for nodes-in-file,
//      blast radius, and evidence packs.
//   3. Renders a single prompt and calls the configured LLM (default:
//      Ollama Cloud gpt-oss).
//   4. Returns a structured review report (markdown for CLI, JSON for MCP).
package review

import (
	"bufio"
	"fmt"
	"os/exec"
	"strings"
)

// ChangedFile is one entry in the diff.
type ChangedFile struct {
	Path         string
	Hunks        []string
	AddedLines   int
	RemovedLines int
}

// ParseDiff parses raw unified `git diff` output into a per-file slice.
// File renames are reported under the new path. Binary diffs are recorded
// with empty hunks and zero line counts.
func ParseDiff(raw string) ([]ChangedFile, error) {
	if raw == "" {
		return nil, nil
	}
	var files []ChangedFile
	var cur *ChangedFile
	var hunkBuf strings.Builder
	flushHunk := func() {
		if cur != nil && hunkBuf.Len() > 0 {
			cur.Hunks = append(cur.Hunks, hunkBuf.String())
			hunkBuf.Reset()
		}
	}
	scanner := bufio.NewScanner(strings.NewReader(raw))
	scanner.Buffer(make([]byte, 1024*1024), 16*1024*1024) // 16MB max line for big patches
	for scanner.Scan() {
		line := scanner.Text()
		switch {
		case strings.HasPrefix(line, "diff --git "):
			flushHunk()
			if cur != nil {
				files = append(files, *cur)
			}
			// `diff --git a/path b/path` — pick the b/ path (new name on
			// rename, same path otherwise).
			parts := strings.SplitN(line, " ", 4)
			path := ""
			if len(parts) == 4 {
				bSide := strings.TrimPrefix(parts[3], "b/")
				path = bSide
			}
			cur = &ChangedFile{Path: path}
		case strings.HasPrefix(line, "+++ b/"):
			// File rename or initial add: prefer the +++ b/ side over the
			// `diff --git` header parse.
			if cur != nil {
				cur.Path = strings.TrimPrefix(line, "+++ b/")
			}
		case strings.HasPrefix(line, "@@"):
			flushHunk()
			hunkBuf.WriteString(line)
			hunkBuf.WriteString("\n")
		case strings.HasPrefix(line, "+") && !strings.HasPrefix(line, "+++"):
			if cur != nil {
				cur.AddedLines++
			}
			hunkBuf.WriteString(line)
			hunkBuf.WriteString("\n")
		case strings.HasPrefix(line, "-") && !strings.HasPrefix(line, "---"):
			if cur != nil {
				cur.RemovedLines++
			}
			hunkBuf.WriteString(line)
			hunkBuf.WriteString("\n")
		default:
			if hunkBuf.Len() > 0 {
				hunkBuf.WriteString(line)
				hunkBuf.WriteString("\n")
			}
		}
	}
	flushHunk()
	if cur != nil {
		files = append(files, *cur)
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("scan diff: %w", err)
	}
	return files, nil
}

// GitDiff shells out to `git diff --unified=3 <base>..<head>` from cwd
// and returns the parsed result. Mirrors the Java DiffParser shellout.
func GitDiff(cwd, base, head string) ([]ChangedFile, error) {
	args := []string{"diff", "--unified=3"}
	if base != "" && head != "" {
		args = append(args, base+".."+head)
	} else if base != "" {
		args = append(args, base)
	}
	cmd := exec.Command("git", args...)
	cmd.Dir = cwd
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("git diff %v: %w", args, err)
	}
	return ParseDiff(string(out))
}
