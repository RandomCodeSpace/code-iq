"""Tests for output/dot.py — Graphviz DOT renderer."""

from __future__ import annotations

from osscodeiq.graph.store import GraphStore
from osscodeiq.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind
from osscodeiq.output.dot import DotRenderer, _quote, _sanitize_id


def _simple_store() -> GraphStore:
    store = GraphStore()
    store.add_node(GraphNode(id="cls:a", kind=NodeKind.CLASS, label="ClassA", module="mod.x"))
    store.add_node(GraphNode(id="cls:b", kind=NodeKind.CLASS, label="ClassB", module="mod.y"))
    store.add_edge(GraphEdge(source="cls:a", target="cls:b", kind=EdgeKind.CALLS))
    return store


# ---------- Helper functions ----------


class TestSanitizeId:
    def test_simple(self):
        assert _sanitize_id("abc") == "abc"

    def test_colons_replaced(self):
        assert _sanitize_id("cls:a") == "cls_a"

    def test_slashes_replaced(self):
        assert _sanitize_id("src/foo/bar.py") == "src_foo_bar_py"

    def test_dots_replaced(self):
        assert _sanitize_id("com.example") == "com_example"


class TestQuote:
    def test_plain_text(self):
        assert _quote("hello") == "hello"

    def test_double_quotes_escaped(self):
        assert _quote('say "hi"') == 'say \\"hi\\"'

    def test_backslash_escaped(self):
        assert _quote("a\\b") == "a\\\\b"


# ---------- DotRenderer ----------


class TestDotRenderer:
    def test_basic_render(self):
        store = _simple_store()
        renderer = DotRenderer()
        dot = renderer.render(store)
        assert "digraph" in dot
        assert "ClassA" in dot
        assert "ClassB" in dot
        assert "->" in dot

    def test_render_contains_node_styles(self):
        store = _simple_store()
        renderer = DotRenderer()
        dot = renderer.render(store)
        assert "fillcolor" in dot
        assert "shape" in dot

    def test_render_edge_label(self):
        store = _simple_store()
        renderer = DotRenderer()
        dot = renderer.render(store)
        assert "calls" in dot

    def test_rankdir_default(self):
        store = _simple_store()
        renderer = DotRenderer()
        dot = renderer.render(store)
        assert "rankdir=LR" in dot

    def test_rankdir_custom(self):
        store = _simple_store()
        renderer = DotRenderer(rankdir="TB")
        dot = renderer.render(store)
        assert "rankdir=TB" in dot

    def test_fontname(self):
        store = _simple_store()
        renderer = DotRenderer(fontname="Courier")
        dot = renderer.render(store)
        assert "Courier" in dot

    def test_cluster_by_module(self):
        store = _simple_store()
        renderer = DotRenderer(cluster_by="module")
        dot = renderer.render(store)
        assert "subgraph" in dot
        assert "mod.x" in dot or "mod_x" in dot

    def test_cluster_by_node_type(self):
        store = GraphStore()
        store.add_node(GraphNode(id="ep:1", kind=NodeKind.ENDPOINT, label="GET /api"))
        store.add_node(GraphNode(id="cls:1", kind=NodeKind.CLASS, label="Ctrl"))
        renderer = DotRenderer(cluster_by="node-type")
        dot = renderer.render(store)
        assert "subgraph" in dot

    def test_empty_store(self):
        store = GraphStore()
        renderer = DotRenderer()
        dot = renderer.render(store)
        assert "digraph" in dot
        assert dot.strip().endswith("}")

    def test_various_node_kinds(self):
        store = GraphStore()
        for kind in [NodeKind.ENDPOINT, NodeKind.ENTITY, NodeKind.TOPIC, NodeKind.MODULE]:
            store.add_node(GraphNode(id=f"n:{kind.value}", kind=kind, label=kind.value))
        renderer = DotRenderer()
        dot = renderer.render(store)
        assert "endpoint" in dot
        assert "entity" in dot

    def test_various_edge_kinds(self):
        store = GraphStore()
        store.add_node(GraphNode(id="a", kind=NodeKind.CLASS, label="A"))
        store.add_node(GraphNode(id="b", kind=NodeKind.CLASS, label="B"))
        for ek in [EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS, EdgeKind.PRODUCES]:
            store.add_edge(GraphEdge(source="a", target="b", kind=ek))
        renderer = DotRenderer()
        dot = renderer.render(store)
        assert "extends" in dot
        assert "implements" in dot
        assert "produces" in dot

    def test_special_chars_in_label(self):
        store = GraphStore()
        store.add_node(GraphNode(id="x", kind=NodeKind.CLASS, label='Class "Special"'))
        renderer = DotRenderer()
        dot = renderer.render(store)
        assert '\\"Special\\"' in dot

    def test_determinism(self):
        """Render twice, get same output."""
        dot1 = DotRenderer().render(_simple_store())
        dot2 = DotRenderer().render(_simple_store())
        assert dot1 == dot2
