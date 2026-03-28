"""Graph builder that aggregates detector results and runs cross-file linkers."""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable

from code_intelligence.graph.backend import GraphBackend
from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import GraphEdge, GraphNode, EdgeKind, NodeKind

logger = logging.getLogger(__name__)


@dataclass
class LinkResult:
    """Result returned by a Linker: new nodes and edges to add to the graph."""

    nodes: list[GraphNode] = field(default_factory=list)
    edges: list[GraphEdge] = field(default_factory=list)


@runtime_checkable
class Linker(Protocol):
    """Cross-file relationship inferencer."""

    def link(self, store: GraphStore) -> LinkResult:
        ...


class TopicLinker:
    """Links Kafka/RabbitMQ producers to consumers via shared topic names.

    Scans for TOPIC/QUEUE nodes and matches PRODUCES edges with CONSUMES
    edges on the same topic label to create direct producer-to-consumer edges.
    """

    def link(self, store: GraphStore) -> LinkResult:
        edges: list[GraphEdge] = []

        # Collect topic/queue nodes by label for matching
        topic_nodes = store.nodes_by_kind(NodeKind.TOPIC) + store.nodes_by_kind(
            NodeKind.QUEUE
        )
        topic_ids_by_label: dict[str, list[str]] = {}
        for node in topic_nodes:
            topic_ids_by_label.setdefault(node.label, []).append(node.id)

        # For each topic label, find producers and consumers
        produces_edges = store.edges_by_kind(EdgeKind.PRODUCES)
        consumes_edges = store.edges_by_kind(EdgeKind.CONSUMES)

        # Map topic_id -> list of producer node ids
        producers_by_topic: dict[str, list[str]] = {}
        for edge in produces_edges:
            producers_by_topic.setdefault(edge.target, []).append(edge.source)

        # Map topic_id -> list of consumer node ids
        consumers_by_topic: dict[str, list[str]] = {}
        for edge in consumes_edges:
            consumers_by_topic.setdefault(edge.target, []).append(edge.source)

        # Create CALLS edges from producers to consumers on the same topic
        for label, topic_ids in topic_ids_by_label.items():
            producers: set[str] = set()
            consumers: set[str] = set()
            for tid in topic_ids:
                producers.update(producers_by_topic.get(tid, []))
                consumers.update(consumers_by_topic.get(tid, []))

            for prod in sorted(producers):
                for cons in sorted(consumers):
                    if prod != cons:
                        edges.append(
                            GraphEdge(
                                source=prod,
                                target=cons,
                                kind=EdgeKind.CALLS,
                                label=f"via topic '{label}'",
                                properties={"inferred": True, "topic": label},
                            )
                        )

        if edges:
            logger.debug("TopicLinker created %d edges", len(edges))
        return LinkResult(edges=edges)


class EntityLinker:
    """Links JPA entities to repositories that query them.

    Scans for ENTITY and REPOSITORY nodes and creates QUERIES edges
    from repositories to the entities they manage, matching by naming
    conventions and existing MAPS_TO relationships.
    """

    def link(self, store: GraphStore) -> LinkResult:
        edges: list[GraphEdge] = []

        entities = store.nodes_by_kind(NodeKind.ENTITY)
        repositories = store.nodes_by_kind(NodeKind.REPOSITORY)

        if not entities or not repositories:
            return LinkResult(edges=edges)

        # Build entity lookup by simple name (last part of FQN or label)
        entity_by_name: dict[str, GraphNode] = {}
        for entity in entities:
            # Use label as the simple class name
            entity_by_name[entity.label.lower()] = entity
            if entity.fqn:
                simple = entity.fqn.rsplit(".", 1)[-1]
                entity_by_name[simple.lower()] = entity

        # Check existing QUERIES edges to avoid duplicates
        existing_queries = {
            (e.source, e.target) for e in store.edges_by_kind(EdgeKind.QUERIES)
        }

        for repo in repositories:
            # Try to match repository name to entity name
            # Convention: FooRepository -> Foo entity
            repo_name = repo.label
            for suffix in ("Repository", "Repo", "Dao", "DAO"):
                if repo_name.endswith(suffix):
                    entity_name = repo_name[: -len(suffix)].lower()
                    if entity_name in entity_by_name:
                        entity = entity_by_name[entity_name]
                        if (repo.id, entity.id) not in existing_queries:
                            edges.append(
                                GraphEdge(
                                    source=repo.id,
                                    target=entity.id,
                                    kind=EdgeKind.QUERIES,
                                    label=f"{repo.label} queries {entity.label}",
                                    properties={"inferred": True},
                                )
                            )
                    break

        if edges:
            logger.debug("EntityLinker created %d edges", len(edges))
        return LinkResult(edges=edges)


