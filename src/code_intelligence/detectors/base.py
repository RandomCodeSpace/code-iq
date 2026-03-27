"""Detector protocol and context for code intelligence analysis."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol, runtime_checkable

import tree_sitter

from code_intelligence.models.graph import GraphNode, GraphEdge, NodeKind, EdgeKind, SourceLocation


@dataclass
class DetectorResult:
    """Result of running a detector on a file."""

    nodes: list[GraphNode] = field(default_factory=list)
    edges: list[GraphEdge] = field(default_factory=list)


@dataclass
class DetectorContext:
    """Context passed to each detector for analysis."""

    file_path: str  # Relative to repo root
    language: str
    content: bytes
    tree: tree_sitter.Tree | None = None
    parsed_data: Any = None  # For structured files (dict, ElementTree)
    module_name: str | None = None  # Owning module name


@runtime_checkable
class Detector(Protocol):
    """Protocol that all detectors must implement."""

    name: str
    supported_languages: tuple[str, ...]

    def detect(self, ctx: DetectorContext) -> DetectorResult: ...
