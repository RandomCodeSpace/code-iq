"""Graph backend factory."""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from code_intelligence.graph.backend import GraphBackend


def create_backend(backend_name: str = "networkx", **kwargs) -> GraphBackend:
    """Create a graph backend by name."""
    if backend_name == "networkx":
        from code_intelligence.graph.backends.networkx import NetworkXBackend
        return NetworkXBackend()
    elif backend_name == "kuzu":
        from code_intelligence.graph.backends.kuzu import KuzuBackend
        return KuzuBackend(db_path=kwargs.get("path", ".code-intelligence/graph.kuzu"))
    elif backend_name == "sqlite":
        from code_intelligence.graph.backends.sqlite_backend import SqliteGraphBackend
        return SqliteGraphBackend(db_path=kwargs.get("path", ".code-intelligence/graph.db"))
    else:
        raise ValueError(f"Unknown graph backend: {backend_name}")
