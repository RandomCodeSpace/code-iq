package cache

import (
	"database/sql"
	"path/filepath"
	"strings"
	"testing"

	_ "github.com/mattn/go-sqlite3"
)

func TestCacheVersionConstant(t *testing.T) {
	if CacheVersion != 6 {
		t.Fatalf("CacheVersion = %d, want 6 (Java is 5; Go starts at 6 to force rebuild)", CacheVersion)
	}
}

func TestSchemaDDLContainsExpectedTables(t *testing.T) {
	wantTables := []string{
		"cache_meta",
		"files",
		"nodes",
		"edges",
		"analysis_runs",
	}
	for _, tbl := range wantTables {
		if !strings.Contains(schemaDDL, "CREATE TABLE IF NOT EXISTS "+tbl) {
			t.Errorf("schemaDDL missing CREATE TABLE for %q", tbl)
		}
	}
}

func TestSchemaDDLPreservesH2ReservedWordWorkaround(t *testing.T) {
	// Parity with Java AnalysisCache — meta_key / meta_value (not key/value).
	if !strings.Contains(schemaDDL, "meta_key") {
		t.Error("schemaDDL must use meta_key (H2 reserved-word workaround, kept for parity)")
	}
	if !strings.Contains(schemaDDL, "meta_value") {
		t.Error("schemaDDL must use meta_value (H2 reserved-word workaround, kept for parity)")
	}
}

func TestPragmasDDLEnablesWAL(t *testing.T) {
	wantPragmas := []string{
		"journal_mode = WAL",
		"synchronous  = NORMAL",
		"foreign_keys = ON",
		"busy_timeout = 5000",
	}
	for _, p := range wantPragmas {
		if !strings.Contains(pragmasDDL, p) {
			t.Errorf("pragmasDDL missing %q", p)
		}
	}
}

func TestSchemaDDLAppliesCleanlyToSQLite(t *testing.T) {
	// The real contract: SQLite must accept the DDL as-is. This catches
	// H2-isms (AUTO_INCREMENT vs AUTOINCREMENT, VARCHAR-without-length, etc.).
	dbPath := filepath.Join(t.TempDir(), "schema.db")
	db, err := sql.Open("sqlite3", dbPath)
	if err != nil {
		t.Fatalf("open sqlite: %v", err)
	}
	defer db.Close()

	if _, err := db.Exec(schemaDDL); err != nil {
		t.Fatalf("schemaDDL failed to apply: %v", err)
	}

	// Sanity: all five tables and three indexes must exist.
	wantObjects := map[string]string{
		"cache_meta":                  "table",
		"files":                       "table",
		"nodes":                       "table",
		"edges":                       "table",
		"analysis_runs":               "table",
		"idx_nodes_content_hash":      "index",
		"idx_edges_content_hash":      "index",
		"idx_analysis_runs_timestamp": "index",
	}
	for name, typ := range wantObjects {
		var got string
		err := db.QueryRow(
			`SELECT type FROM sqlite_master WHERE name = ?`, name,
		).Scan(&got)
		if err != nil {
			t.Errorf("missing %s %q: %v", typ, name, err)
			continue
		}
		if got != typ {
			t.Errorf("object %q has type %q, want %q", name, got, typ)
		}
	}
}
