package cli

import (
	"encoding/json"
	"fmt"
	"io"

	"github.com/randomcodespace/codeiq/internal/buildinfo"
	"github.com/spf13/cobra"
)

// versionPayload is the JSON shape spec'd in §7.1.
type versionPayload struct {
	Version     string   `json:"version"`
	Commit      string   `json:"commit"`
	CommitDirty bool     `json:"commit_dirty"`
	Built       string   `json:"built"`
	GoVersion   string   `json:"go_version"`
	Platform    string   `json:"platform"`
	Features    []string `json:"features"`
}

func versionInfo() versionPayload {
	return versionPayload{
		Version:     buildinfo.Version,
		Commit:      buildinfo.Commit,
		CommitDirty: buildinfo.DirtyBool(),
		Built:       buildinfo.Date,
		GoVersion:   buildinfo.GoVersion(),
		Platform:    buildinfo.Platform(),
		Features:    buildinfo.Features(),
	}
}

func printVersion(w io.Writer, asJSON bool) error {
	info := versionInfo()
	if asJSON {
		b, err := json.MarshalIndent(info, "", "  ")
		if err != nil {
			return err
		}
		_, err = fmt.Fprintln(w, string(b))
		return err
	}
	dirtyTag := "(clean)"
	if info.CommitDirty {
		dirtyTag = "(dirty)"
	}
	fmt.Fprintf(w, "codeiq %s\n", info.Version)
	fmt.Fprintf(w, "  commit:    %s %s\n", info.Commit, dirtyTag)
	fmt.Fprintf(w, "  built:     %s\n", info.Built)
	fmt.Fprintf(w, "  go:        %s\n", info.GoVersion)
	fmt.Fprintf(w, "  platform:  %s\n", info.Platform)
	fmt.Fprintf(w, "  features:  %s\n", joinFeatures(info.Features))
	return nil
}

func joinFeatures(f []string) string {
	out := ""
	for i, s := range f {
		if i > 0 {
			out += ", "
		}
		out += s
	}
	return out
}

func init() {
	registerSubcommand(func() *cobra.Command {
		cmd := &cobra.Command{
			Use:   "version",
			Short: "Show version, commit, build date, and platform.",
			Long: `Print the codeiq version, git commit hash, build date, Go
toolchain version, platform, and compiled-in feature flags. Use --json to
emit the same data as a single JSON object suitable for scripting.`,
			Example: `  codeiq version
  codeiq version --json
  codeiq --version           # alias of "codeiq version"`,
			RunE: func(cmd *cobra.Command, args []string) error {
				return printVersion(cmd.OutOrStdout(), flagJSON)
			},
		}
		return cmd
	})
}
