"""SQLite-backed cache store for incremental analysis."""

from __future__ import annotations

import json
import logging
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

logger = logging.getLogger(__name__)

_SCHEMA_SQL = """\
CREATE TABLE IF NOT EXISTS files (
    content_hash TEXT PRIMARY KEY,
    path TEXT NOT NULL,
    language TEXT NOT NULL,
    parsed_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS nodes (
    id TEXT PRIMARY KEY,
    content_hash TEXT NOT NULL,
    kind TEXT NOT NULL,
    data JSON NOT NULL,
    FOREIGN KEY (content_hash) REFERENCES files(content_hash)
);

CREATE TABLE IF NOT EXISTS edges (
    source TEXT NOT NULL,
    target TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    kind TEXT NOT NULL,
    data JSON NOT NULL,
    FOREIGN KEY (content_hash) REFERENCES files(content_hash)
);

CREATE TABLE IF NOT EXISTS analysis_runs (
    run_id TEXT PRIMARY KEY,
    commit_sha TEXT,
    timestamp TEXT NOT NULL,
    file_count INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_nodes_content_hash ON nodes(content_hash);
CREATE INDEX IF NOT EXISTS idx_edges_content_hash ON edges(content_hash);
CREATE INDEX IF NOT EXISTS idx_analysis_runs_timestamp ON analysis_runs(timestamp);
"""


def _node_to_dict(node: GraphNode) -> dict[str, Any]:
    """Serialize a GraphNode to a JSON-safe dictionary."""
    return node.model_dump(mode="json")


def _dict_to_node(data: dict[str, Any]) -> GraphNode:
    """Deserialize a dictionary to a GraphNode."""
    return GraphNode.model_validate(data)


def _edge_to_dict(edge: GraphEdge) -> dict[str, Any]:
    """Serialize a GraphEdge to a JSON-safe dictionary."""
    return edge.model_dump(mode="json")


def _dict_to_edge(data: dict[str, Any]) -> GraphEdge:
    """Deserialize a dictionary to a GraphEdge."""
    return GraphEdge.model_validate(data)


