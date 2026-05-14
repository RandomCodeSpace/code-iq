package mcp

import (
	"bufio"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// ReadFileRequest is the typed input for the read_file tool / library
// surface. All fields are required except StartLine and EndLine; the
// caller is expected to default MaxBytes to McpLimitsConfig.maxPayloadBytes
// (2 MB by default) when forwarding from MCP.
type ReadFileRequest struct {
	Root      string // Repo root (must be absolute or resolvable). Required.
	Path      string // Caller-supplied relative path under Root.
	StartLine int    // 1-based inclusive; 0 = read from line 1.
	EndLine   int    // 1-based inclusive; 0 = read to EOF.
	MaxBytes  int64  // Byte cap; truncate beyond this.
}

// ReadFileResponse is what the tool returns when the read succeeds.
type ReadFileResponse struct {
	Path      string `json:"path"`
	Content   string `json:"content"`
	Truncated bool   `json:"truncated,omitempty"`
	StartLine int    `json:"start_line,omitempty"`
	EndLine   int    `json:"end_line,omitempty"`
	MimeType  string `json:"mime_type,omitempty"`
}

// allowedMimePrefixes mirrors Java GraphController.readFile — the
// explicit list of content-type prefixes that may be served. Adding new
// types is a deliberate choice (think YAML over multiple official MIME
// strings); never broaden to application/* without weighing the surface
// area. http.DetectContentType returns the type with a `;charset=...`
// suffix in some cases — `isAllowedMime` strips that before comparing.
var allowedMimePrefixes = []string{
	"text/",
	"application/json",
	"application/xml",
	"application/x-yaml",
	"application/javascript",
}

// ReadRepoFile resolves req.Path under req.Root with symlink + traversal
// protection, validates the file is a permitted text MIME type, and
// returns at most MaxBytes of content (optionally line-sliced).
//
// Mirrors Java SafeFileReader + GraphController.readFile exactly:
//
//   - Lexical traversal check before any FS access (rejects `..` segments).
//   - filepath.EvalSymlinks on both root and candidate; second-stage
//     containment check after symlink resolution catches a symlink
//     inside the repo that points to /etc/passwd.
//   - http.DetectContentType (RFC-2046-ish sniffing) against an allowlist.
//   - Byte cap enforced in the read loop; if MaxBytes <= 0 the cap
//     defaults to 1 MiB to keep the function safe to call without a
//     ConfigDefaults wired up.
func ReadRepoFile(req ReadFileRequest) (*ReadFileResponse, error) {
	if req.MaxBytes <= 0 {
		req.MaxBytes = 1 << 20
	}
	if req.Root == "" {
		return nil, fmt.Errorf("read_file: root is required")
	}
	if req.Path == "" {
		return nil, fmt.Errorf("read_file: path is required")
	}
	if filepath.IsAbs(req.Path) {
		return nil, fmt.Errorf("read_file: path must be relative to root, got absolute %q", req.Path)
	}

	rootAbs, err := filepath.Abs(req.Root)
	if err != nil {
		return nil, fmt.Errorf("read_file: resolve root: %w", err)
	}
	rootCanonical, err := filepath.EvalSymlinks(rootAbs)
	if err != nil {
		return nil, fmt.Errorf("read_file: canonicalize root: %w", err)
	}

	// Clean drops `..` segments but does NOT prevent escape — we still
	// need the prefix containment check below. We reject paths that
	// would resolve outside the root pre-FS-access via the same lexical
	// check too, so a malicious `../../etc/passwd` is rejected even
	// when the symlink targets exist.
	candidate := filepath.Clean(filepath.Join(rootCanonical, req.Path))
	if !pathContains(rootCanonical, candidate) {
		return nil, fmt.Errorf("read_file: path traversal detected (lexical)")
	}

	resolved, err := filepath.EvalSymlinks(candidate)
	if err != nil {
		return nil, fmt.Errorf("read_file: resolve path: %w", err)
	}
	if !pathContains(rootCanonical, resolved) {
		return nil, fmt.Errorf("read_file: path traversal detected (symlink target)")
	}

	info, err := os.Stat(resolved)
	if err != nil {
		return nil, fmt.Errorf("read_file: stat: %w", err)
	}
	if info.IsDir() {
		return nil, fmt.Errorf("read_file: path is a directory: %s", req.Path)
	}

	f, err := os.Open(resolved)
	if err != nil {
		return nil, fmt.Errorf("read_file: open: %w", err)
	}
	defer f.Close()

	// Sniff the MIME type from the first 512 bytes (RFC-2046-ish heuristic
	// in net/http). Reject early so a 100 MB binary doesn't even get to
	// the read loop.
	sniff := make([]byte, 512)
	n, _ := io.ReadFull(f, sniff)
	mime := http.DetectContentType(sniff[:n])
	if !isAllowedMime(mime) {
		return nil, fmt.Errorf("read_file: rejected content type %q", mime)
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return nil, fmt.Errorf("read_file: rewind: %w", err)
	}

	if req.StartLine > 0 || req.EndLine > 0 {
		return readLineRange(f, req, mime)
	}
	return readWholeFile(f, req, mime)
}

func readWholeFile(f *os.File, req ReadFileRequest, mime string) (*ReadFileResponse, error) {
	buf := make([]byte, 0, req.MaxBytes)
	tmp := make([]byte, 4096)
	truncated := false
	for {
		got, rerr := f.Read(tmp)
		if got > 0 {
			remaining := req.MaxBytes - int64(len(buf))
			if int64(got) > remaining {
				buf = append(buf, tmp[:remaining]...)
				truncated = true
				break
			}
			buf = append(buf, tmp[:got]...)
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return nil, fmt.Errorf("read_file: read: %w", rerr)
		}
	}
	return &ReadFileResponse{
		Path:      req.Path,
		Content:   string(buf),
		Truncated: truncated,
		MimeType:  trimMimeSuffix(mime),
	}, nil
}

func readLineRange(f *os.File, req ReadFileRequest, mime string) (*ReadFileResponse, error) {
	scanner := bufio.NewScanner(f)
	// Buffer up to 10 MB lines; matches the Java reader's MaxByteSize floor.
	scanner.Buffer(make([]byte, 0, 64*1024), 10*1024*1024)
	var sb strings.Builder
	truncated := false
	ln := 0
	lastLine := 0
	for scanner.Scan() {
		ln++
		if req.StartLine > 0 && ln < req.StartLine {
			continue
		}
		if req.EndLine > 0 && ln > req.EndLine {
			break
		}
		line := scanner.Text()
		if int64(sb.Len()+len(line)+1) > req.MaxBytes {
			truncated = true
			break
		}
		sb.WriteString(line)
		sb.WriteByte('\n')
		lastLine = ln
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("read_file: scan: %w", err)
	}
	return &ReadFileResponse{
		Path:      req.Path,
		Content:   sb.String(),
		Truncated: truncated,
		StartLine: req.StartLine,
		EndLine:   func() int {
			if req.EndLine > 0 {
				return req.EndLine
			}
			return lastLine
		}(),
		MimeType: trimMimeSuffix(mime),
	}, nil
}

// pathContains reports whether child is at or under parent. Both
// arguments must already be filepath.Clean'd absolute paths.
func pathContains(parent, child string) bool {
	if parent == child {
		return true
	}
	prefix := parent + string(filepath.Separator)
	return strings.HasPrefix(child, prefix)
}

// isAllowedMime reports whether mime matches any allowed prefix. The
// `;charset=...` suffix that http.DetectContentType adds for text types
// is stripped before comparison.
func isAllowedMime(mime string) bool {
	mime = trimMimeSuffix(mime)
	for _, p := range allowedMimePrefixes {
		if strings.HasPrefix(mime, p) {
			return true
		}
	}
	return false
}

func trimMimeSuffix(mime string) string {
	if i := strings.Index(mime, ";"); i >= 0 {
		return strings.TrimSpace(mime[:i])
	}
	return mime
}
