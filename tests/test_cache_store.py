"""Tests for CacheStore SQLite-backed cache."""

from osscodeiq.cache.store import CacheStore
from osscodeiq.models.graph import GraphNode, GraphEdge, NodeKind, EdgeKind, SourceLocation


def _make_store(tmp_path):
    db = tmp_path / "cache.db"
    return CacheStore(db)


def _sample_nodes():
    return [
        GraphNode(id="n1", kind=NodeKind.CLASS, label="Foo", module="test",
                  location=SourceLocation(file_path="test.py")),
        GraphNode(id="n2", kind=NodeKind.METHOD, label="bar", module="test",
                  location=SourceLocation(file_path="test.py")),
    ]


def _sample_edges():
    return [
        GraphEdge(source="n1", target="n2", kind=EdgeKind.CONTAINS, label="Foo contains bar"),
    ]


class TestCacheStore:
    def test_store_and_load(self, tmp_path):
        store = _make_store(tmp_path)
        nodes = _sample_nodes()
        edges = _sample_edges()
        store.store_results("hash1", "test.py", "python", nodes, edges)
        assert store.is_cached("hash1")
        loaded_nodes, loaded_edges = store.load_cached_results("hash1")
        assert len(loaded_nodes) == 2
        assert len(loaded_edges) == 1
        assert loaded_nodes[0].id == "n1"
        assert loaded_edges[0].source == "n1"
        store.close()

    def test_is_cached_false(self, tmp_path):
        store = _make_store(tmp_path)
        assert not store.is_cached("nonexistent")
        store.close()

    def test_remove_file(self, tmp_path):
        store = _make_store(tmp_path)
        store.store_results("hash1", "test.py", "python", _sample_nodes(), _sample_edges())
        assert store.is_cached("hash1")
        store.remove_file("hash1")
        assert not store.is_cached("hash1")
        loaded_nodes, loaded_edges = store.load_cached_results("hash1")
        assert len(loaded_nodes) == 0
        assert len(loaded_edges) == 0
        store.close()

    def test_remove_by_path(self, tmp_path):
        store = _make_store(tmp_path)
        store.store_results("hash1", "test.py", "python", _sample_nodes(), _sample_edges())
        store.store_results("hash2", "test.py", "python", _sample_nodes(), _sample_edges())
        store.remove_by_path("test.py")
        assert not store.is_cached("hash1")
        assert not store.is_cached("hash2")
        store.close()

    def test_record_run_and_get_last_commit(self, tmp_path):
        store = _make_store(tmp_path)
        assert store.get_last_commit() is None
        store.record_run("abc123", 10)
        assert store.get_last_commit() == "abc123"
        store.record_run("def456", 20)
        assert store.get_last_commit() == "def456"
        store.close()

    def test_load_full_graph(self, tmp_path):
        store = _make_store(tmp_path)
        store.store_results("hash1", "test.py", "python", _sample_nodes(), _sample_edges())
        graph = store.load_full_graph()
        assert graph.node_count >= 2
        store.close()

    def test_get_stats_empty(self, tmp_path):
        store = _make_store(tmp_path)
        stats = store.get_stats()
        assert stats["cached_files"] == 0
        assert stats["cached_nodes"] == 0
        assert stats["cached_edges"] == 0
        assert stats["total_runs"] == 0
        assert stats["last_run"] is None
        store.close()

    def test_get_stats_with_data(self, tmp_path):
        store = _make_store(tmp_path)
        store.store_results("hash1", "test.py", "python", _sample_nodes(), _sample_edges())
        store.record_run("abc123", 5)
        stats = store.get_stats()
        assert stats["cached_files"] == 1
        assert stats["cached_nodes"] == 2
        assert stats["cached_edges"] == 1
        assert stats["total_runs"] == 1
        assert stats["last_run"]["commit_sha"] == "abc123"
        assert stats["last_run"]["file_count"] == 5
        store.close()

    def test_store_idempotent_replace(self, tmp_path):
        store = _make_store(tmp_path)
        store.store_results("hash1", "test.py", "python", _sample_nodes(), _sample_edges())
        # Re-store same hash replaces old data
        new_nodes = [GraphNode(id="n3", kind=NodeKind.CLASS, label="Baz")]
        store.store_results("hash1", "test.py", "python", new_nodes, [])
        loaded_nodes, loaded_edges = store.load_cached_results("hash1")
        assert len(loaded_nodes) == 1
        assert loaded_nodes[0].id == "n3"
        assert len(loaded_edges) == 0
        store.close()

    def test_determinism(self, tmp_path):
        """Two identical stores produce identical results."""
        for run in range(2):
            store = CacheStore(tmp_path / f"cache_{run}.db")
            store.store_results("hash1", "test.py", "python", _sample_nodes(), _sample_edges())
            nodes, edges = store.load_cached_results("hash1")
            if run == 0:
                first_nodes = [n.id for n in nodes]
                first_edges = [(e.source, e.target) for e in edges]
            else:
                assert [n.id for n in nodes] == first_nodes
                assert [(e.source, e.target) for e in edges] == first_edges
            store.close()
