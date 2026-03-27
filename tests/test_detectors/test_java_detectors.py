"""Tests for Java detectors."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import NodeKind, EdgeKind


def _ctx(content: bytes, language: str = "java", file_path: str = "Test.java") -> DetectorContext:
    return DetectorContext(
        file_path=file_path,
        language=language,
        content=content,
        module_name="test-module",
    )


class TestSpringRestDetector:
    def test_detect_get_mapping(self, order_controller_source):
        from code_intelligence.detectors.java.spring_rest import SpringRestDetector
        detector = SpringRestDetector()
        result = detector.detect(_ctx(order_controller_source, file_path="OrderController.java"))
        endpoints = [n for n in result.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) >= 2  # At least GET and POST
        methods = {n.properties.get("http_method") for n in endpoints}
        assert "GET" in methods or "get" in {m.lower() for m in methods if m}

    def test_supported_languages(self):
        from code_intelligence.detectors.java.spring_rest import SpringRestDetector
        d = SpringRestDetector()
        assert "java" in d.supported_languages


class TestJpaEntityDetector:
    def test_detect_entity(self, order_entity_source):
        from code_intelligence.detectors.java.jpa_entity import JpaEntityDetector
        detector = JpaEntityDetector()
        result = detector.detect(_ctx(order_entity_source, file_path="Order.java"))
        entities = [n for n in result.nodes if n.kind == NodeKind.ENTITY]
        assert len(entities) >= 1
        order_entity = entities[0]
        assert "order" in order_entity.label.lower() or "order" in order_entity.properties.get("table_name", "").lower()


class TestRepositoryDetector:
    def test_detect_repository(self, order_repository_source):
        from code_intelligence.detectors.java.repository import RepositoryDetector
        detector = RepositoryDetector()
        result = detector.detect(_ctx(order_repository_source, file_path="OrderRepository.java"))
        repos = [n for n in result.nodes if n.kind == NodeKind.REPOSITORY]
        assert len(repos) >= 1


class TestKafkaDetector:
    def test_detect_kafka(self, order_event_handler_source):
        from code_intelligence.detectors.java.kafka import KafkaDetector
        detector = KafkaDetector()
        result = detector.detect(_ctx(order_event_handler_source, file_path="OrderEventHandler.java"))
        # Should detect topics and consumer/producer patterns
        topics = [n for n in result.nodes if n.kind == NodeKind.TOPIC]
        assert len(topics) >= 1
        # Should have consume edges
        consume_edges = [e for e in result.edges if e.kind == EdgeKind.CONSUMES]
        assert len(consume_edges) >= 1


class TestSpringEventsDetector:
    def test_detect_events(self, order_event_handler_source):
        from code_intelligence.detectors.java.spring_events import SpringEventsDetector
        detector = SpringEventsDetector()
        result = detector.detect(_ctx(order_event_handler_source, file_path="OrderEventHandler.java"))
        events = [n for n in result.nodes if n.kind == NodeKind.EVENT]
        assert len(events) >= 1


class TestModuleDepsDetector:
    def test_detect_pom_modules(self, pom_xml_source):
        from code_intelligence.detectors.java.module_deps import ModuleDepsDetector
        detector = ModuleDepsDetector()
        result = detector.detect(_ctx(pom_xml_source, language="xml", file_path="pom.xml"))
        modules = [n for n in result.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) >= 1
