package cli

import (
	"strings"
	"testing"

	"github.com/spf13/pflag"
)

// TestEverySubcommandIsDocumented asserts the §7.1 contract: every Cobra
// subcommand has Use, Short, Long, Example, and RunE populated; every flag
// has Usage text. A subcommand or flag that lacks docs fails the build.
func TestEverySubcommandIsDocumented(t *testing.T) {
	root := NewRootCommand()
	for _, cmd := range root.Commands() {
		// Skip Cobra auto-generated children (help / completion).
		if cmd.Hidden || cmd.Name() == "help" || cmd.Name() == "completion" {
			continue
		}
		name := cmd.Name()
		if cmd.Use == "" {
			t.Errorf("%s: Use is empty", name)
		}
		if cmd.Short == "" {
			t.Errorf("%s: Short is empty", name)
		}
		if cmd.Long == "" {
			t.Errorf("%s: Long is empty", name)
		}
		if cmd.Example == "" {
			t.Errorf("%s: Example is empty", name)
		} else if lines := strings.Split(cmd.Example, "\n"); len(lines) < 3 {
			t.Errorf("%s: Example must have >= 3 lines, got %d", name, len(lines))
		}
		if cmd.RunE == nil {
			t.Errorf("%s: must use RunE (returns error), not Run", name)
		}
		cmd.Flags().VisitAll(func(f *pflag.Flag) {
			if f.Usage == "" {
				t.Errorf("%s --%s: Usage is empty", name, f.Name)
			}
		})
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
