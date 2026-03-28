"""Tests for the OSSCodeIQ Explorer page state management."""

from __future__ import annotations

from osscodeiq.server.ui.explorer import ExplorerState


class TestExplorerStateInitial:
    def test_initial_state(self) -> None:
        state = ExplorerState()
        assert state.level == "kinds"
        assert state.current_kind is None
        assert state.page_offset == 0
        assert state.page_limit == 50
        assert len(state.breadcrumb) == 1
        assert state.breadcrumb[0]["label"] == "Home"
        assert state.breadcrumb[0]["level"] == "kinds"
        assert state.breadcrumb[0]["kind"] is None


class TestExplorerStateDrillDown:
    def test_drill_down(self) -> None:
        state = ExplorerState()
        state.drill_down("endpoint")
        assert state.level == "nodes"
        assert state.current_kind == "endpoint"
        assert state.page_offset == 0
        assert len(state.breadcrumb) == 2
        assert state.breadcrumb[1]["label"] == "endpoint"
        assert state.breadcrumb[1]["level"] == "nodes"
        assert state.breadcrumb[1]["kind"] == "endpoint"

    def test_drill_down_resets_offset(self) -> None:
        state = ExplorerState()
        state.page_offset = 100
        state.drill_down("entity")
        assert state.page_offset == 0

    def test_drill_down_preserves_home_breadcrumb(self) -> None:
        state = ExplorerState()
        state.drill_down("class")
        assert state.breadcrumb[0]["label"] == "Home"
        assert state.breadcrumb[0]["level"] == "kinds"


class TestExplorerStateGoHome:
    def test_go_home(self) -> None:
        state = ExplorerState()
        state.drill_down("endpoint")
        state.page_offset = 50
        state.go_home()
        assert state.level == "kinds"
        assert state.current_kind is None
        assert state.page_offset == 0
        assert len(state.breadcrumb) == 1
        assert state.breadcrumb[0]["label"] == "Home"

    def test_go_home_from_home(self) -> None:
        state = ExplorerState()
        state.go_home()
        assert state.level == "kinds"
        assert len(state.breadcrumb) == 1


class TestExplorerStateNavigateTo:
    def test_navigate_to_home(self) -> None:
        state = ExplorerState()
        state.drill_down("endpoint")
        state.navigate_to(0)
        assert state.level == "kinds"
        assert state.current_kind is None
        assert len(state.breadcrumb) == 1
        assert state.breadcrumb[0]["label"] == "Home"

    def test_navigate_to_preserves_path(self) -> None:
        state = ExplorerState()
        state.drill_down("endpoint")
        # Breadcrumb: [Home, endpoint]
        # Navigate to index 1 (endpoint) — stays there
        state.navigate_to(1)
        assert state.level == "nodes"
        assert state.current_kind == "endpoint"
        assert len(state.breadcrumb) == 2

    def test_navigate_to_resets_offset(self) -> None:
        state = ExplorerState()
        state.drill_down("endpoint")
        state.page_offset = 100
        state.navigate_to(0)
        assert state.page_offset == 0

    def test_navigate_to_negative_goes_home(self) -> None:
        state = ExplorerState()
        state.drill_down("endpoint")
        state.navigate_to(-1)
        assert state.level == "kinds"
        assert state.current_kind is None

    def test_navigate_to_out_of_bounds_ignored(self) -> None:
        state = ExplorerState()
        state.drill_down("endpoint")
        # Index 5 is out of bounds — should be a no-op
        state.navigate_to(5)
        assert state.level == "nodes"
        assert state.current_kind == "endpoint"
        assert len(state.breadcrumb) == 2
