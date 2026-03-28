"""Multi-level view transformations for the code intelligence graph."""

from __future__ import annotations

from collections import defaultdict

from code_intelligence.config import DomainMapping
from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
)


class ArchitectView:
    """Collapses detail nodes into module-level nodes.

    Method-level calls, imports, and injections are rolled up into
    module-level ``depends_on`` edges.  Messaging edges (produces,
    consumes, publishes, listens) preserve their identity so that
    data-flow remains visible at the architecture level.
    """

    EDGE_ROLLUP: dict[EdgeKind, EdgeKind] = {
        EdgeKind.CALLS: EdgeKind.DEPENDS_ON,
        EdgeKind.IMPORTS: EdgeKind.DEPENDS_ON,
        EdgeKind.INJECTS: EdgeKind.DEPENDS_ON,
        EdgeKind.EXTENDS: EdgeKind.DEPENDS_ON,
        EdgeKind.IMPLEMENTS: EdgeKind.DEPENDS_ON,
        EdgeKind.PRODUCES: EdgeKind.PRODUCES,
        EdgeKind.CONSUMES: EdgeKind.CONSUMES,
        EdgeKind.PUBLISHES: EdgeKind.PUBLISHES,
        EdgeKind.LISTENS: EdgeKind.LISTENS,
        EdgeKind.INVOKES_RMI: EdgeKind.INVOKES_RMI,
        EdgeKind.EXPORTS_RMI: EdgeKind.EXPORTS_RMI,
        EdgeKind.DEPENDS_ON: EdgeKind.DEPENDS_ON,
    }

    def _resolve_module(self, node: GraphNode) -> str | None:
        """Return the module id that owns *node*.

        MODULE nodes own themselves; every other node uses its
        ``module`` property.
        """
        if node.kind == NodeKind.MODULE:
            return node.id
        return node.module

    def roll_up(self, store: GraphStore) -> GraphStore:
        """Collapse all non-module nodes into their owning module.

        Returns a new :class:`GraphStore` containing only MODULE nodes
        with rolled-up edges and summary properties.
        """
        new_store = GraphStore()

        # --- 1. Collect module nodes and build summary counters --------
        module_nodes: dict[str, GraphNode] = {}
        summary: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))

        for node in store.all_nodes():
            mod_id = self._resolve_module(node)
            if mod_id is None:
                continue
            # Ensure we have a MODULE node entry
            if mod_id not in module_nodes:
                existing = store.get_node(mod_id)
                if existing is not None and existing.kind == NodeKind.MODULE:
                    module_nodes[mod_id] = existing
                else:
                    # Synthesise a module node when one is not present
                    module_nodes[mod_id] = GraphNode(
                        id=mod_id,
                        kind=NodeKind.MODULE,
                        label=mod_id,
                    )
            # Count child node kinds for summary
            summary[mod_id][node.kind.value] += 1

        # Add module nodes with summary properties
        for mod_id, mod_node in module_nodes.items():
            counts = dict(summary.get(mod_id, {}))
            props = dict(mod_node.properties)
            props["endpoint_count"] = counts.get(NodeKind.ENDPOINT.value, 0)
            props["entity_count"] = counts.get(NodeKind.ENTITY.value, 0)
            props["class_count"] = counts.get(NodeKind.CLASS.value, 0)
            props["method_count"] = counts.get(NodeKind.METHOD.value, 0)
            enriched = mod_node.model_copy(update={"properties": props})
            new_store.add_node(enriched)

        # --- 2. Roll up edges -----------------------------------------
        # Pre-build node_id -> module_id mapping via public API
        module_map: dict[str, str | None] = {}
        for node in store.all_nodes():
            module_map[node.id] = node.module

        # (source_module, target_module, rolled_kind) -> weight
        edge_weights: dict[tuple[str, str, EdgeKind], int] = defaultdict(int)

        for edge in store.all_edges():
            rolled_kind = self.EDGE_ROLLUP.get(edge.kind)
            if rolled_kind is None:
                continue

            src_mod = module_map.get(edge.source)
            tgt_mod = module_map.get(edge.target)
            if src_mod is None or tgt_mod is None:
                continue
            # Skip self-loops at module level
            if src_mod == tgt_mod:
                continue
            # Ensure both modules exist in new store
            if src_mod not in module_nodes or tgt_mod not in module_nodes:
                continue

            edge_weights[(src_mod, tgt_mod, rolled_kind)] += 1

        # --- 3. Create merged edges -----------------------------------
        for (src, tgt, kind), weight in edge_weights.items():
            props: dict[str, object] = {"weight": weight}
            new_store.add_edge(
                GraphEdge(
                    source=src,
                    target=tgt,
                    kind=kind,
                    label=f"{kind.value} (x{weight})" if weight > 1 else kind.value,
                    properties=props,
                )
            )

        return new_store


