"""Deterministic layer classifier for code intelligence graph nodes."""

from __future__ import annotations

import re
from typing import Any, Sequence

from code_intelligence.models.graph import GraphNode, NodeKind

_FRONTEND_NODE_KINDS = {NodeKind.COMPONENT, NodeKind.HOOK}
_BACKEND_NODE_KINDS = {NodeKind.GUARD, NodeKind.MIDDLEWARE, NodeKind.ENDPOINT, NodeKind.REPOSITORY, NodeKind.DATABASE_CONNECTION, NodeKind.QUERY}
_INFRA_NODE_KINDS = {NodeKind.INFRA_RESOURCE, NodeKind.AZURE_RESOURCE, NodeKind.AZURE_FUNCTION}
_INFRA_LANGUAGES = {"terraform", "bicep", "dockerfile"}
_SHARED_NODE_KINDS = {NodeKind.CONFIG_FILE, NodeKind.CONFIG_KEY, NodeKind.CONFIG_DEFINITION}

_FRONTEND_PATH_RE = re.compile(
    r"(?:^|/)(?:src/)?(?:components|pages|views|app/ui|public)/",
)
_BACKEND_PATH_RE = re.compile(
    r"(?:^|/)(?:src/)?(?:server|api|controllers|services|routes|handlers)/",
)
_FRONTEND_EXT_RE = re.compile(r"\.(?:tsx|jsx)$")

_FRONTEND_FRAMEWORKS = {"react", "vue", "angular", "svelte", "nextjs"}
_BACKEND_FRAMEWORKS = {"express", "nestjs", "flask", "django", "fastapi", "spring"}


class LayerClassifier:
    """Assigns a deterministic 'layer' property to every graph node.
    Rules are evaluated in order; first match wins.
    """

    def classify(self, nodes: Sequence[GraphNode]) -> None:
        for node in nodes:
            node.properties["layer"] = self._classify_one(node)

    def classify_store(self, store: Any) -> None:
        """Classify nodes in a GraphStore, updating properties via public API."""
        for node in store.all_nodes():
            layer = self._classify_one(node)
            store.update_node_properties(node.id, {"layer": layer})

    def _classify_one(self, node: GraphNode) -> str:
        if node.kind in _FRONTEND_NODE_KINDS:
            return "frontend"
        if node.kind in _BACKEND_NODE_KINDS:
            return "backend"
        if node.kind in _INFRA_NODE_KINDS:
            return "infra"
        lang = node.properties.get("language", "")
        if lang in _INFRA_LANGUAGES:
            return "infra"
        file_path = ""
        if node.location:
            file_path = node.location.file_path
        if _FRONTEND_EXT_RE.search(file_path):
            return "frontend"
        if _FRONTEND_PATH_RE.search(file_path):
            return "frontend"
        if _BACKEND_PATH_RE.search(file_path):
            return "backend"
        fw = node.properties.get("framework", "")
        if fw in _FRONTEND_FRAMEWORKS:
            return "frontend"
        if fw in _BACKEND_FRAMEWORKS:
            return "backend"
        if node.kind in _SHARED_NODE_KINDS:
            return "shared"
        return "unknown"
