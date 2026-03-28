"""Plugin registry for code intelligence detectors."""

from __future__ import annotations

import importlib
import importlib.metadata
import logging
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from code_intelligence.detectors.base import Detector

logger = logging.getLogger(__name__)


class DetectorRegistry:
    """Registry that manages detector instances and plugin discovery."""

    def __init__(self) -> None:
        self._detectors: list[Detector] = []
        self._by_name: dict[str, Detector] = {}

    def register(self, detector: Detector) -> None:
        """Add a detector to the registry."""
        if detector.name in self._by_name:
            logger.warning("Detector %r already registered, skipping", detector.name)
            return
        self._detectors.append(detector)
        self._by_name[detector.name] = detector

    def load_builtin_detectors(self) -> None:
        """Import and register all built-in detectors via package scanning."""
        import pkgutil

        import code_intelligence.detectors as detectors_pkg

        # Walk all subpackages under code_intelligence.detectors
        skip = {"registry", "base", "utils", "__init__"}
        module_paths = []
        for importer, modname, ispkg in pkgutil.walk_packages(
            detectors_pkg.__path__,
            prefix="code_intelligence.detectors.",
        ):
            # Skip non-detector modules
            short_name = modname.rsplit(".", 1)[-1]
            if short_name in skip or short_name.startswith("_"):
                continue
            module_paths.append(modname)

        # Sort for deterministic registration order
        module_paths.sort()

        for module_path in module_paths:
            try:
                mod = importlib.import_module(module_path)
                # Convention: each module exposes a `detector` attribute or a class
                # ending with "Detector"
                if hasattr(mod, "detector"):
                    self.register(mod.detector)
                else:
                    for attr_name in dir(mod):
                        obj = getattr(mod, attr_name)
                        if (
                            isinstance(obj, type)
                            and attr_name.endswith("Detector")
                            and hasattr(obj, "detect")
                        ):
                            self.register(obj())
                            break
            except Exception:
                logger.debug("Could not load detector module %s", module_path, exc_info=True)

    def load_plugin_detectors(self) -> None:
        """Discover detectors via setuptools entry points."""
        try:
            eps = importlib.metadata.entry_points(group="code_intelligence.detectors")
        except TypeError:
            # Python < 3.12 compat
            eps = importlib.metadata.entry_points().get("code_intelligence.detectors", [])
        for ep in eps:
            try:
                detector_or_factory = ep.load()
                if callable(detector_or_factory) and isinstance(detector_or_factory, type):
                    self.register(detector_or_factory())
                else:
                    self.register(detector_or_factory)
            except Exception:
                logger.warning("Failed to load plugin detector %s", ep.name, exc_info=True)

    def detectors_for_language(self, language: str) -> list[Detector]:
        """Return all detectors that support a given language."""
        return [d for d in self._detectors if language in d.supported_languages]

    def all_detectors(self) -> list[Detector]:
        """Return all registered detectors."""
        return list(self._detectors)

    def get(self, name: str) -> Detector | None:
        """Get a detector by name."""
        return self._by_name.get(name)
