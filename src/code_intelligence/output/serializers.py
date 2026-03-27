"""JSON and YAML serializers for the code intelligence graph."""

from __future__ import annotations

import json

import yaml

from code_intelligence.models.graph import CodeGraph


class JsonSerializer:
    """Serialize a :class:`CodeGraph` to JSON."""

    def serialize(self, graph: CodeGraph, pretty: bool = True) -> str:
        """Return the graph as a JSON string.

        Parameters
        ----------
        graph:
            The code graph to serialize.
        pretty:
            When ``True`` (default), emit indented, human-readable JSON.
        """
        data = graph.model_dump(mode="json")
        if pretty:
            return json.dumps(data, indent=2, sort_keys=False)
        return json.dumps(data, sort_keys=False)


class YamlSerializer:
    """Serialize a :class:`CodeGraph` to YAML."""

    def serialize(self, graph: CodeGraph) -> str:
        """Return the graph as a YAML string."""
        data = graph.model_dump(mode="json")
        return yaml.dump(
            data,
            default_flow_style=False,
            sort_keys=False,
            allow_unicode=True,
        )
