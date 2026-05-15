package graph

import (
	"fmt"

	"github.com/randomcodespace/codeiq/internal/model"
)

// RemoveFile deletes every CodeNode whose file_path matches path along with
// every incident relationship across all rel tables. Idempotent — calling
// with a path that has no matching nodes is a no-op that returns nil.
//
// Implementation note: we iterate per rel table because Kuzu (0.11.3) does
// not yet support a heterogeneous "match any rel" DELETE across rel tables.
// The DETACH DELETE on CodeNode then handles whatever remains.
func (s *Store) RemoveFile(path string) error {
	// First, drop every incident rel by walking each declared rel table.
	// DETACH DELETE on CodeNode would handle this, but being explicit per
	// table keeps the delete plan simple and predictable on Kuzu 0.11.3.
	for _, kind := range model.AllEdgeKinds() {
		q := fmt.Sprintf(
			`MATCH (n:CodeNode)-[r:%s]->(m:CodeNode)
			 WHERE n.file_path = $p OR m.file_path = $p
			 DELETE r`,
			relTableName(kind))
		if _, err := s.Cypher(q, map[string]any{"p": path}); err != nil {
			return fmt.Errorf("graph: remove edges for %s: %w", path, err)
		}
	}
	// Now drop the nodes themselves.
	if _, err := s.Cypher(
		`MATCH (n:CodeNode) WHERE n.file_path = $p DELETE n`,
		map[string]any{"p": path},
	); err != nil {
		return fmt.Errorf("graph: remove nodes for %s: %w", path, err)
	}
	return nil
}

// InsertFile bulk-loads nodes + edges for a single file. Equivalent to
// BulkLoadNodes + BulkLoadEdges; the path parameter is for API symmetry
// (file_path is on each node).
func (s *Store) InsertFile(path string, nodes []*model.CodeNode, edges []*model.CodeEdge) error {
	if len(nodes) == 0 && len(edges) == 0 {
		return nil
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		return fmt.Errorf("graph: insert file %s nodes: %w", path, err)
	}
	if err := s.BulkLoadEdges(edges); err != nil {
		return fmt.Errorf("graph: insert file %s edges: %w", path, err)
	}
	return nil
}

// ReplaceFile is the MODIFIED-file path: RemoveFile followed by InsertFile.
// There is a brief window between the two calls where the file's nodes are
// absent from the graph; concurrent readers see either pre-state or
// post-state but may briefly observe the file as missing. The window is
// fine for incremental enrich since enrich is the single writer.
func (s *Store) ReplaceFile(path string, nodes []*model.CodeNode, edges []*model.CodeEdge) error {
	if err := s.RemoveFile(path); err != nil {
		return err
	}
	return s.InsertFile(path, nodes, edges)
}
