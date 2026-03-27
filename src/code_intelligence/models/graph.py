"""Core graph data models for code intelligence."""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class NodeKind(str, Enum):
    """Types of nodes in the code intelligence graph."""

    MODULE = "module"
    PACKAGE = "package"
    CLASS = "class"
    METHOD = "method"
    ENDPOINT = "endpoint"
    ENTITY = "entity"
    REPOSITORY = "repository"
    QUERY = "query"
    MIGRATION = "migration"
    TOPIC = "topic"
    QUEUE = "queue"
    EVENT = "event"
    RMI_INTERFACE = "rmi_interface"
    CONFIG_FILE = "config_file"
    CONFIG_KEY = "config_key"
    WEBSOCKET_ENDPOINT = "websocket_endpoint"


class EdgeKind(str, Enum):
    """Types of edges (relationships) in the code intelligence graph."""

    DEPENDS_ON = "depends_on"
    IMPORTS = "imports"
    EXTENDS = "extends"
    IMPLEMENTS = "implements"
    CALLS = "calls"
    INJECTS = "injects"
    EXPOSES = "exposes"
    QUERIES = "queries"
    MAPS_TO = "maps_to"
    PRODUCES = "produces"
    CONSUMES = "consumes"
    PUBLISHES = "publishes"
    LISTENS = "listens"
    INVOKES_RMI = "invokes_rmi"
    EXPORTS_RMI = "exports_rmi"
    READS_CONFIG = "reads_config"
    MIGRATES = "migrates"
    CONTAINS = "contains"


class SourceLocation(BaseModel):
    """Source code location reference."""

    file_path: str
    line_start: int | None = None
    line_end: int | None = None


class GraphNode(BaseModel):
    """A node in the code intelligence graph."""

    id: str
    kind: NodeKind
    label: str
    fqn: str | None = None
    module: str | None = None
    location: SourceLocation | None = None
    annotations: list[str] = Field(default_factory=list)
    properties: dict[str, Any] = Field(default_factory=dict)


class GraphEdge(BaseModel):
    """An edge (relationship) in the code intelligence graph."""

    source: str
    target: str
    kind: EdgeKind
    label: str | None = None
    properties: dict[str, Any] = Field(default_factory=dict)


class CodeGraph(BaseModel):
    """Top-level serializable graph container."""

    version: str = "1.0.0"
    metadata: dict[str, Any] = Field(default_factory=dict)
    nodes: list[GraphNode] = Field(default_factory=list)
    edges: list[GraphEdge] = Field(default_factory=list)