class CacheStore:
    """SQLite-backed cache for analysis results.

    Stores per-file parse results (nodes and edges) keyed by content hash,
    enabling fast incremental re-analysis when only a subset of files change.
    """

    def __init__(self, db_path: Path) -> None:
        self._db_path = db_path
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(db_path))
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.execute("PRAGMA foreign_keys=ON")
        self.init_db()

    def init_db(self) -> None:
        """Create schema tables if they do not already exist."""
        with self._conn:
            self._conn.executescript(_SCHEMA_SQL)

    def close(self) -> None:
        """Close the database connection."""
        self._conn.close()

    # ------------------------------------------------------------------
    # Commit tracking
    # ------------------------------------------------------------------

    def get_last_commit(self) -> str | None:
        """Return the commit SHA from the most recent analysis run, or None."""
        row = self._conn.execute(
            "SELECT commit_sha FROM analysis_runs "
            "ORDER BY timestamp DESC LIMIT 1"
        ).fetchone()
        if row is None or row[0] is None:
            return None
        return row[0]

    # ------------------------------------------------------------------
    # Cache lookups
    # ------------------------------------------------------------------

    def is_cached(self, content_hash: str) -> bool:
        """Check whether results for *content_hash* are in the cache."""
        row = self._conn.execute(
            "SELECT 1 FROM files WHERE content_hash = ?", (content_hash,)
        ).fetchone()
        return row is not None

    # ------------------------------------------------------------------
    # Store / load results
    # ------------------------------------------------------------------

    def store_results(
        self,
        content_hash: str,
        file_path: str,
        language: str,
        nodes: list[GraphNode],
        edges: list[GraphEdge],
    ) -> None:
        """Persist analysis results for a single file."""
        now = datetime.now(timezone.utc).isoformat()
        with self._conn:
            # Upsert file record
            self._conn.execute(
                "INSERT OR REPLACE INTO files (content_hash, path, language, parsed_at) "
                "VALUES (?, ?, ?, ?)",
                (content_hash, file_path, language, now),
            )

            # Remove old nodes/edges for this hash (idempotent re-store)
            self._conn.execute(
                "DELETE FROM nodes WHERE content_hash = ?", (content_hash,)
            )
            self._conn.execute(
                "DELETE FROM edges WHERE content_hash = ?", (content_hash,)
            )

            # Insert nodes
            for node in nodes:
                self._conn.execute(
                    "INSERT INTO nodes (id, content_hash, kind, data) "
                    "VALUES (?, ?, ?, ?)",
                    (
                        node.id,
                        content_hash,
                        node.kind.value,
                        json.dumps(_node_to_dict(node)),
                    ),
                )

            # Insert edges
            for edge in edges:
                self._conn.execute(
                    "INSERT INTO edges (source, target, content_hash, kind, data) "
                    "VALUES (?, ?, ?, ?, ?)",
                    (
                        edge.source,
                        edge.target,
                        content_hash,
                        edge.kind.value,
                        json.dumps(_edge_to_dict(edge)),
                    ),
                )

    def load_cached_results(
        self, content_hash: str
    ) -> tuple[list[GraphNode], list[GraphEdge]]:
        """Load cached nodes and edges for a given content hash."""
        node_rows = self._conn.execute(
            "SELECT data FROM nodes WHERE content_hash = ?", (content_hash,)
        ).fetchall()
        edge_rows = self._conn.execute(
            "SELECT data FROM edges WHERE content_hash = ?", (content_hash,)
        ).fetchall()

        nodes = [_dict_to_node(json.loads(row[0])) for row in node_rows]
        edges = [_dict_to_edge(json.loads(row[0])) for row in edge_rows]
        return nodes, edges

    # ------------------------------------------------------------------
    # Cache invalidation
    # ------------------------------------------------------------------

    def remove_file(self, content_hash: str) -> None:
        """Delete all cached results associated with *content_hash*."""
        with self._conn:
            self._conn.execute(
                "DELETE FROM nodes WHERE content_hash = ?", (content_hash,)
            )
            self._conn.execute(
                "DELETE FROM edges WHERE content_hash = ?", (content_hash,)
            )
            self._conn.execute(
                "DELETE FROM files WHERE content_hash = ?", (content_hash,)
            )

    def remove_by_path(self, file_path: str) -> None:
        """Remove all cache entries associated with *file_path*."""
        with self._conn:
            rows = self._conn.execute(
                "SELECT content_hash FROM files WHERE path = ?", (file_path,)
            ).fetchall()
            for (content_hash,) in rows:
                self.remove_file(content_hash)

    # ------------------------------------------------------------------
    # Run tracking
    # ------------------------------------------------------------------

    def record_run(self, commit_sha: str, file_count: int) -> None:
        """Record an analysis run with its commit SHA and file count."""
        run_id = uuid.uuid4().hex
        now = datetime.now(timezone.utc).isoformat()
        with self._conn:
            self._conn.execute(
                "INSERT INTO analysis_runs (run_id, commit_sha, timestamp, file_count) "
                "VALUES (?, ?, ?, ?)",
                (run_id, commit_sha, now, file_count),
            )

    # ------------------------------------------------------------------
    # Bulk operations
    # ------------------------------------------------------------------

    def load_full_graph(self) -> GraphStore:
        """Load all cached nodes and edges into a fresh GraphStore."""
        store = GraphStore()

        cursor = self._conn.execute("SELECT data FROM nodes")
        for (data_json,) in cursor:
            store.add_node(_dict_to_node(json.loads(data_json)))

        cursor = self._conn.execute("SELECT data FROM edges")
        for (data_json,) in cursor:
            store.add_edge(_dict_to_edge(json.loads(data_json)))

        return store

    # ------------------------------------------------------------------
    # Statistics
    # ------------------------------------------------------------------

    def get_stats(self) -> dict[str, Any]:
        """Return cache statistics."""
        file_count = self._conn.execute(
            "SELECT COUNT(*) FROM files"
        ).fetchone()[0]
        node_count = self._conn.execute(
            "SELECT COUNT(*) FROM nodes"
        ).fetchone()[0]
        edge_count = self._conn.execute(
            "SELECT COUNT(*) FROM edges"
        ).fetchone()[0]
        run_count = self._conn.execute(
            "SELECT COUNT(*) FROM analysis_runs"
        ).fetchone()[0]

        last_run = self._conn.execute(
            "SELECT timestamp, commit_sha, file_count FROM analysis_runs "
            "ORDER BY timestamp DESC LIMIT 1"
        ).fetchone()

        return {
            "cached_files": file_count,
            "cached_nodes": node_count,
            "cached_edges": edge_count,
            "total_runs": run_count,
            "last_run": {
                "timestamp": last_run[0],
                "commit_sha": last_run[1],
                "file_count": last_run[2],
            }
            if last_run
            else None,
            "db_path": str(self._db_path),
        }
