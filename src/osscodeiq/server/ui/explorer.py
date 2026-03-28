"""Explorer page for the OSSCodeIQ NiceGUI-based web UI."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from nicegui import ui

from osscodeiq.server.ui.components import (
    build_detail_data,
    build_kind_card_data,
    build_node_card_data,
)
from osscodeiq.server.ui.theme import get_animation_css, get_kind_color, get_kind_icon


@dataclass
class ExplorerState:
    """Tracks navigation state for the explorer page.

    Levels:
      - "kinds": shows all node kinds as cards
      - "nodes": shows nodes of a specific kind
    """

    level: str = "kinds"
    current_kind: str | None = None
    breadcrumb: list[dict[str, Any]] = field(default_factory=list)
    page_offset: int = 0
    page_limit: int = 50

    def __post_init__(self) -> None:
        if not self.breadcrumb:
            self.breadcrumb = [{"label": "Home", "level": "kinds", "kind": None}]

    def drill_down(self, kind: str) -> None:
        """Navigate from kinds level into a specific kind's nodes."""
        self.level = "nodes"
        self.current_kind = kind
        self.page_offset = 0
        self.breadcrumb.append({"label": kind, "level": "nodes", "kind": kind})

    def go_home(self) -> None:
        """Reset navigation to the top-level kinds view."""
        self.level = "kinds"
        self.current_kind = None
        self.page_offset = 0
        self.breadcrumb = [{"label": "Home", "level": "kinds", "kind": None}]

    def navigate_to(self, index: int) -> None:
        """Navigate to a specific breadcrumb index, trimming the trail."""
        if index <= 0:
            self.go_home()
            return
        if index < len(self.breadcrumb):
            target = self.breadcrumb[index]
            self.breadcrumb = self.breadcrumb[: index + 1]
            self.level = target["level"]
            self.current_kind = target["kind"]
            self.page_offset = 0


# ---------------------------------------------------------------------------
# Search filter JavaScript (client-side, no server round-trip)
# ---------------------------------------------------------------------------

_SEARCH_JS = """
(function(query) {
    const cards = document.querySelectorAll('.explorer-card');
    const lower = query.toLowerCase();
    cards.forEach(function(card) {
        const text = card.textContent.toLowerCase();
        if (!lower || text.includes(lower)) {
            card.style.opacity = '1';
            card.style.pointerEvents = 'auto';
            card.style.display = '';
        } else {
            card.style.opacity = '0.15';
            card.style.pointerEvents = 'none';
        }
    });
})("{query}")
"""


# ---------------------------------------------------------------------------
# Detail modal
# ---------------------------------------------------------------------------

def _show_detail_modal(service: Any, node_id: str) -> None:
    """Open a dialog showing full node details."""
    raw = service.node_detail_with_edges(node_id)
    if raw is None:
        ui.notify("Node not found", type="warning")
        return

    data = build_detail_data(raw)

    with ui.dialog() as dlg, ui.card().classes("w-full max-w-2xl"):
        # Header
        with ui.row().classes("items-center gap-2 w-full"):
            kind = data["kind"]
            ui.icon(get_kind_icon(kind)).classes("text-2xl").style(
                f"color: {get_kind_color(kind)}"
            )
            ui.label(data["name"]).classes("text-xl font-bold")
            ui.badge(kind).props("outline").style(
                f"color: {get_kind_color(kind)}; border-color: {get_kind_color(kind)}"
            )

        ui.separator()

        # Properties table
        if data["properties"]:
            ui.label("Properties").classes("text-sm font-semibold text-gray-500 mt-2")
            with ui.element("div").classes("w-full"):
                for key, value in data["properties"]:
                    with ui.row().classes("items-center gap-2 py-1"):
                        ui.label(key).classes(
                            "text-xs font-medium text-gray-400 w-28 shrink-0"
                        )
                        ui.label(str(value)).classes(
                            "text-sm select-all break-all"
                        )

        # Outgoing edges
        if data["edges_out"]:
            ui.label("Outgoing Edges").classes(
                "text-sm font-semibold text-gray-500 mt-4"
            )
            for edge in data["edges_out"]:
                with ui.row().classes("items-center gap-1 py-0.5"):
                    ui.badge(edge["kind"]).props("outline dense")
                    ui.icon("arrow_forward").classes("text-xs text-gray-400")
                    ui.label(edge.get("target_name", edge.get("target_id", "?"))).classes(
                        "text-sm select-all"
                    )

        # Incoming edges
        if data["edges_in"]:
            ui.label("Incoming Edges").classes(
                "text-sm font-semibold text-gray-500 mt-4"
            )
            for edge in data["edges_in"]:
                with ui.row().classes("items-center gap-1 py-0.5"):
                    ui.label(edge.get("source_name", edge.get("source_id", "?"))).classes(
                        "text-sm select-all"
                    )
                    ui.icon("arrow_forward").classes("text-xs text-gray-400")
                    ui.badge(edge["kind"]).props("outline dense")

        # Close button
        with ui.row().classes("w-full justify-end mt-4"):
            ui.button("Close", on_click=dlg.close).props("flat")

    dlg.open()


