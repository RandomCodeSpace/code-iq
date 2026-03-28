"""Tests for MarkdownStructureDetector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.docs.markdown_structure import MarkdownStructureDetector
from code_intelligence.models.graph import EdgeKind, NodeKind


def _ctx(content, path="README.md"):
    return DetectorContext(
        file_path=path,
        language="markdown",
        content=content.encode(),
    )


class TestMarkdownStructureDetector:
    def setup_method(self):
        self.detector = MarkdownStructureDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "markdown_structure"
        assert self.detector.supported_languages == ("markdown",)

    def test_detects_headings_and_links(self):
        md = """\
# My Project

## Installation

See [getting started](docs/getting-started.md) for details.

## API Reference

Check [the docs](https://example.com/api) for external info.
"""
        ctx = _ctx(md)
        r = self.detector.detect(ctx)
        # MODULE node with first H1 as label
        modules = [n for n in r.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        assert modules[0].label == "My Project"
        # CONFIG_KEY nodes for headings
        headings = [n for n in r.nodes if n.kind == NodeKind.CONFIG_KEY]
        heading_labels = {n.label for n in headings}
        assert "My Project" in heading_labels
        assert "Installation" in heading_labels
        assert "API Reference" in heading_labels
        # CONTAINS edges from module to headings
        contains_edges = [e for e in r.edges if e.kind == EdgeKind.CONTAINS]
        assert len(contains_edges) == 3
        # DEPENDS_ON edge for internal link only (external skipped)
        dep_edges = [e for e in r.edges if e.kind == EdgeKind.DEPENDS_ON]
        assert len(dep_edges) == 1
        assert dep_edges[0].target == "docs/getting-started.md"

    def test_no_headings_returns_module_only(self):
        ctx = _ctx("Just some plain text without any headings.\n")
        r = self.detector.detect(ctx)
        modules = [n for n in r.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        # Label falls back to filename
        assert modules[0].label == "README.md"
        headings = [n for n in r.nodes if n.kind == NodeKind.CONFIG_KEY]
        assert headings == []

    def test_determinism(self):
        md = "# Title\n\n## Section A\n\n## Section B\n"
        ctx = _ctx(md)
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]

    def test_returns_detector_result(self):
        ctx = _ctx("")
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)
