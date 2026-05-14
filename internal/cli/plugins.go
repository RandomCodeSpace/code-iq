package cli

import (
	"fmt"
	"io"
	"reflect"
	"sort"
	"strings"
	"text/tabwriter"

	"github.com/randomcodespace/codeiq/internal/detector"

	// Blank imports register every phase-1/2 detector with detector.Default.
	// Same set the `index` command pulls in — keep in sync.
	_ "github.com/randomcodespace/codeiq/internal/detector/generic"
	_ "github.com/randomcodespace/codeiq/internal/detector/jvm/java"
	_ "github.com/randomcodespace/codeiq/internal/detector/python"

	"github.com/spf13/cobra"
)

func init() {
	registerSubcommand(newPluginsCommand)
}

// newPluginsCommand assembles `codeiq plugins` — list / inspect registered
// detectors.
//
// Detectors are registered at compile time via the detector.RegisterDefault
// init() pattern (Go's compile-time registry — no classpath scan, no
// reflection at runtime). The list reflects whatever was linked into the
// binary; build tags / blank imports change the set.
func newPluginsCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "plugins <action>",
		Short: "List and inspect available detectors.",
		Long: `Inspect the static detector registry. Detectors are
auto-registered by the Go compile-time detector.Default registry — no
classpath scan, no runtime reflection. Use ` + "`plugins list`" + ` for an
overview and ` + "`plugins inspect <name>`" + ` for per-detector metadata.

Detectors are stateless ` + "`Detector`" + ` implementations registered via
` + "`detector.RegisterDefault`" + ` from their package's ` + "`init()`" + `. The list
in this binary reflects whatever was linked in — build tags / blank
imports change the set.`,
		Example: `  codeiq plugins list
  codeiq plugins list --language python
  codeiq plugins inspect spring_rest`,
		RunE: func(c *cobra.Command, _ []string) error { return c.Help() },
	}
	cmd.AddCommand(newPluginsListCommand())
	cmd.AddCommand(newPluginsInspectCommand())
	return cmd
}

func newPluginsListCommand() *cobra.Command {
	var (
		lang   string
		asJSON bool
	)
	cmd := &cobra.Command{
		Use:   "list",
		Short: "List every registered detector.",
		Long: `Print one row per registered detector with columns:
NAME, CATEGORY (derived from the detector's package path), and LANGUAGES.

Filter with ` + "`--language`" + ` to restrict to detectors that handle a given
language. Pass ` + "`--json`" + ` for a machine-parseable array.`,
		Example: `  codeiq plugins list
  codeiq plugins list --language python
  codeiq plugins list --json | jq '.[] | .name'`,
		RunE: func(cmd *cobra.Command, args []string) error {
			dets := detector.Default.All()
			if lang != "" {
				dets = filterByLanguage(dets, lang)
			}
			rows := buildPluginRows(dets)
			if asJSON {
				return jsonOut(cmd.OutOrStdout(), rows)
			}
			return printPluginRows(cmd.OutOrStdout(), rows)
		},
	}
	cmd.Flags().StringVar(&lang, "language", "",
		"Filter by supported language (e.g. java, python, typescript).")
	cmd.Flags().BoolVar(&asJSON, "json", false,
		"Emit detectors as a JSON array instead of a table.")
	return cmd
}

func newPluginsInspectCommand() *cobra.Command {
	var asJSON bool
	cmd := &cobra.Command{
		Use:   "inspect <name>",
		Short: "Print metadata for one detector.",
		Long: `Print all registered metadata for the named detector:
category (derived from package path), supported languages, default
confidence level, and the underlying Go type. Use ` + "`plugins list`" + ` to
discover detector names.`,
		Example: `  codeiq plugins inspect spring_rest
  codeiq plugins inspect jpa_entity
  codeiq plugins inspect django_model --json`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := args[0]
			d := detector.Default.ByName(name)
			if d == nil {
				return fmt.Errorf("unknown detector %q (try `codeiq plugins list`)", name)
			}
			info := describeDetector(d)
			if asJSON {
				return jsonOut(cmd.OutOrStdout(), info)
			}
			return printPluginInspect(cmd.OutOrStdout(), info)
		},
	}
	cmd.Flags().BoolVar(&asJSON, "json", false,
		"Emit detector metadata as a JSON object instead of a key:value list.")
	return cmd
}

