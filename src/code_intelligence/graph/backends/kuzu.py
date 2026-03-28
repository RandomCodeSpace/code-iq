"""KuzuDB-backed graph backend with Cypher support."""

from __future__ import annotations

import csv
import json
import logging
import os
import tempfile
from typing import Any

import kuzu

from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Schema DDL
# ---------------------------------------------------------------------------
_CREATE_NODE_TABLE = """
CREATE NODE TABLE IF NOT EXISTS CodeNode(
    id STRING,
    kind STRING,
    label STRING,
    fqn STRING,
    module STRING,
    file_path STRING,
    line_start INT64,
    line_end INT64,
    annotations STRING,
    properties STRING,
    PRIMARY KEY(id)
)
""".strip()

_CREATE_EDGE_TABLE = """
CREATE REL TABLE IF NOT EXISTS CODE_EDGE(
    FROM CodeNode TO CodeNode,
    kind STRING,
    label STRING,
    properties STRING
)
""".strip()


# ---------------------------------------------------------------------------
# Serialization helpers
# ---------------------------------------------------------------------------
def _node_to_params(node: GraphNode) -> dict[str, Any]:
    """Convert a GraphNode to a flat dict suitable for Cypher parameters."""
    return {
        "id": node.id,
        "kind": node.kind.value,
        "label": node.label,
        "fqn": node.fqn or "",
        "module": node.module or "",
        "file_path": node.location.file_path if node.location else "",
        "line_start": node.location.line_start if node.location and node.location.line_start is not None else -1,
        "line_end": node.location.line_end if node.location and node.location.line_end is not None else -1,
        "annotations": json.dumps(node.annotations),
        "properties": json.dumps(node.properties),
    }


def _row_to_node(columns: list[str], row: list[Any]) -> GraphNode:
    """Reconstruct a *GraphNode* from a ``RETURN n.*`` result row.

    *columns* must be the column names returned by the query (e.g.
    ``["n.id", "n.kind", ...]``).  We strip the ``n.`` prefix to get
    field names.
    """
    data: dict[str, Any] = {}
    for col, val in zip(columns, row):
        # column names look like "n.id", "n.kind", etc.
        field = col.rsplit(".", 1)[-1]
        data[field] = val

    location: SourceLocation | None = None
    if data.get("file_path"):
        ls = data.get("line_start")
        le = data.get("line_end")
        location = SourceLocation(
            file_path=data["file_path"],
            line_start=ls if ls is not None and ls >= 0 else None,
            line_end=le if le is not None and le >= 0 else None,
        )

    annotations_raw = data.get("annotations", "[]")
    properties_raw = data.get("properties", "{}")

    return GraphNode(
        id=data["id"],
        kind=NodeKind(data["kind"]),
        label=data["label"],
        fqn=data.get("fqn") or None,
        module=data.get("module") or None,
        location=location,
        annotations=json.loads(annotations_raw) if annotations_raw else [],
        properties=json.loads(properties_raw) if properties_raw else {},
    )


def _edge_row_to_edge(columns: list[str], row: list[Any]) -> GraphEdge:
    """Reconstruct a *GraphEdge* from an edge query result row.

    Expected columns pattern: ``["a.id", "b.id", "e.kind", "e.label", "e.properties"]``.
    """
    data: dict[str, Any] = {}
    for col, val in zip(columns, row):
        data[col] = val

    # Find source / target ids  (first two columns are a.id and b.id)
    source = row[0]
    target = row[1]

    # Remaining columns are edge properties prefixed with "e."
    kind_val = data.get("e.kind", "")
    label_val = data.get("e.label")
    props_raw = data.get("e.properties", "{}")

    return GraphEdge(
        source=source,
        target=target,
        kind=EdgeKind(kind_val),
        label=label_val or None,
        properties=json.loads(props_raw) if props_raw else {},
    )


