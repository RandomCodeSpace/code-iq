"""Tests for FastAPI application assembly (app.py)."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from osscodeiq.server.app import create_app


@pytest.fixture
def app(tmp_path):
    """Create a test app with an empty codebase."""
    return create_app(codebase_path=tmp_path, backend="networkx")


@pytest.fixture
def client(app):
    return TestClient(app, raise_server_exceptions=False)


def test_create_app_returns_fastapi(app):
    from fastapi import FastAPI
    assert isinstance(app, FastAPI)


def test_welcome_page(client):
    resp = client.get("/")
    assert resp.status_code == 200
    assert "OSSCodeIQ" in resp.text


def test_api_stats_route(client):
    resp = client.get("/api/stats")
    assert resp.status_code == 200
    data = resp.json()
    assert "backend" in data


def test_api_nodes_route(client):
    resp = client.get("/api/nodes")
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


def test_api_edges_route(client):
    resp = client.get("/api/edges")
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


def test_docs_route(client):
    resp = client.get("/docs")
    assert resp.status_code == 200


def test_app_title(app):
    assert app.title == "OSSCodeIQ"
