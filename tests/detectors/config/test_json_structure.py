"""Tests for JsonStructureDetector."""

import json

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.config.json_structure import JsonStructureDetector
from code_intelligence.models.graph import NodeKind


def _ctx(content, path="test.json"):
    return DetectorContext(
        file_path=path,
        language="json",
        content=content.encode(),
        parsed_data={"type": "json", "file": path, "data": json.loads(content)},
    )


class TestJsonStructureDetector:
    def setup_method(self):
        self.detector = JsonStructureDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "json_structure"
        assert self.detector.supported_languages == ("json",)

    def test_extracts_top_level_keys(self):
        ctx = _ctx('{"name": "test", "version": "1.0", "scripts": {}}')
        r = self.detector.detect(ctx)
        assert any(n.kind == NodeKind.CONFIG_FILE for n in r.nodes)
        assert any(n.kind == NodeKind.CONFIG_KEY for n in r.nodes)
        key_labels = {n.label for n in r.nodes if n.kind == NodeKind.CONFIG_KEY}
        assert key_labels == {"name", "version", "scripts"}

    def test_empty_object_returns_file_node(self):
        ctx = _ctx("{}")
        r = self.detector.detect(ctx)
        assert any(n.kind == NodeKind.CONFIG_FILE for n in r.nodes)
        config_keys = [n for n in r.nodes if n.kind == NodeKind.CONFIG_KEY]
        assert config_keys == []

    def test_determinism(self):
        ctx = _ctx('{"a": 1, "b": 2}')
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]

    def test_returns_detector_result(self):
        ctx = _ctx("{}")
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)