# ---------------------------------------------------------------------------
# KuzuBackend
# ---------------------------------------------------------------------------
class KuzuBackend:
    """Persistent graph backend using KuzuDB (embedded graph database).

    Implements both :class:`GraphBackend` and :class:`CypherBackend` protocols.
    """

    def __init__(self, db_path: str) -> None:
        self._db = kuzu.Database(db_path)
        self._conn = kuzu.Connection(self._db)
        self._ensure_schema()

    # ------------------------------------------------------------------
    # Schema bootstrapping
    # ------------------------------------------------------------------
    def _ensure_schema(self) -> None:
        """Create the node and relationship tables if they don't exist."""
        try:
            self._conn.execute(_CREATE_NODE_TABLE)
            self._conn.execute(_CREATE_EDGE_TABLE)
        except Exception:
            logger.exception("Failed to ensure KuzuDB schema")
            raise

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------
    def _execute(
        self, query: str, params: dict[str, Any] | None = None
    ) -> kuzu.QueryResult | None:
        """Execute a Cypher statement, returning the QueryResult or *None* on error."""
        try:
            return self._conn.execute(query, parameters=params or {})
        except Exception:
            logger.exception("KuzuDB query failed: %s | params=%s", query, params)
            return None

    # ------------------------------------------------------------------
    # GraphBackend protocol
    # ------------------------------------------------------------------
    def add_node(self, node: GraphNode) -> None:
        if self.has_node(node.id):
            logger.debug("Duplicate node ID %s, keeping first", node.id)
            return
        params = _node_to_params(node)
        self._execute(
            "CREATE (n:CodeNode {"
            "id: $id, kind: $kind, label: $label, fqn: $fqn, module: $module, "
            "file_path: $file_path, line_start: $line_start, line_end: $line_end, "
            "annotations: $annotations, properties: $properties"
            "})",
            params,
        )

    def add_edge(self, edge: GraphEdge) -> None:
        params = {
            "src": edge.source,
            "tgt": edge.target,
            "kind": edge.kind.value,
            "label": edge.label or "",
            "properties": json.dumps(edge.properties),
        }
        self._execute(
            "MATCH (a:CodeNode {id: $src}), (b:CodeNode {id: $tgt}) "
            "CREATE (a)-[:CODE_EDGE {kind: $kind, label: $label, properties: $properties}]->(b)",
            params,
        )

    def bulk_add_nodes(self, nodes: list[GraphNode]) -> None:
        """Bulk-insert nodes via CSV COPY FROM (~100x faster than per-row)."""
        if not nodes:
            return
        seen: set[str] = set()
        unique_nodes: list[GraphNode] = []
        for n in nodes:
            if n.id not in seen:
                seen.add(n.id)
                unique_nodes.append(n)

        csv_path = ""
        try:
            fd = tempfile.NamedTemporaryFile(
                mode="w", suffix=".csv", delete=False, newline=""
            )
            csv_path = fd.name
            writer = csv.writer(fd)
            for node in unique_nodes:
                p = _node_to_params(node)
                writer.writerow([
                    p["id"], p["kind"], p["label"], p["fqn"], p["module"],
                    p["file_path"], p["line_start"], p["line_end"],
                    p["annotations"], p["properties"],
                ])
            fd.close()
            self._conn.execute(
                f'COPY CodeNode FROM "{csv_path}" (HEADER=false)'
            )
        except Exception:
            logger.exception("Bulk node insert failed, falling back to per-row")
            for node in unique_nodes:
                self.add_node(node)
        finally:
            if csv_path:
                try:
                    os.unlink(csv_path)
                except OSError:
                    pass

    def bulk_add_edges(self, edges: list[GraphEdge]) -> None:
        """Bulk-insert edges via CSV COPY FROM (~100x faster than per-row)."""
        if not edges:
            return
        csv_path = ""
        try:
            fd = tempfile.NamedTemporaryFile(
                mode="w", suffix=".csv", delete=False, newline=""
            )
            csv_path = fd.name
            writer = csv.writer(fd)
            for edge in edges:
                writer.writerow([
                    edge.source,
                    edge.target,
                    edge.kind.value,
                    edge.label or "",
                    json.dumps(edge.properties),
                ])
            fd.close()
            self._conn.execute(
                f'COPY CODE_EDGE FROM "{csv_path}" (HEADER=false)'
            )
        except Exception:
            logger.exception("Bulk edge insert failed, falling back to per-row")
            for edge in edges:
                self.add_edge(edge)
        finally:
            if csv_path:
                try:
                    os.unlink(csv_path)
                except OSError:
                    pass

    def clear(self) -> None:
        """Remove all data by dropping and recreating both tables."""
        try:
            self._conn.execute("DROP TABLE CODE_EDGE")
        except Exception:
            logger.debug("DROP TABLE CODE_EDGE failed (may not exist)")
        try:
            self._conn.execute("DROP TABLE CodeNode")
        except Exception:
            logger.debug("DROP TABLE CodeNode failed (may not exist)")
        self._ensure_schema()

    def get_node(self, node_id: str) -> GraphNode | None:
        result = self._execute(
            "MATCH (n:CodeNode {id: $id}) RETURN n.*", {"id": node_id}
        )
        if result is None:
            return None
        rows = result.get_all()
        if not rows:
            return None
        return _row_to_node(result.get_column_names(), rows[0])

    def has_node(self, node_id: str) -> bool:
        result = self._execute(
            "MATCH (n:CodeNode {id: $id}) RETURN COUNT(n)", {"id": node_id}
        )
        if result is None:
            return False
        rows = result.get_all()
        return bool(rows and rows[0][0] > 0)

    def get_edges_between(self, source: str, target: str) -> list[GraphEdge]:
        result = self._execute(
            "MATCH (a:CodeNode {id: $src})-[e:CODE_EDGE]->(b:CodeNode {id: $tgt}) "
            "RETURN a.id, b.id, e.*",
            {"src": source, "tgt": target},
        )
        if result is None:
            return []
        columns = result.get_column_names()
        return [_edge_row_to_edge(columns, r) for r in result.get_all()]

    def all_nodes(self) -> list[GraphNode]:
        result = self._execute("MATCH (n:CodeNode) RETURN n.*")
        if result is None:
            return []
        columns = result.get_column_names()
        return [_row_to_node(columns, r) for r in result.get_all()]

    def all_edges(self) -> list[GraphEdge]:
        result = self._execute(
            "MATCH (a:CodeNode)-[e:CODE_EDGE]->(b:CodeNode) RETURN a.id, b.id, e.*"
        )
        if result is None:
            return []
        columns = result.get_column_names()
        return [_edge_row_to_edge(columns, r) for r in result.get_all()]

    def nodes_by_kind(self, kind: NodeKind) -> list[GraphNode]:
        result = self._execute(
            "MATCH (n:CodeNode) WHERE n.kind = $kind RETURN n.*",
            {"kind": kind.value},
        )
        if result is None:
            return []
        columns = result.get_column_names()
        return [_row_to_node(columns, r) for r in result.get_all()]

    def edges_by_kind(self, kind: EdgeKind) -> list[GraphEdge]:
        result = self._execute(
            "MATCH (a:CodeNode)-[e:CODE_EDGE]->(b:CodeNode) WHERE e.kind = $kind "
            "RETURN a.id, b.id, e.*",
            {"kind": kind.value},
        )
        if result is None:
            return []
        columns = result.get_column_names()
        return [_edge_row_to_edge(columns, r) for r in result.get_all()]

    @property
    def node_count(self) -> int:
        result = self._execute("MATCH (n:CodeNode) RETURN COUNT(n)")
        if result is None:
            return 0
        rows = result.get_all()
        return int(rows[0][0]) if rows else 0

    @property
    def edge_count(self) -> int:
        result = self._execute("MATCH ()-[e:CODE_EDGE]->() RETURN COUNT(e)")
        if result is None:
            return 0
        rows = result.get_all()
        return int(rows[0][0]) if rows else 0

    def neighbors(
        self,
        node_id: str,
        edge_kinds: set[EdgeKind] | None = None,
        direction: str = "both",
    ) -> list[str]:
        result_ids: set[str] = set()

        if direction in ("out", "both"):
            if edge_kinds is not None:
                for ek in edge_kinds:
                    res = self._execute(
                        "MATCH (a:CodeNode {id: $id})-[e:CODE_EDGE]->(b:CodeNode) "
                        "WHERE e.kind = $kind RETURN DISTINCT b.id",
                        {"id": node_id, "kind": ek.value},
                    )
                    if res is not None:
                        for row in res.get_all():
                            result_ids.add(row[0])
            else:
                res = self._execute(
                    "MATCH (a:CodeNode {id: $id})-[:CODE_EDGE]->(b:CodeNode) "
                    "RETURN DISTINCT b.id",
                    {"id": node_id},
                )
                if res is not None:
                    for row in res.get_all():
                        result_ids.add(row[0])

        if direction in ("in", "both"):
            if edge_kinds is not None:
                for ek in edge_kinds:
                    res = self._execute(
                        "MATCH (b:CodeNode)-[e:CODE_EDGE]->(a:CodeNode {id: $id}) "
                        "WHERE e.kind = $kind RETURN DISTINCT b.id",
                        {"id": node_id, "kind": ek.value},
                    )
                    if res is not None:
                        for row in res.get_all():
                            result_ids.add(row[0])
            else:
                res = self._execute(
                    "MATCH (b:CodeNode)-[:CODE_EDGE]->(a:CodeNode {id: $id}) "
                    "RETURN DISTINCT b.id",
                    {"id": node_id},
                )
                if res is not None:
                    for row in res.get_all():
                        result_ids.add(row[0])

        return sorted(result_ids)

    def find_cycles(self, limit: int = 100) -> list[list[str]]:
        """Detect cycles using bounded recursive Cypher match.

        Falls back to loading the graph into NetworkX if the Cypher
        approach fails.
        """
        try:
            result = self._execute(
                "MATCH p = (a:CodeNode)-[e:CODE_EDGE* 2..10]->(a) "
                "RETURN a.id, nodes(p) LIMIT $lim",
                {"lim": limit * 5},  # over-fetch to account for dedup
            )
            if result is None:
                return self._find_cycles_nx_fallback(limit)

            rows = result.get_all()
            if not rows:
                return []

            # Deduplicate: each cycle can appear starting from any node and at
            # varying lengths (due to repeated traversals).  Normalise each
            # cycle to its shortest, canonical rotation.
            seen: set[tuple[str, ...]] = set()
            cycles: list[list[str]] = []
            for row in rows:
                path_nodes: list[str] = [n["id"] for n in row[1]]
                # path_nodes is e.g. [a, b, c, a] — strip the repeated tail
                cycle = path_nodes[:-1]
                if len(cycle) < 2:
                    continue
                # Check the cycle is *simple* (no repeated interior nodes)
                if len(set(cycle)) != len(cycle):
                    continue
                # Canonical form: rotate so the smallest id is first
                min_idx = cycle.index(min(cycle))
                canonical = tuple(cycle[min_idx:] + cycle[:min_idx])
                if canonical in seen:
                    continue
                seen.add(canonical)
                cycles.append(list(canonical))
                if len(cycles) >= limit:
                    break
            return cycles

        except Exception:
            logger.debug("Cypher cycle detection failed, falling back to NetworkX")
            return self._find_cycles_nx_fallback(limit)

    def _find_cycles_nx_fallback(self, limit: int) -> list[list[str]]:
        """Load the graph into a temporary NetworkX digraph and find cycles."""
        from code_intelligence.graph.backends.networkx import NetworkXBackend

        nx_backend = self._to_networkx_backend()
        return nx_backend.find_cycles(limit)

    def shortest_path(self, source: str, target: str) -> list[str] | None:
        """Find the shortest path between two nodes.

        Uses KuzuDB's ``ALL SHORTEST`` recursive match.  Falls back to
        NetworkX if the Cypher query fails.
        """
        try:
            result = self._execute(
                "MATCH (a:CodeNode {id: $src}), (b:CodeNode {id: $tgt}), "
                "p = (a)-[:CODE_EDGE* ALL SHORTEST 1..30]->(b) "
                "RETURN nodes(p) LIMIT 1",
                {"src": source, "tgt": target},
            )
            if result is None:
                return self._shortest_path_nx_fallback(source, target)

            rows = result.get_all()
            if not rows:
                return None
            return [n["id"] for n in rows[0][0]]

        except Exception:
            logger.debug("Cypher shortest-path failed, falling back to NetworkX")
            return self._shortest_path_nx_fallback(source, target)

    def _shortest_path_nx_fallback(self, source: str, target: str) -> list[str] | None:
        from code_intelligence.graph.backends.networkx import NetworkXBackend

        nx_backend = self._to_networkx_backend()
        return nx_backend.shortest_path(source, target)

    def subgraph(self, node_ids: set[str]) -> "NetworkXBackend":
        """Return a NetworkXBackend loaded with the requested subset.

        KuzuDB has no lightweight view abstraction, so we materialise the
        subgraph into an in-memory NetworkX backend.
        """
        from code_intelligence.graph.backends.networkx import NetworkXBackend

        nx_backend = NetworkXBackend()
        for node in self.all_nodes():
            if node.id in node_ids:
                nx_backend.add_node(node)
        for edge in self.all_edges():
            if edge.source in node_ids and edge.target in node_ids:
                nx_backend.add_edge(edge)
        return nx_backend

    def update_node_properties(self, node_id: str, properties: dict[str, Any]) -> None:
        # Merge new properties into existing ones
        node = self.get_node(node_id)
        if node is None:
            logger.warning("update_node_properties: node %s not found", node_id)
            return
        merged = {**node.properties, **properties}
        self._execute(
            "MATCH (n:CodeNode {id: $id}) SET n.properties = $props",
            {"id": node_id, "props": json.dumps(merged)},
        )

    def close(self) -> None:
        """Close the KuzuDB connection."""
        try:
            self._conn.close()
        except Exception:
            logger.debug("Error closing KuzuDB connection", exc_info=True)

    # ------------------------------------------------------------------
    # CypherBackend protocol
    # ------------------------------------------------------------------
    def query_cypher(
        self, cypher: str, params: dict[str, Any] | None = None
    ) -> list[dict[str, Any]]:
        """Execute a raw Cypher query and return results as a list of dicts."""
        result = self._execute(cypher, params)
        if result is None:
            return []
        columns = result.get_column_names()
        return [dict(zip(columns, row)) for row in result.get_all()]

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------
    def _to_networkx_backend(self) -> "NetworkXBackend":
        """Materialise the entire graph into a NetworkXBackend."""
        from code_intelligence.graph.backends.networkx import NetworkXBackend

        nx_backend = NetworkXBackend()
        for node in self.all_nodes():
            nx_backend.add_node(node)
        for edge in self.all_edges():
            nx_backend.add_edge(edge)
        return nx_backend
