"""Tests for YamlStructureDetector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.config.yaml_structure import YamlStructureDetector
from code_intelligence.models.graph import NodeKind


def _ctx(parsed_data, path="config.yml"):
    return DetectorContext(
        file_path=path,
        language="yaml",
        content=b"",
        parsed_data=parsed_data,
    )


class TestYamlStructureDetector:
    def setup_method(self):
        self.detector = YamlStructureDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "yaml_structure"
        assert self.detector.supported_languages == ("yaml",)

    def test_extracts_top_level_keys(self):
        ctx = _ctx({"type": "yaml", "data": {"name": "app", "version": "1.0", "debug": True}})
        r = self.detector.detect(ctx)
        assert any(n.kind == NodeKind.CONFIG_FILE for n in r.nodes)
        key_labels = {n.label for n in r.nodes if n.kind == NodeKind.CONFIG_KEY}
        assert key_labels == {"name", "version", "debug"}

    def test_non_yaml_parsed_data_returns_file_node_only(self):
        ctx = _ctx(None)
        r = self.detector.detect(ctx)
        # parsed_data is None, so detector returns only file node
        assert any(n.kind == NodeKind.CONFIG_FILE for n in r.nodes)
        config_keys = [n for n in r.nodes if n.kind == NodeKind.CONFIG_KEY]
        assert config_keys == []

    def test_determinism(self):
        ctx = _ctx({"type": "yaml", "data": {"alpha": 1, "beta": 2}})
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]

    def test_multi_document_yaml(self):
        ctx = _ctx({
            "type": "yaml_multi",
            "documents": [
                {"name": "doc1", "port": 8080},
                {"name": "doc2", "host": "localhost"},
            ],
        })
        r = self.detector.detect(ctx)
        key_labels = {n.label for n in r.nodes if n.kind == NodeKind.CONFIG_KEY}
        assert "name" in key_labels
        assert "port" in key_labels
        assert "host" in key_labels

    def test_returns_detector_result(self):
        ctx = _ctx({"type": "yaml", "data": {}})
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)