class DomainView:
    """Collapses modules into business domain groups.

    Uses :class:`DomainMapping` definitions from the project
    configuration to merge module-level nodes into domain-level
    aggregates.
    """

    def __init__(self, domain_mappings: list[DomainMapping]) -> None:
        self._mappings = domain_mappings
        # Pre-build module -> domain lookup
        self._module_to_domain: dict[str, str] = {}
        for mapping in domain_mappings:
            for module in mapping.modules:
                self._module_to_domain[module] = mapping.name

    def _resolve_domain(self, module_id: str) -> str | None:
        """Return the domain name for a module, or ``None`` if unmapped."""
        # Exact match first
        if module_id in self._module_to_domain:
            return self._module_to_domain[module_id]
        # Prefix match (e.g. ``com.example.orders`` matches ``com.example.orders.service``)
        for mod_prefix, domain in self._module_to_domain.items():
            if module_id.startswith(mod_prefix + ".") or module_id.startswith(mod_prefix + "/"):
                return domain
        return None

    def roll_up(self, store: GraphStore) -> GraphStore:
        """Collapse module-level nodes into domain-level aggregates.

        Parameters
        ----------
        store:
            A :class:`GraphStore` — ideally already at module level
            (i.e. output of :meth:`ArchitectView.roll_up`).

        Returns
        -------
        GraphStore
            A new store with one node per domain and rolled-up edges.
        """
        new_store = GraphStore()

        # --- 1. Build domain nodes ------------------------------------
        domain_modules: dict[str, list[str]] = defaultdict(list)

        for node in store.all_nodes():
            domain = self._resolve_domain(node.id)
            if domain is None:
                # Keep unmapped nodes as-is
                new_store.add_node(node)
                continue
            domain_modules[domain].append(node.id)

        for domain_name, mod_ids in domain_modules.items():
            props: dict[str, object] = {
                "module_count": len(mod_ids),
                "modules": mod_ids,
            }
            new_store.add_node(
                GraphNode(
                    id=f"domain:{domain_name}",
                    kind=NodeKind.MODULE,
                    label=domain_name,
                    properties=props,
                )
            )

        # --- 2. Roll up edges -----------------------------------------
        edge_weights: dict[tuple[str, str, EdgeKind], int] = defaultdict(int)

        for edge in store.all_edges():
            src_domain = self._resolve_domain(edge.source)
            tgt_domain = self._resolve_domain(edge.target)

            src_id = f"domain:{src_domain}" if src_domain else edge.source
            tgt_id = f"domain:{tgt_domain}" if tgt_domain else edge.target

            if src_id == tgt_id:
                continue

            edge_weights[(src_id, tgt_id, edge.kind)] += 1

        for (src, tgt, kind), weight in edge_weights.items():
            props = {"weight": weight}
            new_store.add_edge(
                GraphEdge(
                    source=src,
                    target=tgt,
                    kind=kind,
                    label=f"{kind.value} (x{weight})" if weight > 1 else kind.value,
                    properties=props,
                )
            )

        return new_store
