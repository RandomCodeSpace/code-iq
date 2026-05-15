package graph

import "fmt"

const metaKeyManifest = "manifest_hash"

// ReadManifest returns the manifest_hash stored by the last successful
// enrich, or "" if none exists (fresh graph). Used by enrich to short-
// circuit when the cache and graph are already in sync.
func (s *Store) ReadManifest() (string, error) {
	rows, err := s.Cypher(
		`MATCH (m:GraphMeta) WHERE m.meta_key = $k RETURN m.value AS v`,
		map[string]any{"k": metaKeyManifest})
	if err != nil {
		return "", fmt.Errorf("graph: read manifest: %w", err)
	}
	if len(rows) == 0 {
		return "", nil
	}
	v, _ := rows[0]["v"].(string)
	return v, nil
}

// WriteManifest stores hash as the manifest_hash, replacing any existing
// value. Called at the tail of every successful enrich.
func (s *Store) WriteManifest(hash string) error {
	if _, err := s.Cypher(
		`MATCH (m:GraphMeta) WHERE m.meta_key = $k DELETE m`,
		map[string]any{"k": metaKeyManifest},
	); err != nil {
		return fmt.Errorf("graph: clear manifest: %w", err)
	}
	if _, err := s.Cypher(
		`CREATE (:GraphMeta {meta_key: $k, value: $v})`,
		map[string]any{"k": metaKeyManifest, "v": hash},
	); err != nil {
		return fmt.Errorf("graph: write manifest: %w", err)
	}
	return nil
}