# ---------------------------------------------------------------------------
# Kind summary modal
# ---------------------------------------------------------------------------

def _show_kind_modal(kind_data: dict[str, Any]) -> None:
    """Open a dialog showing a summary of a node kind."""
    with ui.dialog() as dlg, ui.card().classes("w-full max-w-md"):
        with ui.row().classes("items-center gap-2"):
            ui.icon(kind_data["icon"]).classes("text-2xl").style(
                f"color: {kind_data['color']}"
            )
            ui.label(kind_data["title"]).classes("text-xl font-bold")

        ui.separator()

        ui.label(f"Total nodes: {kind_data['count']}").classes("text-sm")

        if kind_data.get("preview"):
            ui.label("Preview").classes("text-sm font-semibold text-gray-500 mt-2")
            for item in kind_data["preview"][:10]:
                ui.label(f"  {item}").classes("text-xs text-gray-400 select-all")

        with ui.row().classes("w-full justify-end mt-4"):
            ui.button("Close", on_click=dlg.close).props("flat")

    dlg.open()


# ---------------------------------------------------------------------------
# Card renderers
# ---------------------------------------------------------------------------

def _render_kind_card(
    kind_data: dict[str, Any],
    service: Any,
    state: ExplorerState,
    refresh_fn: Any,
    index: int,
) -> None:
    """Render a single kind card."""
    color = kind_data["color"]
    delay_cls = f"card-animate-{min(index + 1, 10)}"

    with ui.card().classes(
        f"explorer-card card-animate {delay_cls} w-full"
    ).style(
        f"border-left: 4px solid {color};"
    ):
        with ui.card_section():
            with ui.row().classes("items-center gap-2"):
                ui.icon(kind_data["icon"]).classes("text-xl").style(
                    f"color: {color}"
                )
                ui.label(kind_data["title"]).classes("text-lg font-semibold")
            ui.label(f"{kind_data['count']} nodes").classes(
                "text-sm text-gray-500"
            )
            if kind_data.get("preview"):
                for item in kind_data["preview"][:3]:
                    ui.label(item).classes(
                        "text-xs text-gray-400 truncate"
                    ).style("max-width: 220px")

        with ui.card_actions().classes("justify-end"):
            ui.button(
                "Details",
                on_click=lambda kd=kind_data: _show_kind_modal(kd),
            ).props("flat dense")
            ui.button(
                "Explore",
                on_click=lambda k=kind_data["kind"]: _on_drill_down(
                    k, state, refresh_fn
                ),
            ).props("flat dense color=primary")


def _render_node_card(
    node_data: dict[str, Any],
    service: Any,
    index: int,
) -> None:
    """Render a single node card."""
    delay_cls = f"card-animate-{min(index + 1, 10)}"

    with ui.card().classes(
        f"explorer-card card-animate {delay_cls} w-full"
    ):
        with ui.card_section():
            ui.label(node_data["title"]).classes("text-base font-semibold")
            if node_data["subtitle"]:
                ui.label(node_data["subtitle"]).classes(
                    "text-xs text-gray-500 truncate"
                ).style("max-width: 280px")
            if node_data.get("properties"):
                with ui.row().classes("gap-1 flex-wrap mt-1"):
                    for key, val in list(node_data["properties"].items())[:5]:
                        ui.badge(f"{key}: {val}").props("outline dense")

        with ui.card_actions().classes("justify-end"):
            ui.button(
                "Details",
                on_click=lambda nid=node_data["id"]: _show_detail_modal(
                    service, nid
                ),
            ).props("flat dense")


# ---------------------------------------------------------------------------
# Navigation helpers
# ---------------------------------------------------------------------------

def _on_drill_down(kind: str, state: ExplorerState, refresh_fn: Any) -> None:
    """Handle drilling down into a kind."""
    state.drill_down(kind)
    refresh_fn()


def _on_page_change(
    delta: int, state: ExplorerState, total: int, refresh_fn: Any
) -> None:
    """Handle pagination offset change."""
    new_offset = state.page_offset + delta
    if new_offset < 0:
        new_offset = 0
    if new_offset >= total:
        return
    state.page_offset = new_offset
    refresh_fn()


