package cache

// CacheVersion is bumped whenever the hash algorithm, schema, or any field
// shape changes. Java side is currently version 5. Go side starts at 6 to
// force a rebuild on first run.
const CacheVersion = 6

// schemaDDL mirrors Java AnalysisCache SCHEMA_SQL, ported from H2 to SQLite.
// Differences:
//   - H2 BIGINT AUTO_INCREMENT  → SQLite INTEGER PRIMARY KEY AUTOINCREMENT
//   - H2 VARCHAR (unbounded)    → SQLite TEXT
//   - H2 INTEGER                → SQLite INTEGER
//   - "key" / "value" reserved-word workaround stays as meta_key/meta_value
//     even though SQLite doesn't reserve them — keeps parity dumps identical.
const schemaDDL = `
CREATE TABLE IF NOT EXISTS cache_meta (
    meta_key   TEXT PRIMARY KEY,
    meta_value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS files (
    content_hash     TEXT PRIMARY KEY,
    path             TEXT NOT NULL,
    language         TEXT NOT NULL,
    parsed_at        TEXT NOT NULL,
    status           TEXT DEFAULT 'DETECTED',
    detection_method TEXT DEFAULT 'tree-sitter',
    file_type        TEXT DEFAULT 'source',
    snippet          TEXT
);

CREATE TABLE IF NOT EXISTS nodes (
    row_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    id           TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    kind         TEXT NOT NULL,
    data         TEXT NOT NULL,
    FOREIGN KEY (content_hash) REFERENCES files(content_hash)
);

CREATE TABLE IF NOT EXISTS edges (
    source       TEXT NOT NULL,
    target       TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    kind         TEXT NOT NULL,
    data         TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS analysis_runs (
    run_id     TEXT PRIMARY KEY,
    commit_sha TEXT,
    timestamp  TEXT NOT NULL,
    file_count INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_nodes_content_hash ON nodes(content_hash);
CREATE INDEX IF NOT EXISTS idx_edges_content_hash ON edges(content_hash);
CREATE INDEX IF NOT EXISTS idx_analysis_runs_timestamp ON analysis_runs(timestamp);
`

// pragmasDDL is applied at open time for WAL mode + sane defaults.
const pragmasDDL = `
PRAGMA journal_mode = WAL;
PRAGMA synchronous  = NORMAL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;
`
