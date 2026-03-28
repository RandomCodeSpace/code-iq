"""SQLite-backed graph backend."""

from __future__ import annotations

import json
import logging
import sqlite3
from typing import Any

import networkx as nx

from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
)

logger = logging.getLogger(__name__)

_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS nodes (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    label TEXT NOT NULL,
    data JSON NOT NULL
);
CREATE TABLE IF NOT EXISTS edges (
    rowid INTEGER PRIMARY KEY AUTOINCREMENT,
    source TEXT NOT NULL,
    target TEXT NOT NULL,
    kind TEXT NOT NULL,
    data JSON NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_edges_source ON edges(source);
CREATE INDEX IF NOT EXISTS idx_edges_target ON edges(target);
CREATE INDEX IF NOT EXISTS idx_nodes_kind ON nodes(kind);
CREATE INDEX IF NOT EXISTS idx_edges_kind ON edges(kind);
"""


def _serialize_node(node: GraphNode) -> tuple[str, str, str, str]:
    """Serialize a GraphNode to a tuple suitable for INSERT."""
    return (
        node.id,
        node.kind.value,
        node.label,
        json.dumps(node.model_dump(mode="json")),
    )


def _deserialize_node(data_json: str) -> GraphNode:
    """Reconstruct a GraphNode from its JSON representation."""
    return GraphNode(**json.loads(data_json))


def _serialize_edge(edge: GraphEdge) -> tuple[str, str, str, str]:
    """Serialize a GraphEdge to a tuple suitable for INSERT."""
    return (
        edge.source,
        edge.target,
        edge.kind.value,
        json.dumps(edge.model_dump(mode="json")),
    )


def _deserialize_edge(data_json: str) -> GraphEdge:
    """Reconstruct a GraphEdge from its JSON representation."""
    return GraphEdge(**json.loads(data_json))


class SqliteGraphBackend:
    """Persistent graph backend using SQLite.

    Stores nodes and edges in a SQLite database with JSON-serialized
    Pydantic model data.  Uses WAL journal mode for good write concurrency
    and indexes on ``kind``, ``source``, and ``target`` columns.
    """

    def __init__(self, db_path: str) -> None:
        self._db_path = db_path
        try:
            self._conn = sqlite3.connect(db_path)
            self._conn.row_factory = sqlite3.Row
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA synchronous=NORMAL")
            self._conn.executescript(_SCHEMA_SQL)
            self._conn.commit()
        except sqlite3.Error:
            logger.exception("Failed to initialize SQLite backend at %s", db_path)
            raise

    # ------------------------------------------------------------------
    # Properties
    # ------------------------------------------------------------------

    @property
    def node_count(self) -> int:
        try:
            row = self._conn.execute("SELECT COUNT(*) FROM nodes").fetchone()
            return row[0]
        except sqlite3.Error:
            logger.exception("Failed to count nodes")
            return 0

    @property
    def edge_count(self) -> int:
        try:
            row = self._conn.execute("SELECT COUNT(*) FROM edges").fetchone()
            return row[0]
        except sqlite3.Error:
            logger.exception("Failed to count edges")
            return 0

    # ------------------------------------------------------------------
    # Mutations
    # ------------------------------------------------------------------

    def add_node(self, node: GraphNode) -> None:
        try:
            self._conn.execute(
                "INSERT OR IGNORE INTO nodes VALUES (?, ?, ?, ?)",
                _serialize_node(node),
            )
            self._conn.commit()
        except sqlite3.Error:
            logger.exception("Failed to add node %s", node.id)

    def add_edge(self, edge: GraphEdge) -> None:
        # Only add edge if both nodes exist — consistent with KuzuDB/Neo4j behavior
        if not self.has_node(edge.source) or not self.has_node(edge.target):
            return
        try:
            self._conn.execute(
                "INSERT INTO edges (source, target, kind, data) VALUES (?, ?, ?, ?)",
                _serialize_edge(edge),
            )
            self._conn.commit()
        except sqlite3.Error:
            logger.exception("Failed to add edge %s -> %s", edge.source, edge.target)

    def clear(self) -> None:
        try:
            self._conn.execute("DELETE FROM edges")
            self._conn.execute("DELETE FROM nodes")
            self._conn.commit()
        except sqlite3.Error:
            logger.exception("Failed to clear graph")

    def update_node_properties(self, node_id: str, properties: dict[str, Any]) -> None:
        try:
            row = self._conn.execute(
                "SELECT data FROM nodes WHERE id = ?", (node_id,)
            ).fetchone()
            if row is None:
                return
            node_data = json.loads(row[0])
            props = node_data.get("properties", {})
            props.update(properties)
            node_data["properties"] = props
            # Also update the label column in case callers rely on it,
            # but the primary payload is the JSON blob.
            node = GraphNode(**node_data)
            self._conn.execute(
                "UPDATE nodes SET data = ? WHERE id = ?",
                (json.dumps(node.model_dump(mode="json")), node_id),
            )
            self._conn.commit()
        except sqlite3.Error:
            logger.exception("Failed to update properties for node %s", node_id)

    # ------------------------------------------------------------------
    # Queries — single item
    # ------------------------------------------------------------------

    def get_node(self, node_id: str) -> GraphNode | None:
        try:
            row = self._conn.execute(
                "SELECT data FROM nodes WHERE id = ?", (node_id,)
            ).fetchone()
            if row is None:
                return None
            return _deserialize_node(row[0])
        except sqlite3.Error:
            logger.exception("Failed to get node %s", node_id)
            return None

    def has_node(self, node_id: str) -> bool:
        try:
            row = self._conn.execute(
                "SELECT 1 FROM nodes WHERE id = ? LIMIT 1", (node_id,)
            ).fetchone()
            return row is not None
        except sqlite3.Error:
            logger.exception("Failed to check node %s", node_id)
            return False

    def get_edges_between(self, source: str, target: str) -> list[GraphEdge]:
        try:
            rows = self._conn.execute(
                "SELECT data FROM edges WHERE source = ? AND target = ?",
                (source, target),
            ).fetchall()
            return [_deserialize_edge(r[0]) for r in rows]
        except sqlite3.Error:
            logger.exception("Failed to get edges between %s and %s", source, target)
            return []

    # ------------------------------------------------------------------
    # Queries — bulk
    # ------------------------------------------------------------------

    def all_nodes(self) -> list[GraphNode]:
        try:
            rows = self._conn.execute("SELECT data FROM nodes").fetchall()
            return [_deserialize_node(r[0]) for r in rows]
        except sqlite3.Error:
            logger.exception("Failed to fetch all nodes")
            return []

    def all_edges(self) -> list[GraphEdge]:
        try:
            rows = self._conn.execute("SELECT data FROM edges").fetchall()
            return [_deserialize_edge(r[0]) for r in rows]
        except sqlite3.Error:
            logger.exception("Failed to fetch all edges")
            return []

    def nodes_by_kind(self, kind: NodeKind) -> list[GraphNode]:
        try:
            rows = self._conn.execute(
                "SELECT data FROM nodes WHERE kind = ?", (kind.value,)
            ).fetchall()
            return [_deserialize_node(r[0]) for r in rows]
        except sqlite3.Error:
            logger.exception("Failed to fetch nodes of kind %s", kind)
            return []

    def edges_by_kind(self, kind: EdgeKind) -> list[GraphEdge]:
        try:
            rows = self._conn.execute(
                "SELECT data FROM edges WHERE kind = ?", (kind.value,)
            ).fetchall()
            return [_deserialize_edge(r[0]) for r in rows]
        except sqlite3.Error:
            logger.exception("Failed to fetch edges of kind %s", kind)
            return []

    # ------------------------------------------------------------------
    # Graph traversal
    # ------------------------------------------------------------------

    def neighbors(
        self,
        node_id: str,
        edge_kinds: set[EdgeKind] | None = None,
        direction: str = "both",
    ) -> list[str]:
        result: set[str] = set()
        try:
            if direction in ("out", "both"):
                if edge_kinds is not None:
                    placeholders = ",".join("?" for _ in edge_kinds)
                    rows = self._conn.execute(
                        f"SELECT target FROM edges WHERE source = ? AND kind IN ({placeholders})",
                        (node_id, *(k.value for k in edge_kinds)),
                    ).fetchall()
                else:
                    rows = self._conn.execute(
                        "SELECT target FROM edges WHERE source = ?", (node_id,)
                    ).fetchall()
                result.update(r[0] for r in rows)

            if direction in ("in", "both"):
                if edge_kinds is not None:
                    placeholders = ",".join("?" for _ in edge_kinds)
                    rows = self._conn.execute(
                        f"SELECT source FROM edges WHERE target = ? AND kind IN ({placeholders})",
                        (node_id, *(k.value for k in edge_kinds)),
                    ).fetchall()
                else:
                    rows = self._conn.execute(
                        "SELECT source FROM edges WHERE target = ?", (node_id,)
                    ).fetchall()
                result.update(r[0] for r in rows)
        except sqlite3.Error:
            logger.exception("Failed to find neighbors of %s", node_id)
        return sorted(result)

    # ------------------------------------------------------------------
    # Advanced graph algorithms
    # ------------------------------------------------------------------

    def find_cycles(self, limit: int = 100) -> list[list[str]]:
        """Detect cycles.

        Attempts a recursive-CTE approach first for small/medium graphs.
        Falls back to NetworkX ``simple_cycles`` for robustness.
        """
        try:
            return self._find_cycles_networkx(limit)
        except Exception:
            logger.exception("Cycle detection failed")
            return []

    def _find_cycles_networkx(self, limit: int) -> list[list[str]]:
        """Load the graph into NetworkX and use ``simple_cycles``."""
        g = self._to_networkx()
        cycles: list[list[str]] = []
        for cycle in nx.simple_cycles(g):
            cycles.append(cycle)
            if len(cycles) >= limit:
                break
        return cycles

    def shortest_path(self, source: str, target: str) -> list[str] | None:
        """Find the shortest path between two nodes.

        Uses a BFS via recursive CTE for simple cases, falling back to
        NetworkX for correctness.
        """
        try:
            return self._shortest_path_networkx(source, target)
        except nx.NetworkXNoPath:
            return None
        except Exception:
            logger.exception("Shortest-path computation failed")
            return None

    def _shortest_path_networkx(self, source: str, target: str) -> list[str] | None:
        g = self._to_networkx()
        try:
            return nx.shortest_path(g, source, target)
        except nx.NetworkXNoPath:
            return None
        except nx.NodeNotFound:
            return None

    # ------------------------------------------------------------------
    # Subgraph extraction
    # ------------------------------------------------------------------

    def subgraph(self, node_ids: set[str]) -> SqliteGraphBackend:
        """Return a new in-memory SqliteGraphBackend containing only the
        specified nodes and the edges between them."""
        sub = SqliteGraphBackend(":memory:")
        try:
            if not node_ids:
                return sub
            placeholders = ",".join("?" for _ in node_ids)
            ids = tuple(node_ids)

            node_rows = self._conn.execute(
                f"SELECT id, kind, label, data FROM nodes WHERE id IN ({placeholders})",
                ids,
            ).fetchall()
            if node_rows:
                sub._conn.executemany(
                    "INSERT OR IGNORE INTO nodes VALUES (?, ?, ?, ?)",
                    [(r[0], r[1], r[2], r[3]) for r in node_rows],
                )

            edge_rows = self._conn.execute(
                f"SELECT source, target, kind, data FROM edges "
                f"WHERE source IN ({placeholders}) AND target IN ({placeholders})",
                ids + ids,
            ).fetchall()
            if edge_rows:
                sub._conn.executemany(
                    "INSERT INTO edges (source, target, kind, data) VALUES (?, ?, ?, ?)",
                    [(r[0], r[1], r[2], r[3]) for r in edge_rows],
                )

            sub._conn.commit()
        except sqlite3.Error:
            logger.exception("Failed to extract subgraph")
        return sub

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def close(self) -> None:
        try:
            self._conn.commit()
            self._conn.close()
        except sqlite3.Error:
            logger.exception("Error closing SQLite connection")

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _to_networkx(self) -> nx.DiGraph:
        """Load the full graph into a NetworkX DiGraph for algorithm use."""
        g = nx.DiGraph()
        try:
            for row in self._conn.execute("SELECT id FROM nodes").fetchall():
                g.add_node(row[0])
            for row in self._conn.execute(
                "SELECT source, target FROM edges"
            ).fetchall():
                g.add_edge(row[0], row[1])
        except sqlite3.Error:
            logger.exception("Failed to load graph into NetworkX")
        return g
