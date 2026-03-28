"""Tests to improve coverage for detectors/registry.py."""

from __future__ import annotations

import pytest

from osscodeiq.detectors.registry import DetectorRegistry
from osscodeiq.detectors.base import DetectorResult


class _FakeDetector:
    """Minimal detector for testing."""

    def __init__(self, name: str = "fake", languages: frozenset[str] | None = None):
        self._name = name
        self._languages = languages or frozenset({"python"})

    @property
    def name(self) -> str:
        return self._name

    @property
    def supported_languages(self) -> frozenset[str]:
        return self._languages

    def detect(self, ctx):
        return DetectorResult()


class TestRegister:
    def test_register_single(self):
        reg = DetectorRegistry()
        d = _FakeDetector("test_det")
        reg.register(d)
        assert reg.get("test_det") is d

    def test_register_duplicate_skipped(self):
        reg = DetectorRegistry()
        d1 = _FakeDetector("dup")
        d2 = _FakeDetector("dup")
        reg.register(d1)
        reg.register(d2)
        assert len(reg.all_detectors()) == 1
        assert reg.get("dup") is d1


class TestAllDetectors:
    def test_empty(self):
        reg = DetectorRegistry()
        assert reg.all_detectors() == []

    def test_returns_copy(self):
        reg = DetectorRegistry()
        reg.register(_FakeDetector("a"))
        result = reg.all_detectors()
        result.clear()
        assert len(reg.all_detectors()) == 1


class TestDetectorsForLanguage:
    def test_matching_language(self):
        reg = DetectorRegistry()
        reg.register(_FakeDetector("py_det", frozenset({"python"})))
        reg.register(_FakeDetector("js_det", frozenset({"javascript"})))
        result = reg.detectors_for_language("python")
        assert len(result) == 1
        assert result[0].name == "py_det"

    def test_no_matching_language(self):
        reg = DetectorRegistry()
        reg.register(_FakeDetector("py_det", frozenset({"python"})))
        assert reg.detectors_for_language("rust") == []


class TestGet:
    def test_get_existing(self):
        reg = DetectorRegistry()
        d = _FakeDetector("findme")
        reg.register(d)
        assert reg.get("findme") is d

    def test_get_missing(self):
        reg = DetectorRegistry()
        assert reg.get("nope") is None


class TestLoadBuiltinDetectors:
    def test_loads_detectors(self):
        reg = DetectorRegistry()
        reg.load_builtin_detectors()
        all_dets = reg.all_detectors()
        assert len(all_dets) > 0

    def test_no_duplicates(self):
        reg = DetectorRegistry()
        reg.load_builtin_detectors()
        names = [d.name for d in reg.all_detectors()]
        assert len(names) == len(set(names))


class TestLoadPluginDetectors:
    def test_no_crash_without_plugins(self):
        reg = DetectorRegistry()
        reg.load_plugin_detectors()
        # Should not raise even with no plugins installed


class TestMultipleLanguages:
    def test_detector_supports_multiple(self):
        reg = DetectorRegistry()
        reg.register(_FakeDetector("multi", frozenset({"python", "javascript"})))
        assert len(reg.detectors_for_language("python")) == 1
        assert len(reg.detectors_for_language("javascript")) == 1
        assert len(reg.detectors_for_language("rust")) == 0
