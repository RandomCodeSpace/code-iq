package cli

import (
	"strings"
	"testing"

	"github.com/spf13/cobra"
	"github.com/spf13/pflag"
)

// TestEverySubcommandIsDocumented asserts the §7.1 contract: every Cobra
// subcommand (including nested subcommands like `query consumers`) has Use,
// Short, Long, Example, and RunE populated; every flag has Usage text. A
// subcommand or flag that lacks docs fails the build.
func TestEverySubcommandIsDocumented(t *testing.T) {
	root := NewRootCommand()
	var walk func(parent string, cmd *cobra.Command)
	walk = func(parent string, cmd *cobra.Command) {
		// Skip Cobra auto-generated children (help / completion).
		if cmd.Hidden || cmd.Name() == "help" || cmd.Name() == "completion" {
			return
		}
		full := cmd.Name()
		if parent != "" {
			full = parent + " " + full
		}
		if cmd.Use == "" {
			t.Errorf("%s: Use is empty", full)
		}
		if cmd.Short == "" {
			t.Errorf("%s: Short is empty", full)
		}
		if cmd.Long == "" {
			t.Errorf("%s: Long is empty", full)
		}
		if cmd.Example == "" {
			t.Errorf("%s: Example is empty", full)
		} else if lines := strings.Split(cmd.Example, "\n"); len(lines) < 3 {
			t.Errorf("%s: Example must have >= 3 lines, got %d", full, len(lines))
		}
		if cmd.RunE == nil {
			t.Errorf("%s: must use RunE (returns error), not Run", full)
		}
		cmd.Flags().VisitAll(func(f *pflag.Flag) {
			if f.Usage == "" {
				t.Errorf("%s --%s: Usage is empty", full, f.Name)
			}
		})
		for _, child := range cmd.Commands() {
			walk(full, child)
		}
	}
	for _, cmd := range root.Commands() {
		walk("", cmd)
	}
}

// TestRootCommandPersistentFlagsDocumented ensures the global flags themselves
// are documented — they're inherited by every subcommand so a missing Usage
// there pollutes every help screen.
func TestRootCommandPersistentFlagsDocumented(t *testing.T) {
	root := NewRootCommand()
	root.PersistentFlags().VisitAll(func(f *pflag.Flag) {
		if f.Usage == "" {
			t.Errorf("persistent flag --%s: Usage is empty", f.Name)
		}
	})
}
