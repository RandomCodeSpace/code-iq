package cli

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/randomcodespace/codeiq/go/internal/query"
)

// resolvePath turns the optional [path] positional that most subcommands
// accept into an absolute, directory-validated path. An empty args slice is
// the current working directory. A non-empty args slice uses args[0].
//
// Returns a usageError when the resolved path does not exist or is not a
// directory — that path-type problem is a user-input issue (exit code 1) per
// root.go's exit-code mapping.
func resolvePath(args []string) (string, error) {
	path := "."
	if len(args) >= 1 && args[0] != "" {
		path = args[0]
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", fmt.Errorf("resolve %q: %w", path, err)
	}
	st, err := os.Stat(abs)
	if err != nil {
		return "", newUsageError("path %q does not exist", abs)
	}
	if !st.IsDir() {
		return "", newUsageError("path %q is not a directory", abs)
	}
	return abs, nil
}

// printOrdered writes a query.OrderedMap (or any other deterministic
// structure) as indented JSON. We use JSON for the default human view too —
// it's already deterministic, easily diffable in tests, and matches the
// JSON-by-default convention the Java CLI moved to in PR-5. Callers who want
// a more aggressive text rendering can opt-out by re-implementing this in
// the specific command.
func printOrdered(w io.Writer, v any) error {
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	if om, ok := v.(*query.OrderedMap); ok && om != nil {
		return enc.Encode(om)
	}
	return enc.Encode(v)
}
