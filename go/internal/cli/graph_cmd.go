package cli

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/randomcodespace/codeiq/go/internal/flow"
	"github.com/randomcodespace/codeiq/go/internal/graph"
	"github.com/randomcodespace/codeiq/go/internal/model"
	"github.com/spf13/cobra"
	"gopkg.in/yaml.v3"
)

func init() {
	registerSubcommand(newGraphCommand)
}

// newGraphCommand assembles `codeiq graph` — full graph export in JSON,
// YAML, Mermaid, or DOT.
func newGraphCommand() *cobra.Command {
	var (
		graphDir     string
		format       string
		outPath      string
		queryTimeout time.Duration
	)
	cmd := &cobra.Command{
		Use:   "graph [path]",
		Short: "Export the full graph in JSON, YAML, Mermaid, or DOT.",
		Long: `Export every node and edge from the analyzed graph in a single
file. Useful for parity diffs, off-line analysis, and feeding the graph
into other tools.

JSON / YAML emit a {nodes, edges, stats} object with the full hydrated
properties for every node and edge. Mermaid and DOT collapse the data
into a renderable diagram — large graphs (>500 nodes) are truncated to
keep the output legible; use JSON / YAML for the complete view.`,
		Example: `  codeiq graph --format json > graph.json
  codeiq graph --format mermaid | head -20
  codeiq graph --format dot --out graph.dot
  codeiq graph --format yaml /repo`,
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			format = strings.ToLower(strings.TrimSpace(format))
			root, err := resolvePath(args)
			if err != nil {
				return err
			}
			gdir := graphDir
			if gdir == "" {
				gdir = filepath.Join(root, ".codeiq", "graph", "codeiq.kuzu")
			}
			store, err := graph.OpenReadOnly(gdir, queryTimeout)
			if err != nil {
				return fmt.Errorf("open graph %s: %w", gdir, err)
			}
			defer store.Close()

			nodes, err := store.LoadAllNodes()
			if err != nil {
				return fmt.Errorf("load nodes: %w", err)
			}
			edges, err := store.LoadAllEdges()
			if err != nil {
				return fmt.Errorf("load edges: %w", err)
			}
			body, err := renderGraphExport(format, nodes, edges)
			if err != nil {
				return err
			}
			return writeGraphOutput(cmd.OutOrStdout(), body, outPath)
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	cmd.Flags().StringVarP(&format, "format", "f", "json",
		"Output format: json, yaml, mermaid, dot.")
	cmd.Flags().StringVar(&outPath, "out", "",
		"Write the exported graph to this file instead of stdout.")
	cmd.Flags().DurationVar(&queryTimeout, "query-timeout", graph.DefaultQueryTimeout,
		"Per-query wall-clock timeout (default: 30s).")
	return cmd
}

// renderGraphExport dispatches the format. JSON / YAML emit the full
// (nodes, edges) payload; Mermaid / DOT delegate to the flow renderer
// after projecting the graph into a flow.Diagram.
func renderGraphExport(format string, nodes []*model.CodeNode, edges []*model.CodeEdge) (string, error) {
	switch format {
	case "", "json":
		return renderGraphJSON(nodes, edges)
	case "yaml", "yml":
		return renderGraphYAML(nodes, edges)
	case "mermaid":
		return flow.RenderMermaid(graphToDiagram(nodes, edges)), nil
	case "dot":
		return flow.RenderDOT(graphToDiagram(nodes, edges)), nil
	default:
		return "", fmt.Errorf("graph: unknown format %q (valid: json, yaml, mermaid, dot)", format)
	}
}

func renderGraphJSON(nodes []*model.CodeNode, edges []*model.CodeEdge) (string, error) {
	body, err := json.MarshalIndent(graphExportPayload(nodes, edges), "", "  ")
	if err != nil {
		return "", fmt.Errorf("graph: marshal json: %w", err)
	}
	return string(body), nil
}

func renderGraphYAML(nodes []*model.CodeNode, edges []*model.CodeEdge) (string, error) {
	body, err := yaml.Marshal(graphExportPayload(nodes, edges))
	if err != nil {
		return "", fmt.Errorf("graph: marshal yaml: %w", err)
	}
	return string(body), nil
}

// graphExportPayload assembles the canonical {nodes, edges, stats}
// envelope used by JSON and YAML exports.
func graphExportPayload(nodes []*model.CodeNode, edges []*model.CodeEdge) map[string]any {
	return map[string]any{
		"nodes": nodes,
		"edges": edges,
		"stats": map[string]any{
			"node_count": len(nodes),
			"edge_count": len(edges),
		},
	}
}

// graphToDiagram projects the raw graph into a flow.Diagram so the Mermaid
// / DOT renderers can render it. Nodes are emitted as loose nodes (no
// subgraph grouping) and edges as flow edges. To keep the rendered output
// legible, the projection truncates at 500 nodes — large graphs should be
// exported as JSON / YAML.
const graphExportMermaidLimit = 500

func graphToDiagram(nodes []*model.CodeNode, edges []*model.CodeEdge) *flow.Diagram {
	d := flow.NewDiagram("Full Graph", "graph")
	limit := len(nodes)
	if limit > graphExportMermaidLimit {
		limit = graphExportMermaidLimit
	}
	// Deterministic sort by ID.
	sorted := append([]*model.CodeNode(nil), nodes...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].ID < sorted[j].ID })
	for i := 0; i < limit; i++ {
		n := sorted[i]
		d.LooseNodes = append(d.LooseNodes, flow.NewNode(n.ID, n.Label, flowKindFor(n.Kind)))
	}
	for _, e := range edges {
		d.Edges = append(d.Edges, flow.NewLabelEdge(e.SourceID, e.TargetID, e.Kind.String()))
	}
	d.Stats = map[string]any{
		"node_count":     len(nodes),
		"edge_count":     len(edges),
		"rendered_nodes": limit,
		"truncated":      len(nodes) > limit,
	}
	return d
}

// flowKindFor maps a NodeKind onto the kind label flow.renderer uses for
// bracket / shape lookup. Falls back to "code" for kinds without a custom
// shape.
func flowKindFor(k model.NodeKind) string {
	switch k {
	case model.NodeEndpoint, model.NodeWebSocketEndpoint:
		return "endpoint"
	case model.NodeEntity, model.NodeSQLEntity:
		return "entity"
	case model.NodeDatabaseConnection:
		return "database"
	case model.NodeGuard:
		return "guard"
	case model.NodeMiddleware:
		return "middleware"
	case model.NodeComponent:
		return "component"
	case model.NodeTopic, model.NodeQueue, model.NodeEvent, model.NodeMessageQueue:
		return "messaging"
	case model.NodeInfraResource, model.NodeAzureResource:
		return "infra"
	case model.NodeService:
		return "service"
	}
	return "code"
}

func writeGraphOutput(w io.Writer, content, outPath string) error {
	if !strings.HasSuffix(content, "\n") {
		content += "\n"
	}
	if outPath == "" {
		_, err := io.WriteString(w, content)
		return err
	}
	return os.WriteFile(outPath, []byte(content), 0o644)
}
