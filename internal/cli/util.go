package cli

import (
	"encoding/json"
	"errors"
	"io"

	"github.com/randomcodespace/codeiq/internal/projectroot"
	"github.com/randomcodespace/codeiq/internal/query"
)

// resolvePath turns the optional [path] positional that most subcommands
// accept into an absolute, directory-validated project root.
//
// Resolution order (highest wins): explicit positional argument, then the
// CODEIQ_PROJECT_ROOT environment variable, then walking up from the current
// working directory looking for `.codeiq/graph/codeiq.kuzu` (already-indexed),
// then `.git/` (repo root). The walk-up makes `codeiq <cmd>` "just work" when
// invoked from inside an indexed project — most relevant for MCP-client
// configs that previously needed a hardcoded path arg.
//
// Returns a usageError on any resolution failure (no path arg, no env, and
// the walk-up found nothing) — exit code 1 per root.go's exit-code mapping.
func resolvePath(args []string) (string, error) {
	root, err := projectroot.FromArgs(args)
	if err != nil {
		if errors.Is(err, projectroot.ErrNotFound) {
			return "", newUsageError(
				"could not resolve project root.\n" +
					"  Try one of:\n" +
					"    codeiq <cmd> /path/to/project\n" +
					"    CODEIQ_PROJECT_ROOT=/path/to/project codeiq <cmd>\n" +
					"    cd /path/to/project && codeiq <cmd>   # walk-up finds .codeiq/ or .git/")
		}
		return "", newUsageError("%s", err.Error())
	}
	return root, nil
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
