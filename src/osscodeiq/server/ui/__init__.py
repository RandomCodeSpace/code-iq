"""NiceGUI-based web UI for OSSCodeIQ."""

from __future__ import annotations

from typing import TYPE_CHECKING

from nicegui import ui

from osscodeiq.server.ui.theme import BRAND_COLOR

if TYPE_CHECKING:
    from osscodeiq.server.service import CodeIQService


def setup_ui(service: CodeIQService) -> None:
    """Register NiceGUI pages on the existing FastAPI app."""

    @ui.page("/ui")
    def index():
        dark = ui.dark_mode(value=None)

        with ui.header().classes("items-center justify-between px-4 py-2"):
            with ui.row().classes("items-center gap-3"):
                ui.icon("hub").style(f"color: {BRAND_COLOR}; font-size: 28px")
                ui.label("OSSCodeIQ").classes("text-lg font-bold")
            with ui.row().classes("items-center gap-2"):
                try:
                    stats = service.get_stats()
                    ui.badge(f"{stats.get('total_nodes', 0):,} nodes").props("color=primary outline")
                    ui.badge(f"{stats.get('total_edges', 0):,} edges").props("color=positive outline")
                except Exception:
                    pass
                ui.button(icon="light_mode", on_click=lambda: dark.set_value(False)).props("flat dense round").tooltip("Light theme")
                ui.button(icon="dark_mode", on_click=lambda: dark.set_value(True)).props("flat dense round").tooltip("Dark theme")
                ui.button(icon="contrast", on_click=lambda: dark.set_value(None)).props("flat dense round").tooltip("System theme")

        with ui.tabs().classes("w-full") as tabs:
            explorer_tab = ui.tab("Explorer", icon="explore")
            flow_tab = ui.tab("Flow", icon="account_tree")
            console_tab = ui.tab("MCP Console", icon="terminal")

        with ui.tab_panels(tabs, value=explorer_tab).classes("w-full flex-grow"):
            with ui.tab_panel(explorer_tab):
                from osscodeiq.server.ui.explorer import create_explorer_page
                create_explorer_page(service)
            with ui.tab_panel(flow_tab):
                from osscodeiq.server.ui.flow_view import create_flow_page
                create_flow_page(service)
            with ui.tab_panel(console_tab):
                from osscodeiq.server.ui.mcp_console import create_mcp_console
                create_mcp_console(service)
