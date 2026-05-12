package graph

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// nodeColumns is the column order written to the staging CSV. The order
// MUST match the CodeNode DDL in schema.go — Kuzu COPY FROM is positional
// unless an explicit column list is supplied (which we do here).
var nodeColumns = []string{
	"id", "kind", "label", "fqn", "file_path",
	"line_start", "line_end", "module", "layer",
	"language", "framework", "confidence", "source",
	"label_lower", "fqn_lower",
	"prop_lex_comment", "prop_lex_config_keys",
	"props",
}

// BulkLoadNodes writes nodes to a temporary CSV file and ingests via Kuzu's
// COPY FROM. This is materially faster than per-node CREATE for the
// enrich-phase volumes we hit (44k files / 100k+ nodes). Empty input is a
// no-op (an empty CSV would still issue a COPY, which Kuzu may reject; the
// no-op behaviour also matches Java's bulkSave convention).
func (s *Store) BulkLoadNodes(nodes []*model.CodeNode) error {
	if len(nodes) == 0 {
		return nil
	}
	tmp, err := os.CreateTemp("", "codeiq-nodes-*.csv")
	if err != nil {
		return fmt.Errorf("graph: temp csv: %w", err)
	}
	// Cleanup runs whether COPY succeeds or fails.
	defer os.Remove(tmp.Name())

	w := csv.NewWriter(tmp)
	for _, n := range nodes {
		row, err := encodeNodeRow(n)
		if err != nil {
			tmp.Close()
			return err
		}
		if err := w.Write(row); err != nil {
			tmp.Close()
			return fmt.Errorf("graph: csv write: %w", err)
		}
	}
	w.Flush()
	if err := w.Error(); err != nil {
		tmp.Close()
		return fmt.Errorf("graph: csv flush: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("graph: csv close: %w", err)
	}

	// Kuzu COPY FROM with explicit column list. ToSlash for Windows path
	// portability — Kuzu's parser accepts forward slashes on all platforms.
	q := fmt.Sprintf(
		"COPY CodeNode(%s) FROM '%s' (header=false)",
		strings.Join(nodeColumns, ", "),
		filepath.ToSlash(tmp.Name()),
	)
	if _, err := s.Cypher(q); err != nil {
		return fmt.Errorf("graph: copy CodeNode: %w", err)
	}
	return nil
}

// encodeNodeRow serialises one CodeNode into the column order declared by
// nodeColumns. Numeric INT64 columns are emitted as empty strings when zero
// so Kuzu treats them as NULL rather than 0 (line_start/line_end on
// non-source nodes like SERVICE).
func encodeNodeRow(n *model.CodeNode) ([]string, error) {
	props, err := json.Marshal(n.Properties)
	if err != nil {
		return nil, fmt.Errorf("graph: marshal props: %w", err)
	}
	lineStart := ""
	if n.LineStart > 0 {
		lineStart = strconv.Itoa(n.LineStart)
	}
	lineEnd := ""
	if n.LineEnd > 0 {
		lineEnd = strconv.Itoa(n.LineEnd)
	}
	// Pull framework + language out of properties to populate the
	// first-class columns. Detectors usually set framework via the
	// properties map; this gives the read side a direct projection.
	framework, _ := n.Properties["framework"].(string)
	language, _ := n.Properties["language"].(string)
	return []string{
		n.ID,
		n.Kind.String(),
		n.Label,
		n.FQN,
		n.FilePath,
		lineStart,
		lineEnd,
		n.Module,
		n.Layer.String(),
		language,
		framework,
		n.Confidence.String(),
		n.Source,
		strings.ToLower(n.Label),
		strings.ToLower(n.FQN),
		stringProp(n.Properties, "lex_comment"),
		stringProp(n.Properties, "lex_config_keys"),
		string(props),
	}, nil
}

// stringProp returns p[key] as a string when present and string-typed,
// otherwise empty. The lex_* properties are written by LexicalEnricher.
func stringProp(p map[string]any, key string) string {
	if v, ok := p[key].(string); ok {
		return v
	}
	return ""
}

// edgeColumns is the column order written to each rel-table staging CSV.
// MUST match the per-kind REL table DDL in schema.go: the FROM/TO node
// primary keys come first (Kuzu COPY convention for rel tables), followed
// by the user columns id, confidence, source, props.
var edgeColumns = []string{"from", "to", "id", "confidence", "source", "props"}

// BulkLoadEdges groups edges by Kind and issues one COPY FROM per rel
// table. A mixed-kind batch is split internally — callers don't need to
// pre-partition. Empty input is a no-op.
func (s *Store) BulkLoadEdges(edges []*model.CodeEdge) error {
	if len(edges) == 0 {
		return nil
	}
	byKind := make(map[model.EdgeKind][]*model.CodeEdge)
	for _, e := range edges {
		byKind[e.Kind] = append(byKind[e.Kind], e)
	}
	// Iterate in canonical EdgeKind order so the COPY sequence is
	// deterministic — matters for parity diffing against the Java side.
	for _, kind := range model.AllEdgeKinds() {
		group, ok := byKind[kind]
		if !ok {
			continue
		}
		if err := s.copyEdgeGroup(kind, group); err != nil {
			return err
		}
	}
	return nil
}

// copyEdgeGroup stages one rel-table CSV and issues COPY <REL> FROM. The
// first two columns are the FROM and TO node primary keys per Kuzu's rel
// COPY convention.
func (s *Store) copyEdgeGroup(kind model.EdgeKind, edges []*model.CodeEdge) error {
	tmp, err := os.CreateTemp("", "codeiq-edges-*.csv")
	if err != nil {
		return fmt.Errorf("graph: temp csv: %w", err)
	}
	defer os.Remove(tmp.Name())

	w := csv.NewWriter(tmp)
	for _, e := range edges {
		props, err := json.Marshal(e.Properties)
		if err != nil {
			tmp.Close()
			return fmt.Errorf("graph: marshal edge props: %w", err)
		}
		row := []string{
			e.SourceID,
			e.TargetID,
			e.ID,
			e.Confidence.String(),
			e.Source,
			string(props),
		}
		if err := w.Write(row); err != nil {
			tmp.Close()
			return fmt.Errorf("graph: csv write: %w", err)
		}
	}
	w.Flush()
	if err := w.Error(); err != nil {
		tmp.Close()
		return fmt.Errorf("graph: csv flush: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("graph: csv close: %w", err)
	}

	q := fmt.Sprintf(
		"COPY %s FROM '%s' (header=false)",
		relTableName(kind),
		filepath.ToSlash(tmp.Name()),
	)
	if _, err := s.Cypher(q); err != nil {
		return fmt.Errorf("graph: copy %s: %w", relTableName(kind), err)
	}
	return nil
}
