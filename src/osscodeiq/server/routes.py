"""REST API routes for OSSCodeIQ server."""
from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import PlainTextResponse
from pydantic import BaseModel

from osscodeiq.server.service import CodeIQService


class AnalyzeRequest(BaseModel):
    incremental: bool = True


class CypherRequest(BaseModel):
    query: str
    params: dict | None = None


def create_router(service: CodeIQService) -> APIRouter:
    router = APIRouter(prefix="/api", tags=["OSSCodeIQ API"])

    # ── Stats ────────────────────────────────────────────────────────────

    @router.get("/stats")
    async def stats():
        return service.get_stats()

    # ── Kinds (Explorer UI) ────────────────────────────────────────────────

    @router.get("/kinds")
    async def list_kinds():
        return service.list_kinds()

    @router.get("/kinds/{kind}")
    async def nodes_by_kind(
        kind: str,
        limit: Annotated[int, Query(ge=1)] = 50,
        offset: Annotated[int, Query(ge=0)] = 0,
    ):
        return service.nodes_by_kind_paginated(kind, limit=limit, offset=offset)

    # ── Nodes & Edges ────────────────────────────────────────────────────

    @router.get("/nodes")
    async def list_nodes(
        kind: str | None = None,
        limit: Annotated[int, Query(ge=1)] = 100,
        offset: Annotated[int, Query(ge=0)] = 0,
    ):
        return service.list_nodes(kind=kind, limit=limit, offset=offset)

    # NOTE: /neighbors and /detail must be registered before the catch-all
    # {node_id:path} route, otherwise Starlette's greedy path matching
    # swallows them.

    @router.get("/nodes/{node_id:path}/neighbors")
    async def get_neighbors(
        node_id: str,
        direction: str = "both",
        edge_kinds: str | None = None,
    ):
        kinds = edge_kinds.split(",") if edge_kinds else None
        return service.get_neighbors(node_id, direction=direction, edge_kinds=kinds)

    @router.get(
        "/nodes/{node_id:path}/detail",
        responses={404: {"description": "Node not found"}},
    )
    async def node_detail(node_id: str):
        result = service.node_detail_with_edges(node_id)
        if result is None:
            raise HTTPException(status_code=404, detail=f"Node not found: {node_id}")
        return result

    @router.get(
        "/nodes/{node_id:path}",
        responses={404: {"description": "Node not found"}},
    )
    async def get_node(node_id: str):
        result = service.get_node(node_id)
        if result is None:
            raise HTTPException(status_code=404, detail=f"Node not found: {node_id}")
        return result

    @router.get("/edges")
    async def list_edges(
        kind: str | None = None,
        limit: Annotated[int, Query(ge=1)] = 100,
        offset: Annotated[int, Query(ge=0)] = 0,
    ):
        return service.list_edges(kind=kind, limit=limit, offset=offset)

    # ── Ego ──────────────────────────────────────────────────────────────

    @router.get("/ego/{center:path}")
    async def get_ego(
        center: str,
        radius: Annotated[int, Query(ge=1)] = 2,
        edge_kinds: str | None = None,
    ):
        kinds = edge_kinds.split(",") if edge_kinds else None
        return service.get_ego(center, radius=radius, edge_kinds=kinds)

    # ── Query endpoints ──────────────────────────────────────────────────

    @router.get("/query/cycles")
    async def find_cycles(limit: Annotated[int, Query(ge=1)] = 100):
        return service.find_cycles(limit=limit)

    @router.get(
        "/query/shortest-path",
        responses={404: {"description": "No path found between source and target"}},
    )
    async def shortest_path(
        source: Annotated[str, Query()],
        target: Annotated[str, Query()],
    ):
        result = service.shortest_path(source, target)
        if result is None:
            raise HTTPException(
                status_code=404,
                detail=f"No path found between {source} and {target}",
            )
        return result

    @router.get("/query/consumers/{target_id:path}")
    async def consumers_of(target_id: str):
        return service.consumers_of(target_id)

    @router.get("/query/producers/{target_id:path}")
    async def producers_of(target_id: str):
        return service.producers_of(target_id)

    @router.get("/query/callers/{target_id:path}")
    async def callers_of(target_id: str):
        return service.callers_of(target_id)

    @router.get("/query/dependencies/{module_id:path}")
    async def dependencies_of(module_id: str):
        return service.dependencies_of(module_id)

    @router.get("/query/dependents/{module_id:path}")
    async def dependents_of(module_id: str):
        return service.dependents_of(module_id)

    # ── Flow ─────────────────────────────────────────────────────────────

    @router.get("/flow/{view}")
    async def generate_flow(view: str, fmt: str = "json"):
        return service.generate_flow(view, fmt=fmt)

    @router.get("/flow")
    async def generate_all_flows():
        return service.generate_all_flows()

    # ── Analysis ─────────────────────────────────────────────────────────

    @router.post("/analyze")
    async def analyze(body: AnalyzeRequest):
        return service.run_analysis(body.incremental)

    # ── Cypher ───────────────────────────────────────────────────────────

    @router.post(
        "/cypher",
        responses={400: {"description": "Invalid Cypher query or backend unavailable"}},
    )
    async def cypher(body: CypherRequest):
        try:
            return service.query_cypher(body.query, body.params)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    # ── Triage ───────────────────────────────────────────────────────────

    @router.get("/triage/component")
    async def find_component(file_path: Annotated[str, Query()]):
        return service.find_component_by_file(file_path)

    @router.get("/triage/impact/{node_id:path}")
    async def trace_impact(node_id: str, depth: Annotated[int, Query(ge=1)] = 3):
        return service.trace_impact(node_id, depth=depth)

    @router.get("/triage/endpoints")
    async def find_related_endpoints(identifier: Annotated[str, Query()]):
        return service.find_related_endpoints(identifier)

    # ── Search ───────────────────────────────────────────────────────────

    @router.get("/search")
    async def search_graph(
        q: Annotated[str, Query()],
        limit: Annotated[int, Query(ge=1)] = 20,
    ):
        return service.search_graph(q, limit=limit)

    # ── File ─────────────────────────────────────────────────────────────

    @router.get("/file")
    async def read_file(path: Annotated[str, Query()]):
        try:
            content = service.read_file(path)
        except ValueError as exc:
            detail = str(exc)
            status = 404 if "not found" in detail.lower() else 400
            raise HTTPException(status_code=status, detail=detail) from exc
        return PlainTextResponse(content)

    return router