// pluginRow is one row in `plugins list` output.
type pluginRow struct {
	Name              string   `json:"name"`
	Category          string   `json:"category"`
	Languages         []string `json:"languages"`
	DefaultConfidence string   `json:"default_confidence"`
	GoType            string   `json:"go_type,omitempty"`
}

// buildPluginRows converts a slice of Detectors into row structs sorted by name.
func buildPluginRows(dets []detector.Detector) []pluginRow {
	rows := make([]pluginRow, 0, len(dets))
	for _, d := range dets {
		rows = append(rows, describeDetector(d))
	}
	sort.Slice(rows, func(i, j int) bool { return rows[i].Name < rows[j].Name })
	return rows
}

// describeDetector packages the Detector metadata into a pluginRow.
// Category is derived from the Go package path of the underlying type —
// e.g. `.../detector/jvm/java` -> `jvm/java`. This avoids the need for a
// `Category()` method on every detector while still giving operators a
// useful grouping.
func describeDetector(d detector.Detector) pluginRow {
	t := reflect.TypeOf(d)
	if t.Kind() == reflect.Ptr {
		t = t.Elem()
	}
	pkgPath := t.PkgPath()
	return pluginRow{
		Name:              d.Name(),
		Category:          categoryFromPkgPath(pkgPath),
		Languages:         sortedCopy(d.SupportedLanguages()),
		DefaultConfidence: d.DefaultConfidence().String(),
		GoType:            pkgPath + "." + t.Name(),
	}
}

// categoryFromPkgPath turns a Go package path like
// `github.com/randomcodespace/codeiq/internal/detector/jvm/java` into
// `jvm/java`. Returns "unknown" if `detector/` is not in the path.
func categoryFromPkgPath(pkgPath string) string {
	const marker = "/detector/"
	idx := strings.Index(pkgPath, marker)
	if idx < 0 {
		return "unknown"
	}
	return pkgPath[idx+len(marker):]
}

// filterByLanguage keeps only detectors that declare lang as a supported
// language.
func filterByLanguage(dets []detector.Detector, lang string) []detector.Detector {
	out := make([]detector.Detector, 0, len(dets))
	for _, d := range dets {
		for _, l := range d.SupportedLanguages() {
			if l == lang {
				out = append(out, d)
				break
			}
		}
	}
	return out
}

// printPluginRows renders rows as an aligned table.
func printPluginRows(w io.Writer, rows []pluginRow) error {
	tw := tabwriter.NewWriter(w, 0, 0, 2, ' ', 0)
	fmt.Fprintln(tw, "NAME\tCATEGORY\tLANGUAGES\tCONFIDENCE")
	for _, r := range rows {
		fmt.Fprintf(tw, "%s\t%s\t[%s]\t%s\n",
			r.Name, r.Category, strings.Join(r.Languages, ","), r.DefaultConfidence)
	}
	return tw.Flush()
}

// printPluginInspect renders a single row as a key/value block.
func printPluginInspect(w io.Writer, row pluginRow) error {
	fmt.Fprintf(w, "name:               %s\n", row.Name)
	fmt.Fprintf(w, "category:           %s\n", row.Category)
	fmt.Fprintf(w, "languages:          [%s]\n", strings.Join(row.Languages, ", "))
	fmt.Fprintf(w, "default_confidence: %s\n", row.DefaultConfidence)
	fmt.Fprintf(w, "go_type:            %s\n", row.GoType)
	return nil
}

// sortedCopy returns a defensive sorted copy of the slice.
func sortedCopy(xs []string) []string {
	out := append([]string(nil), xs...)
	sort.Strings(out)
	return out
}

