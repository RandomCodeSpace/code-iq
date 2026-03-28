"""Tests for graph/backends/__init__.py — create_backend factory."""

from __future__ import annotations

import pytest

from osscodeiq.graph.backends import create_backend


def test_create_networkx_backend():
    backend = create_backend("networkx")
    assert backend is not None
    from osscodeiq.graph.backends.networkx import NetworkXBackend
    assert isinstance(backend, NetworkXBackend)


def test_create_sqlite_backend(tmp_path):
    db_path = str(tmp_path / "test.db")
    backend = create_backend("sqlite", path=db_path)
    assert backend is not None
    from osscodeiq.graph.backends.sqlite_backend import SqliteGraphBackend
    assert isinstance(backend, SqliteGraphBackend)


def test_create_kuzu_backend(tmp_path):
    db_path = str(tmp_path / "test.kuzu")
    backend = create_backend("kuzu", path=db_path)
    assert backend is not None
    from osscodeiq.graph.backends.kuzu import KuzuBackend
    assert isinstance(backend, KuzuBackend)


def test_create_unknown_backend_raises():
    with pytest.raises(ValueError, match="Unknown graph backend"):
        create_backend("nonexistent_backend")


def test_default_is_networkx():
    backend = create_backend()
    from osscodeiq.graph.backends.networkx import NetworkXBackend
    assert isinstance(backend, NetworkXBackend)
