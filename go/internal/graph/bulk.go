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

// bulkLoadBatchSize caps the number of rows materialised into any single
// staging CSV / `COPY FROM` call. Kuzu buffers the full CSV in process
// memory during ingest; on real-world polyglot targets (~/projects-scale
// 49k files / 434k nodes) a single CSV pushed the process past the box's
// 15 GiB RAM ceiling and got it OOM-killed. 50k rows keeps the peak
// COPY-side resident set well under 1 GiB while still amortising the
// per-statement Kuzu overhead. Override via CODEIQ_BULK_BATCH_SIZE env
// (validated in resolveBulkBatchSize) for downstream perf tuning.
const bulkLoadBatchSize = 50_000

// BulkLoadNodes writes nodes to one or more temporary CSV files and
// ingests them via Kuzu's COPY FROM, in batches of bulkLoadBatchSize.
// This is materially faster than per-node CREATE for the enrich-phase
// volumes we hit (44k files / 100k+ nodes). Empty input is a no-op (an
// empty CSV would still issue a COPY, which Kuzu may reject; the no-op
// behaviour also matches Java's bulkSave convention).
//
// Each batch is staged + ingested + cleaned up before the next batch
// starts so that neither the on-disk CSV footprint nor Kuzu's ingest
// buffer ever holds more than bulkLoadBatchSize rows. Cypher uniqueness
// constraints are still enforced cross-batch, so a duplicate primary
// key surfaces the same Copy exception either way.
func (s *Store) BulkLoadNodes(nodes []*model.CodeNode) error {
	if len(nodes) == 0 {
		return nil
	}
	batchSize := resolveBulkBatchSize()
	for start := 0; start < len(nodes); start += batchSize {
		end := start + batchSize
		if end > len(nodes) {
			end = len(nodes)
		}
		if err := s.copyNodeBatch(nodes[start:end]); err != nil {
			return err
		}
	}
	return nil
}

// copyNodeBatch stages a single CSV for `batch` and runs one Kuzu COPY
// FROM. Caller is responsible for slicing input into batches.
func (s *Store) copyNodeBatch(batch []*model.CodeNode) error {
	tmp, err := os.CreateTemp("", "codeiq-nodes-*.csv")
	if err != nil {
		return fmt.Errorf("graph: temp csv: %w", err)
	}
	// Cleanup runs whether COPY succeeds or fails.
	defer os.Remove(tmp.Name())

	// Use pipe '|' as the field delimiter so that JSON property values
	// containing commas (e.g. {"language":"python","module":"glob"}) are not
	// mis-parsed by Kuzu's CSV reader. Go's json.Marshal never emits '|',
	// so it is unambiguous as a separator.
	w := csv.NewWriter(tmp)
	w.Comma = '|'
	for _, n := range batch {
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
	//
	// DELIM='|' matches the pipe-separated staging file written above. The
	// explicit QUOTE/ESCAPE pair overrides Kuzu's default backslash-escape
	// behaviour with RFC-4180 (doubled-quote) escaping so that Go's
	// encoding/csv writer (which emits "field""with""quotes" form) round-
	// trips correctly. Fields containing the delimiter (e.g. Istio service
	// names like "inbound|7070|tcplocal|s1tcp.none") are wrapped by the Go
	// writer; Kuzu then dequotes them only when the matching escape rule is
	// set.
	q := fmt.Sprintf(
		`COPY CodeNode(%s) FROM '%s' (header=false, DELIM='|', QUOTE='"', ESCAPE='"')`,
		strings.Join(nodeColumns, ", "),
		filepath.ToSlash(tmp.Name()),
	)
	if _, err := s.Cypher(q); err != nil {
		return fmt.Errorf("graph: copy CodeNode: %w", err)
	}
	return nil
}

// resolveBulkBatchSize honours CODEIQ_BULK_BATCH_SIZE when set to a
// positive integer; otherwise returns the compiled-in default. Invalid
// values silently fall back to the default so a typo in the env never
// blocks enrichment.
func resolveBulkBatchSize() int {
	if raw := os.Getenv("CODEIQ_BULK_BATCH_SIZE"); raw != "" {
		if v, err := strconv.Atoi(raw); err == nil && v > 0 {
			return v
		}
	}
	return bulkLoadBatchSize
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

// copyEdgeGroup stages rel-table CSVs in batches of bulkLoadBatchSize
// and issues one COPY <REL> FROM per batch. The first two columns are
// the FROM and TO node primary keys per Kuzu's rel COPY convention.
// Same memory rationale as BulkLoadNodes — Kuzu buffers the full CSV
// in ingest, so chunking caps peak resident memory.
func (s *Store) copyEdgeGroup(kind model.EdgeKind, edges []*model.CodeEdge) error {
	batchSize := resolveBulkBatchSize()
	for start := 0; start < len(edges); start += batchSize {
		end := start + batchSize
		if end > len(edges) {
			end = len(edges)
		}
		if err := s.copyEdgeBatch(kind, edges[start:end]); err != nil {
			return err
		}
	}
	return nil
}

// copyEdgeBatch stages a single rel-table CSV for `batch` and runs one
// Kuzu COPY FROM.
func (s *Store) copyEdgeBatch(kind model.EdgeKind, batch []*model.CodeEdge) error {
	tmp, err := os.CreateTemp("", "codeiq-edges-*.csv")
	if err != nil {
		return fmt.Errorf("graph: temp csv: %w", err)
	}
	defer os.Remove(tmp.Name())

	// Use pipe '|' as the field delimiter — see copyNodeBatch for the rationale.
	w := csv.NewWriter(tmp)
	w.Comma = '|'
	for _, e := range batch {
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

	// DELIM/QUOTE/ESCAPE — see copyNodeBatch for the rationale (RFC-4180
	// round-trip with Go's encoding/csv).
	q := fmt.Sprintf(
		`COPY %s FROM '%s' (header=false, DELIM='|', QUOTE='"', ESCAPE='"')`,
		relTableName(kind),
		filepath.ToSlash(tmp.Name()),
	)
	if _, err := s.Cypher(q); err != nil {
		return fmt.Errorf("graph: copy %s: %w", relTableName(kind), err)
	}
	return nil
}
