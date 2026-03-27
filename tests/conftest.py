"""Shared test fixtures for code-intelligence tests."""

from __future__ import annotations

from pathlib import Path

import pytest


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