# ---------------------------------------------------------------------------
# Main explorer page builder
# ---------------------------------------------------------------------------

def create_explorer_page(service: Any) -> None:
    """Build the explorer tab content within an existing NiceGUI page context.

    Parameters
    ----------
    service:
        A CodeIQService instance providing list_kinds(),
        nodes_by_kind_paginated(), and node_detail_with_edges().
    """
    state = ExplorerState()

    # Inject animation CSS
    ui.add_head_html(f"<style>{get_animation_css()}</style>")

    # -- Container that gets refreshed on navigation -----------------------

    @ui.refreshable
    def content() -> None:
        # Breadcrumb row
        with ui.row().classes("items-center gap-1 mb-2"):
            for idx, crumb in enumerate(state.breadcrumb):
                if idx > 0:
                    ui.icon("chevron_right").classes("text-sm text-gray-400")
                if idx < len(state.breadcrumb) - 1:
                    ui.link(
                        crumb["label"],
                        on_click=lambda i=idx: _nav_to(i, state, content.refresh),
                    ).classes("text-sm text-blue-500 cursor-pointer")
                else:
                    ui.label(crumb["label"]).classes(
                        "text-sm font-semibold text-gray-700"
                    )

        # Search input
        search_input = ui.input(
            placeholder="Filter cards...",
        ).classes("w-full max-w-sm mb-3").props('dense clearable outlined')

        search_input.on(
            "update:model-value",
            lambda e: ui.run_javascript(
                _SEARCH_JS.replace("{query}", str(e.args or "").replace('"', '\\"'))
            ),
        )

        # Render based on level
        if state.level == "kinds":
            _render_kinds_grid(service, state, content.refresh)
        else:
            _render_nodes_grid(service, state, content.refresh)

    content()


def _nav_to(index: int, state: ExplorerState, refresh_fn: Any) -> None:
    """Navigate to a breadcrumb index and refresh."""
    state.navigate_to(index)
    refresh_fn()


def _render_kinds_grid(service: Any, state: ExplorerState, refresh_fn: Any) -> None:
    """Render the kind cards grid."""
    result = service.list_kinds()
    kinds = result.get("kinds", [])

    if not kinds:
        ui.label("No data. Run an analysis first.").classes("text-gray-500 mt-4")
        return

    with ui.row().classes("text-xs text-gray-400 mb-1"):
        ui.label(
            f"{result.get('total_nodes', 0)} nodes, "
            f"{result.get('total_edges', 0)} edges across "
            f"{len(kinds)} kinds"
        )

    with ui.element("div").classes(
        "grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4 w-full"
    ):
        for idx, kind_info in enumerate(kinds):
            card_data = build_kind_card_data(kind_info)
            _render_kind_card(card_data, service, state, refresh_fn, idx)


def _render_nodes_grid(service: Any, state: ExplorerState, refresh_fn: Any) -> None:
    """Render the node cards grid with pagination."""
    kind = state.current_kind
    if kind is None:
        return

    result = service.nodes_by_kind_paginated(
        kind, state.page_limit, state.page_offset
    )
    total = result.get("total", 0)
    nodes = result.get("nodes", [])

    if not nodes and state.page_offset == 0:
        ui.label(f"No {kind} nodes found.").classes("text-gray-500 mt-4")
        return

    # Summary line
    start = state.page_offset + 1
    end = min(state.page_offset + len(nodes), total)
    with ui.row().classes("text-xs text-gray-400 mb-1"):
        ui.label(f"Showing {start}-{end} of {total} {kind} nodes")

    # Card grid
    with ui.element("div").classes(
        "grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4 w-full"
    ):
        for idx, node_info in enumerate(nodes):
            card_data = build_node_card_data(node_info)
            _render_node_card(card_data, service, idx)

    # Pagination controls
    with ui.row().classes("items-center justify-center gap-4 mt-4"):
        prev_disabled = state.page_offset <= 0
        ui.button(
            "Prev",
            on_click=lambda: _on_page_change(
                -state.page_limit, state, total, refresh_fn
            ),
        ).props(f"flat {'disable' if prev_disabled else ''}")

        ui.label(f"Page {state.page_offset // state.page_limit + 1}").classes(
            "text-sm text-gray-500"
        )

        next_disabled = state.page_offset + state.page_limit >= total
        ui.button(
            "Next",
            on_click=lambda: _on_page_change(
                state.page_limit, state, total, refresh_fn
            ),
        ).props(f"flat {'disable' if next_disabled else ''}")
