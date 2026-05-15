// Package projectroot is the layered project-root resolver used by every
// CLI subcommand and the MCP server.
//
// Resolution order (highest wins):
//
//  1. Explicit positional argument (the legacy behavior; `codeiq <cmd> <path>`).
//  2. `CODEIQ_PROJECT_ROOT` environment variable. Useful for wrappers and CI.
//  3. Walk up from the current working directory looking for `.codeiq/`
//     (already-indexed project; strongest signal that this is the root).
//  4. Walk up from the current working directory looking for `.git/` (repo root).
//  5. Error with an actionable message.
//
// The MCP server adds a sixth signal at the top of the chain — the MCP
// client's `ListRoots` response — wired separately in `internal/mcp` because
// it requires an active session.
package projectroot

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// EnvVar is the environment variable consulted by Resolve.
const EnvVar = "CODEIQ_PROJECT_ROOT"

// Markers walked up the directory tree.
const (
	graphMarker = ".codeiq/graph/codeiq.kuzu" // strongest signal
	gitMarker   = ".git"                      // fallback
)

// ErrNotFound is returned when no resolution succeeds.
var ErrNotFound = errors.New("project root could not be resolved from arg, " + EnvVar + ", or filesystem walk-up")

// Options bundles the resolution inputs. Pass empty strings to skip a layer.
//   - Arg: the positional argument (or "" if the user didn't supply one).
//   - EnvValue: the value of CODEIQ_PROJECT_ROOT (or "" if unset).
//   - CWD: the current working directory (typically os.Getwd()).
type Options struct {
	Arg      string
	EnvValue string
	CWD      string
}

// Resolve runs the layered resolution chain. Returns an absolute, validated
// directory path on success.
//
// Any non-empty Arg or EnvValue that points at a non-directory is an error
// (we don't silently fall through user-supplied paths — it's almost always
// a typo we want surfaced).
func Resolve(opts Options) (string, error) {
	if opts.Arg != "" {
		return validateDir(opts.Arg, "argument")
	}
	if opts.EnvValue != "" {
		return validateDir(opts.EnvValue, EnvVar)
	}
	if opts.CWD == "" {
		return "", ErrNotFound
	}
	if root, ok := WalkUp(opts.CWD); ok {
		return root, nil
	}
	return "", ErrNotFound
}

// WalkUp walks up from start looking for `.codeiq/graph/codeiq.kuzu` first
// (already-indexed), then `.git` (repo root). Returns the matching ancestor
// directory and true, or ("", false).
//
// start must be an absolute path; if not, it's resolved against the current
// working directory at call time.
func WalkUp(start string) (string, bool) {
	abs, err := filepath.Abs(start)
	if err != nil {
		return "", false
	}
	// First pass: prefer .codeiq/ because it tells us the project has been
	// indexed (the user almost certainly meant THIS root). Second pass: fall
	// back to .git/ because nearly every codebase has one.
	for _, marker := range []string{graphMarker, gitMarker} {
		if hit, ok := walkUpFor(abs, marker); ok {
			return hit, true
		}
	}
	return "", false
}

// walkUpFor walks dir → dir/.. → dir/../.. looking for marker. Stops at
// filesystem root.
func walkUpFor(dir, marker string) (string, bool) {
	for {
		candidate := filepath.Join(dir, marker)
		if _, err := os.Stat(candidate); err == nil {
			return dir, true
		}
		parent := filepath.Dir(dir)
		if parent == dir { // hit filesystem root
			return "", false
		}
		dir = parent
	}
}

// FromArgs is the call-site sugar used by every CLI subcommand. It bundles
// args (the cobra positional slice), the env, and the cwd into Options and
// runs Resolve. Cobra's `MaximumNArgs(1)` plus this helper means subcommands
// stay tiny.
func FromArgs(args []string) (string, error) {
	cwd, _ := os.Getwd() // best-effort; if it fails Resolve falls through to ErrNotFound
	arg := ""
	if len(args) > 0 {
		arg = args[0]
	}
	return Resolve(Options{
		Arg:      arg,
		EnvValue: os.Getenv(EnvVar),
		CWD:      cwd,
	})
}

// validateDir absolute-izes p and confirms it's an existing directory.
// label is for the error message ("argument" / "CODEIQ_PROJECT_ROOT").
func validateDir(p, label string) (string, error) {
	abs, err := filepath.Abs(p)
	if err != nil {
		return "", fmt.Errorf("resolve %s %q: %w", label, p, err)
	}
	st, err := os.Stat(abs)
	if err != nil {
		return "", fmt.Errorf("%s %q does not exist", label, abs)
	}
	if !st.IsDir() {
		return "", fmt.Errorf("%s %q is not a directory", label, abs)
	}
	return abs, nil
}
