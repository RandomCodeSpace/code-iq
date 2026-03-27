"""Tests for Python detectors."""

from code_intelligence.detectors.base import DetectorContext
from code_intelligence.models.graph import NodeKind, EdgeKind


def _ctx(content: bytes, file_path: str = "test.py") -> DetectorContext:
    return DetectorContext(
        file_path=file_path,
        language="python",
        content=content,
        module_name="test-module",
    )


class TestFastAPIRouteDetector:
    def test_detect_routes(self, fastapi_source):
        from code_intelligence.detectors.python.fastapi_routes import FastAPIRouteDetector
        detector = FastAPIRouteDetector()
        result = detector.detect(_ctx(fastapi_source, file_path="app.py"))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) >= 2
        methods = {n.properties.get("http_method") for n in endpoints}
        assert "GET" in methods


class TestSQLAlchemyModelDetector:
    def test_detect_models(self, sqlalchemy_source):
        from code_intelligence.detectors.python.sqlalchemy_models import SQLAlchemyModelDetector
        detector = SQLAlchemyModelDetector()
        result = detector.detect(_ctx(sqlalchemy_source, file_path="models.py"))
        entities = [n for n in result.nodes if n.kind == NodeKind.ENTITY]
        assert len(entities) >= 2  # User, UserProfile, Order
        # Check relationships
        maps_to_edges = [e for e in result.edges if e.kind == EdgeKind.MAPS_TO]
        assert len(maps_to_edges) >= 1
