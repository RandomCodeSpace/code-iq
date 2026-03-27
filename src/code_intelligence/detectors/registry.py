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
        """Import and register all built-in detectors."""
        builtin_modules = [
            "code_intelligence.detectors.java.spring_rest",
            "code_intelligence.detectors.java.jpa_entity",
            "code_intelligence.detectors.java.repository",
            "code_intelligence.detectors.java.kafka",
            "code_intelligence.detectors.java.spring_events",
            "code_intelligence.detectors.java.rmi",
            "code_intelligence.detectors.java.module_deps",
            "code_intelligence.detectors.java.raw_sql",
            "code_intelligence.detectors.java.graphql_resolver",
            "code_intelligence.detectors.java.grpc_service",
            "code_intelligence.detectors.java.jms",
            "code_intelligence.detectors.java.rabbitmq",
            "code_intelligence.detectors.java.websocket",
            # Python detectors
            "code_intelligence.detectors.python.flask_routes",
            "code_intelligence.detectors.python.django_views",
            "code_intelligence.detectors.python.fastapi_routes",
            "code_intelligence.detectors.python.sqlalchemy_models",
            "code_intelligence.detectors.python.celery_tasks",
            # TypeScript detectors
            "code_intelligence.detectors.typescript.express_routes",
            "code_intelligence.detectors.typescript.nestjs_controllers",
            "code_intelligence.detectors.typescript.graphql_resolvers",
            "code_intelligence.detectors.typescript.typeorm_entities",
        ]
        for module_path in builtin_modules:
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
                logger.debug("Could not load builtin detector module %s", module_path, exc_info=True)

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
