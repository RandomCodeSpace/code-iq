"""Composable query builder for the code intelligence graph."""

from __future__ import annotations

import fnmatch
from collections.abc import Callable
from dataclasses import dataclass, field

from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
)


@dataclass(frozen=True)
class _FocusSpec:
    """Describes a neighbourhood-focus operation."""

    node_id: str
    hops: int
    direction: str


@dataclass(frozen=True)
class GraphQuery:
    """Immutable, composable query builder over a :class:`GraphStore`.

    Every filter method returns a **new** ``GraphQuery``; the original
    is never mutated.  Call :meth:`execute` to materialise the result
    as a new :class:`GraphStore`.
    """

    _store: GraphStore
    _module_filters: tuple[list[str], ...] = ()
    _node_kind_filters: tuple[list[NodeKind], ...] = ()
    _edge_kind_filters: tuple[list[EdgeKind], ...] = ()
    _path_filters: tuple[str, ...] = ()
    _annotation_filters: tuple[str, ...] = ()
    _focus_specs: tuple[_FocusSpec, ...] = ()
    _node_predicates: tuple[Callable[[GraphNode], bool], ...] = ()
    _edge_predicates: tuple[Callable[[GraphEdge], bool], ...] = ()

    # -- convenience constructor ------------------------------------

    def __init__(self, store: GraphStore) -> None:  # noqa: D107
        object.__setattr__(self, "_store", store)
        object.__setattr__(self, "_module_filters", ())
        object.__setattr__(self, "_node_kind_filters", ())
        object.__setattr__(self, "_edge_kind_filters", ())
        object.__setattr__(self, "_path_filters", ())
        object.__setattr__(self, "_annotation_filters", ())
        object.__setattr__(self, "_focus_specs", ())
        object.__setattr__(self, "_node_predicates", ())
        object.__setattr__(self, "_edge_predicates", ())

    def _copy(self, **overrides: object) -> GraphQuery:
        """Return a shallow copy with selected field overrides."""
        new = object.__new__(GraphQuery)
        for attr in (
            "_store",
            "_module_filters",
            "_node_kind_filters",
            "_edge_kind_filters",
            "_path_filters",
            "_annotation_filters",
            "_focus_specs",
            "_node_predicates",
            "_edge_predicates",
        ):
            object.__setattr__(new, attr, overrides.get(attr, getattr(self, attr)))
        return new

    # -- filter methods (each returns a new GraphQuery) -------------

    def filter_modules(self, modules: list[str]) -> GraphQuery:
        """Keep only nodes belonging to one of the listed modules."""
        return self._copy(_module_filters=self._module_filters + (modules,))

    def filter_node_kinds(self, kinds: list[NodeKind]) -> GraphQuery:
        """Keep only nodes whose kind is in *kinds*."""
        return self._copy(_node_kind_filters=self._node_kind_filters + (kinds,))

    def filter_edge_kinds(self, kinds: list[EdgeKind]) -> GraphQuery:
        """Keep only edges whose kind is in *kinds*."""
        return self._copy(_edge_kind_filters=self._edge_kind_filters + (kinds,))

    def filter_path(self, glob_pattern: str) -> GraphQuery:
        """Keep only nodes whose source file path matches *glob_pattern*."""
        return self._copy(_path_filters=self._path_filters + (glob_pattern,))

    def filter_annotation(self, annotation: str) -> GraphQuery:
        """Keep only nodes that carry *annotation*."""
        return self._copy(_annotation_filters=self._annotation_filters + (annotation,))

    def focus(self, node_id: str, hops: int = 2, direction: str = "both") -> GraphQuery:
        """Restrict to the *hops*-neighbourhood around *node_id*."""
        spec = _FocusSpec(node_id=node_id, hops=hops, direction=direction)
        return self._copy(_focus_specs=self._focus_specs + (spec,))

    # -- semantic queries -------------------------------------------

    def consumers_of(self, target_id: str) -> GraphQuery:
        """Find nodes that consume from *target_id*."""
        def _pred(edge: GraphEdge) -> bool:
            return edge.target == target_id and edge.kind in {
                EdgeKind.CONSUMES,
                EdgeKind.LISTENS,
            }
        return self._copy(_edge_predicates=self._edge_predicates + (_pred,))

    def producers_of(self, target_id: str) -> GraphQuery:
        """Find nodes that produce to *target_id*."""
        def _pred(edge: GraphEdge) -> bool:
            return edge.target == target_id and edge.kind in {
                EdgeKind.PRODUCES,
                EdgeKind.PUBLISHES,
            }
        return self._copy(_edge_predicates=self._edge_predicates + (_pred,))

    def callers_of(self, target_id: str) -> GraphQuery:
        """Find nodes that call *target_id*."""
        def _pred(edge: GraphEdge) -> bool:
            return edge.target == target_id and edge.kind == EdgeKind.CALLS
        return self._copy(_edge_predicates=self._edge_predicates + (_pred,))

    def dependencies_of(self, module_id: str) -> GraphQuery:
        """Find modules that *module_id* depends on."""
        def _pred(edge: GraphEdge) -> bool:
            return edge.source == module_id and edge.kind in {
                EdgeKind.DEPENDS_ON,
                EdgeKind.IMPORTS,
                EdgeKind.CALLS,
                EdgeKind.INJECTS,
            }
        return self._copy(_edge_predicates=self._edge_predicates + (_pred,))

    def dependents_of(self, module_id: str) -> GraphQuery:
        """Find modules that depend on *module_id*."""
        def _pred(edge: GraphEdge) -> bool:
            return edge.target == module_id and edge.kind in {
                EdgeKind.DEPENDS_ON,
                EdgeKind.IMPORTS,
                EdgeKind.CALLS,
                EdgeKind.INJECTS,
            }
        return self._copy(_edge_predicates=self._edge_predicates + (_pred,))

    # -- execution --------------------------------------------------

    def execute(self) -> GraphStore:
        """Apply all accumulated filters and return a new :class:`GraphStore`."""
        store = self._store

        # 1. Apply focus specs first (they restrict the working set)
        if self._focus_specs:
            focused_ids: set[str] = set()
            for spec in self._focus_specs:
                ego_store = store.ego(spec.node_id, spec.hops)
                if spec.direction != "both":
                    # For directional focus, do a BFS in the specified direction
                    focused_ids.update(
                        n.id for n in ego_store.all_nodes()
                    )
                else:
                    focused_ids.update(
                        n.id for n in ego_store.all_nodes()
                    )
            store = store.subgraph(focused_ids)

        # 2. Build composite node filter
        def _node_ok(node: GraphNode) -> bool:
            # Module filters (OR within a single call, AND across calls)
            for mod_list in self._module_filters:
                if node.module not in mod_list and node.id not in mod_list:
                    return False

            # Node-kind filters
            for kind_list in self._node_kind_filters:
                if node.kind not in kind_list:
                    return False

            # Path filters (any pattern match)
            if self._path_filters:
                loc = node.location
                if loc is None:
                    return False
                if not any(fnmatch.fnmatch(loc.file_path, p) for p in self._path_filters):
                    return False

            # Annotation filters (all must be present)
            for ann in self._annotation_filters:
                if ann not in node.annotations:
                    return False

            # Custom node predicates
            for pred in self._node_predicates:
                if not pred(node):
                    return False

            return True

        # 3. Build composite edge filter
        def _edge_ok(edge: GraphEdge) -> bool:
            for kind_list in self._edge_kind_filters:
                if edge.kind not in kind_list:
                    return False
            # Edge predicates are OR-combined: if any are set, at least
            # one must match.  This allows semantic queries to union.
            if self._edge_predicates:
                if not any(pred(edge) for pred in self._edge_predicates):
                    return False
            return True

        # When we have edge predicates but no node filters, we should
        # also include nodes referenced by matching edges.
        if self._edge_predicates and not (
            self._module_filters
            or self._node_kind_filters
            or self._path_filters
            or self._annotation_filters
            or self._node_predicates
        ):
            # Collect node IDs from matching edges
            keep_ids: set[str] = set()
            for edge in store.all_edges():
                if _edge_ok(edge):
                    keep_ids.add(edge.source)
                    keep_ids.add(edge.target)
            store = store.subgraph(keep_ids)
            # Re-filter edges only
            return store.filter(edge_filter=_edge_ok)

        return store.filter(node_filter=_node_ok, edge_filter=_edge_ok)
