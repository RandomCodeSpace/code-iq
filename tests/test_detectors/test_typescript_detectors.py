"""Tests for TypeScript detectors."""

from code_intelligence.detectors.base import DetectorContext
from code_intelligence.models.graph import NodeKind, EdgeKind


def _ctx(content: bytes, file_path: str = "test.ts") -> DetectorContext:
    return DetectorContext(
        file_path=file_path,
        language="typescript",
        content=content,
        module_name="test-module",
    )


class TestNestJSControllerDetector:
    def test_detect_controllers(self, nestjs_controller_source):
        from code_intelligence.detectors.typescript.nestjs_controllers import NestJSControllerDetector
        detector = NestJSControllerDetector()
        result = detector.detect(_ctx(nestjs_controller_source, file_path="user.controller.ts"))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) >= 3  # GET, POST, PUT, DELETE
        classes = [n for n in result.nodes if n.kind == NodeKind.CLASS]
        assert len(classes) >= 1
        assert any("UserController" in c.label for c in classes)


class TestTypeORMEntityDetector:
    def test_detect_entities(self, typeorm_entity_source):
        from code_intelligence.detectors.typescript.typeorm_entities import TypeORMEntityDetector
        detector = TypeORMEntityDetector()
        result = detector.detect(_ctx(typeorm_entity_source, file_path="user.entity.ts"))
        entities = [n for n in result.nodes if n.kind == NodeKind.ENTITY]
        assert len(entities) >= 1
        user_entity = entities[0]
        assert "User" in user_entity.label
        assert user_entity.properties.get("table_name") == "users"
