"""Shared test fixtures for code-intelligence tests."""

from __future__ import annotations

from pathlib import Path

import pytest

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.registry import DetectorRegistry


FIXTURES_DIR = Path(__file__).parent / "fixtures"
JAVA_FIXTURES = FIXTURES_DIR / "java"
PYTHON_FIXTURES = FIXTURES_DIR / "python"
TS_FIXTURES = FIXTURES_DIR / "typescript"


@pytest.fixture
def java_fixtures() -> Path:
    return JAVA_FIXTURES


@pytest.fixture
def python_fixtures() -> Path:
    return PYTHON_FIXTURES


@pytest.fixture
def ts_fixtures() -> Path:
    return TS_FIXTURES


@pytest.fixture
def order_controller_source() -> bytes:
    return (JAVA_FIXTURES / "OrderController.java").read_bytes()


@pytest.fixture
def order_entity_source() -> bytes:
    return (JAVA_FIXTURES / "Order.java").read_bytes()


@pytest.fixture
def order_repository_source() -> bytes:
    return (JAVA_FIXTURES / "OrderRepository.java").read_bytes()


@pytest.fixture
def order_event_handler_source() -> bytes:
    return (JAVA_FIXTURES / "OrderEventHandler.java").read_bytes()


@pytest.fixture
def pom_xml_source() -> bytes:
    return (JAVA_FIXTURES / "pom.xml").read_bytes()


@pytest.fixture
def fastapi_source() -> bytes:
    return (PYTHON_FIXTURES / "app.py").read_bytes()


@pytest.fixture
def sqlalchemy_source() -> bytes:
    return (PYTHON_FIXTURES / "models.py").read_bytes()


@pytest.fixture
def nestjs_controller_source() -> bytes:
    return (TS_FIXTURES / "user.controller.ts").read_bytes()


@pytest.fixture
def typeorm_entity_source() -> bytes:
    return (TS_FIXTURES / "user.entity.ts").read_bytes()


@pytest.fixture
def fetch_request_source() -> bytes:
    return (JAVA_FIXTURES / "FetchRequest.java").read_bytes()


@pytest.fixture
def fetch_response_source() -> bytes:
    return (JAVA_FIXTURES / "FetchResponse.java").read_bytes()


@pytest.fixture
def connectors_resource_source() -> bytes:
    return (JAVA_FIXTURES / "ConnectorsResource.java").read_bytes()


@pytest.fixture
def consumer_config_source() -> bytes:
    return (JAVA_FIXTURES / "ConsumerConfig.java").read_bytes()


# ---------------------------------------------------------------------------
# Detector discovery fixture
# ---------------------------------------------------------------------------

def _all_detectors():
    """Discover all registered detectors for parametrized tests."""
    registry = DetectorRegistry()
    registry.load_builtin_detectors()
    return registry.all_detectors()


ALL_DETECTORS = _all_detectors()
ALL_DETECTOR_IDS = [d.name for d in ALL_DETECTORS]


@pytest.fixture(params=ALL_DETECTORS, ids=ALL_DETECTOR_IDS)
def detector(request):
    """Parametrized fixture yielding each registered detector."""
    return request.param


# ---------------------------------------------------------------------------
# Hostile input fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def empty_ctx():
    """Empty file -- zero bytes."""
    def _make(language="java", path="empty.txt"):
        return DetectorContext(file_path=path, language=language, content=b"", module_name=None)
    return _make


@pytest.fixture
def binary_ctx():
    """Binary garbage -- should not crash any detector."""
    data = bytes(range(256)) * 10  # 2560 bytes of every byte value
    def _make(language="java", path="binary.bin"):
        return DetectorContext(file_path=path, language=language, content=data, module_name=None)
    return _make


@pytest.fixture
def malformed_utf8_ctx():
    """Invalid UTF-8 sequences -- tests decode error handling."""
    data = b"public class Foo {\n" + b"\xff\xfe\x80\x81" * 50 + b"\n}\n"
    def _make(language="java", path="malformed.java"):
        return DetectorContext(file_path=path, language=language, content=data, module_name=None)
    return _make


@pytest.fixture
def unicode_ctx():
    """Unicode content -- Chinese, Arabic, emoji in identifiers."""
    data = (
        "class \u4f60\u597d\u4e16\u754c {\n"  # Chinese
        "    public void \u0645\u0631\u062d\u0628\u0627() {}\n"  # Arabic
        "    String emoji = \"\U0001f680\U0001f4a5\";\n"  # Rocket + explosion
        "    // Comment with \u00e9\u00e8\u00ea\u00eb\n"  # French accents
        "}\n"
    ).encode("utf-8")
    def _make(language="java", path="unicode.java"):
        return DetectorContext(file_path=path, language=language, content=data, module_name=None)
    return _make


@pytest.fixture
def huge_ctx():
    """Large file -- 50K lines of repetitive content."""
    lines = ["public void method_%d() { return; }" % i for i in range(50000)]
    data = "\n".join(lines).encode("utf-8")
    def _make(language="java", path="huge.java"):
        return DetectorContext(file_path=path, language=language, content=data, module_name=None)
    return _make


@pytest.fixture
def null_bytes_ctx():
    """File with null bytes embedded in otherwise valid content."""
    data = b"class Foo {\n\x00\x00\x00\n    void bar() {}\n\x00}\n"
    def _make(language="java", path="nulls.java"):
        return DetectorContext(file_path=path, language=language, content=data, module_name=None)
    return _make


@pytest.fixture
def deeply_nested_json_ctx():
    """Deeply nested JSON -- 100 levels deep."""
    nested = "{}"
    for i in range(100):
        nested = '{"level_%d": %s}' % (i, nested)
    def _make(path="deep.json"):
        import json
        return DetectorContext(
            file_path=path, language="json", content=nested.encode(),
            parsed_data={"type": "json", "file": path, "data": json.loads(nested)},
            module_name=None,
        )
    return _make


@pytest.fixture
def special_chars_path_ctx():
    """File path with spaces, parentheses, and special characters."""
    data = b"class Normal { void test() {} }"
    def _make(language="java"):
        return DetectorContext(
            file_path="path with spaces/file (copy).java",
            language=language, content=data, module_name=None,
        )
    return _make