class ModuleContainmentLinker:
    """Links classes to their owning modules via CONTAINS edges.

    Groups nodes by their ``module`` field and creates MODULE nodes
    with CONTAINS edges pointing to each member node.
    """

    def link(self, store: GraphStore) -> LinkResult:
        edges: list[GraphEdge] = []
        new_nodes: list[GraphNode] = []

        # Collect existing module nodes
        existing_modules = {n.id for n in store.nodes_by_kind(NodeKind.MODULE)}

        # Group nodes by module name
        nodes_by_module: dict[str, list[GraphNode]] = {}
        for node in store.all_nodes():
            if node.module and node.kind != NodeKind.MODULE:
                nodes_by_module.setdefault(node.module, []).append(node)

        # Check existing CONTAINS edges to avoid duplicates
        existing_contains = {
            (e.source, e.target) for e in store.edges_by_kind(EdgeKind.CONTAINS)
        }

        for module_name, members in nodes_by_module.items():
            module_id = f"module:{module_name}"

            # Create module node if it doesn't exist
            if module_id not in existing_modules:
                new_nodes.append(
                    GraphNode(
                        id=module_id,
                        kind=NodeKind.MODULE,
                        label=module_name,
                        fqn=module_name,
                    )
                )

            for member in members:
                if (module_id, member.id) not in existing_contains:
                    edges.append(
                        GraphEdge(
                            source=module_id,
                            target=member.id,
                            kind=EdgeKind.CONTAINS,
                            label=f"{module_name} contains {member.label}",
                            properties={"inferred": True},
                        )
                    )

        if edges:
            logger.debug("ModuleContainmentLinker created %d edges", len(edges))
        return LinkResult(nodes=new_nodes, edges=edges)


class GraphBuilder:
    """Aggregates detector results and runs cross-file linkers to build a graph.

    Edges are buffered and flushed after all nodes are added to ensure
    consistent behavior across all storage backends. Some backends
    (NetworkX, SQLite, KuzuDB) reject edges referencing non-existent
    nodes, so all nodes must be present before edges are inserted.
    """

    def __init__(self, backend: GraphBackend | None = None) -> None:
        self._store = GraphStore(backend=backend)
        self._pending_nodes: list[GraphNode] = []
        self._pending_edges: list[GraphEdge] = []
        self._linkers: list[Linker] = [
            TopicLinker(),
            EntityLinker(),
            ModuleContainmentLinker(),
        ]

    def add_nodes(self, nodes: list[GraphNode]) -> None:
        """Buffer nodes for deferred insertion."""
        self._pending_nodes.extend(nodes)

    def add_edges(self, edges: list[GraphEdge]) -> None:
        """Buffer edges for deferred insertion."""
        self._pending_edges.extend(edges)

    def flush(self) -> None:
        """Insert all buffered nodes then edges into the store.

        Nodes first, then edges — ensures backends that validate node
        existence won't reject valid cross-file edges. Uses bulk insert
        when the backend supports it (e.g. KuzuDB CSV COPY FROM).
        """
        backend = self._store._backend

        # Flush nodes
        if self._pending_nodes:
            if hasattr(backend, "bulk_add_nodes"):
                backend.bulk_add_nodes(self._pending_nodes)
            else:
                for node in self._pending_nodes:
                    self._store.add_node(node)
            self._pending_nodes.clear()

        # Flush edges
        if self._pending_edges:
            if hasattr(backend, "bulk_add_edges"):
                backend.bulk_add_edges(self._pending_edges)
            else:
                for edge in self._pending_edges:
                    self._store.add_edge(edge)
            self._pending_edges.clear()

    def merge_detector_result(self, result: object) -> None:
        """Merge a DetectorResult into the graph.

        Accepts any object with ``nodes`` and ``edges`` attributes
        (duck-typed to avoid circular imports with DetectorResult).
        """
        nodes: list[GraphNode] = getattr(result, "nodes", [])
        edges: list[GraphEdge] = getattr(result, "edges", [])
        self.add_nodes(nodes)
        self.add_edges(edges)  # buffered, not inserted yet

    def run_linkers(self) -> None:
        """Flush pending nodes and edges, then run all registered linkers."""
        # Flush all buffered detector data so linkers see the full graph
        self.flush()

        for linker in self._linkers:
            try:
                result = linker.link(self._store)

                if result.nodes:
                    self.add_nodes(result.nodes)

                if result.edges:
                    self._pending_edges.extend(result.edges)
            except Exception:
                logger.warning(
                    "Linker %s failed",
                    type(linker).__name__,
                    exc_info=True,
                )

        # Flush linker-produced nodes and edges
        self.flush()

    def build(self) -> GraphStore:
        """Return the assembled graph store."""
        # Safety: flush any remaining edges
        if self._pending_edges:
            self.flush()
        return self._store
