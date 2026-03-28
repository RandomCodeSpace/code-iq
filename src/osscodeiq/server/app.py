"""FastAPI application assembly — mounts REST API, MCP server, and NiceGUI UI."""

from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import RedirectResponse

from osscodeiq.server.middleware import AuthMiddleware
from osscodeiq.server.mcp_server import get_mcp_app, set_service
from osscodeiq.server.routes import create_router
from osscodeiq.server.service import CodeIQService


def create_app(
    codebase_path: Path = Path("."),
    backend: str = "networkx",
    config_path: Path | None = None,
) -> FastAPI:
    """Create and configure the unified OSSCodeIQ server."""
    service = CodeIQService(
        path=codebase_path, backend=backend, config_path=config_path
    )

    # Set up MCP server
    set_service(service)
    mcp_app = get_mcp_app()

    # Create FastAPI with MCP lifespan
    app = FastAPI(
        title="OSSCodeIQ",
        description="OSSCodeIQ — graph queries, flow diagrams, and codebase analysis",
        lifespan=mcp_app.lifespan,
    )

    # Auth middleware stub (no-op, ready for future auth)
    app.add_middleware(AuthMiddleware)

    # Mount MCP at /mcp (streamable HTTP)
    app.mount("/mcp", mcp_app)

    # Include REST routes at /api
    router = create_router(service)
    app.include_router(router)

    # Redirect / to NiceGUI UI
    @app.get("/", include_in_schema=False)
    async def root_redirect():
        return RedirectResponse(url="/ui")

    # NiceGUI UI (explorer, flow, MCP console)
    from osscodeiq.server.ui import setup_ui
    from nicegui import ui

    setup_ui(service)
    ui.run_with(
        app,
        dark=None,
        title="OSSCodeIQ",
        storage_secret="osscodeiq-ui",
    )

    return app
