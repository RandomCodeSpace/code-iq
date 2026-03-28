"""Pipeline orchestrator for code intelligence analysis."""

from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from code_intelligence.cache.store import CacheStore
from code_intelligence.config import Config
from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.registry import DetectorRegistry
from code_intelligence.discovery.change_detector import ChangeDetector
from code_intelligence.discovery.file_discovery import (
    ChangeType,
    DiscoveredFile,
    FileDiscovery,
)
from code_intelligence.graph.builder import GraphBuilder
from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import GraphEdge, GraphNode

logger = logging.getLogger(__name__)

# Languages handled by tree-sitter
_TREESITTER_LANGUAGES = {"java", "python", "typescript", "javascript"}

# Languages handled by structured parsers
_STRUCTURED_LANGUAGES = {
    "xml", "yaml", "json", "properties", "gradle", "sql",
    "bicep", "terraform", "csharp", "go", "cpp", "c",
    "bash", "powershell", "batch", "ruby", "rust", "kotlin",
    "scala", "swift", "r", "perl", "lua", "dart",
    "dockerfile", "toml", "ini", "dotenv", "csv",
    "vue", "svelte",
}


@dataclass
class AnalysisResult:
    """Result of running the full analysis pipeline."""

    graph: GraphStore
    files_analyzed: int
    files_cached: int
    total_files: int
    language_breakdown: dict[str, int]
    node_breakdown: dict[str, int]
    files_with_detectors: int
    files_without_detectors: int


def _parse_structured(language: str, content: bytes, file_path: str) -> Any:
    """Dispatch to the correct structured parser."""
    if language == "xml":
        from code_intelligence.parsing.structured.xml_parser import XmlParser
        return XmlParser().parse(content, file_path)
    elif language == "yaml":
        from code_intelligence.parsing.structured.yaml_parser import YamlParser
        return YamlParser().parse(content, file_path)
    elif language == "json":
        from code_intelligence.parsing.structured.json_parser import JsonParser
        return JsonParser().parse(content, file_path)
    elif language == "properties":
        from code_intelligence.parsing.structured.properties_parser import PropertiesParser
        return PropertiesParser().parse(content, file_path)
    elif language == "gradle":
        from code_intelligence.parsing.structured.gradle_parser import GradleParser
        return GradleParser().parse(content, file_path)
    elif language == "sql":
        from code_intelligence.parsing.structured.sql_parser import SqlParser
        return SqlParser().parse(content, file_path)
    elif language == "toml":
        try:
            import tomllib
        except ModuleNotFoundError:
            import tomli as tomllib  # type: ignore[no-redef]
        try:
            text = content.decode("utf-8", errors="replace")
            data = tomllib.loads(text)
        except Exception as exc:
            return {"error": "invalid_toml", "file": file_path, "detail": str(exc)}
        return {"type": "toml", "file": file_path, "data": data}
    elif language == "ini":
        import configparser
        try:
            text = content.decode("utf-8", errors="replace")
            parser = configparser.ConfigParser()
            parser.read_string(text)
            data = {section: dict(parser[section]) for section in parser.sections()}
        except Exception as exc:
            return {"error": "invalid_ini", "file": file_path, "detail": str(exc)}
        return {"type": "ini", "file": file_path, "data": data}
    elif language == "markdown":
        # Return raw text for regex-based detection
        return {"type": "markdown", "file": file_path, "data": content.decode("utf-8", errors="replace")}
    elif language == "proto":
        # Return raw text for regex-based detection
        return {"type": "proto", "file": file_path, "data": content.decode("utf-8", errors="replace")}
    elif language == "vue":
        return {"type": "vue", "file": file_path, "data": content.decode("utf-8", errors="replace")}
    elif language == "svelte":
        return {"type": "svelte", "file": file_path, "data": content.decode("utf-8", errors="replace")}
    return None


def _analyze_file(
    file: DiscoveredFile,
    repo_path: Path,
    registry: DetectorRegistry,
    parser_manager: Any | None = None,
) -> tuple[DiscoveredFile, DetectorResult]:
    """Analyze a single file: read, parse, run detectors.

    This function is designed to be called from worker threads.
    Tree-sitter releases the GIL during parsing, so ThreadPoolExecutor
    gives real parallelism for the parse step.
    """
    abs_path = repo_path / file.path
    try:
        content = abs_path.read_bytes()
    except OSError:
        logger.warning("Could not read file %s", abs_path)
        return file, DetectorResult()

    tree = None
    parsed_data = None

    # Tree-sitter parse for supported languages
    if parser_manager is not None and file.language in _TREESITTER_LANGUAGES:
        try:
            tree = parser_manager.parse_file(file, content)
        except Exception:
            logger.debug("Tree-sitter parse failed for %s", file.path, exc_info=True)

    # Structured file parsing
    if file.language in _STRUCTURED_LANGUAGES:
        try:
            parsed_data = _parse_structured(file.language, content, str(file.path))
        except Exception:
            logger.debug("Structured parse failed for %s", file.path, exc_info=True)

    module_name = _derive_module_name(file.path, file.language)

    ctx = DetectorContext(
        file_path=str(file.path),
        language=file.language,
        content=content,
        tree=tree,
        parsed_data=parsed_data,
        module_name=module_name,
    )

    merged = DetectorResult()
    for detector in registry.detectors_for_language(file.language):
        try:
            result = detector.detect(ctx)
            merged.nodes.extend(result.nodes)
            merged.edges.extend(result.edges)
        except Exception:
            logger.warning(
                "Detector %s failed on %s",
                detector.name,
                file.path,
                exc_info=True,
            )

    return file, merged


def _derive_module_name(path: Path, language: str) -> str | None:
    """Best-effort module name from file path."""
    parts = path.parts
    joined = "/".join(parts)

    if language == "java":
        for marker in ("src/main/java/", "src/test/java/"):
            if marker in joined:
                idx = joined.index(marker) + len(marker)
                remainder = joined[idx:]
                pkg = remainder.rsplit("/", 1)[0] if "/" in remainder else ""
                return pkg.replace("/", ".") if pkg else None
        return None

    if language == "python":
        parent = path.parent
        if str(parent) == ".":
            return None
        return str(parent).replace("/", ".").replace("\\", ".")

    # For XML/YAML/etc., use parent directory as module name
    if language in _STRUCTURED_LANGUAGES:
        parent = path.parent
        if str(parent) == ".":
            return None
        return str(parent).replace("/", ".").replace("\\", ".")

    return None


class Analyzer:
    """Orchestrates the full code intelligence analysis pipeline.

    Steps:
    1. Discover files (FileDiscovery)
    2. If incremental, detect changed files and load cached results for unchanged
    3. Parse and run detectors on changed/new files
    4. Aggregate results in GraphBuilder
    5. Run cross-file linkers
    6. Cache new results
    7. Return AnalysisResult
    """

    def __init__(self, config: Config | None = None) -> None:
        self._config = config or Config()
        self._registry = DetectorRegistry()
        self._registry.load_builtin_detectors()
        self._registry.load_plugin_detectors()

        # Create ParserManager once (thread-safe via internal pool)
        self._parser_manager = None
        try:
            from code_intelligence.parsing.parser_manager import ParserManager
            self._parser_manager = ParserManager()
        except Exception:
            logger.warning("ParserManager unavailable, tree-sitter parsing disabled", exc_info=True)

    def run(
        self,
        repo_path: Path,
        incremental: bool = True,
        on_progress: Any | None = None,
    ) -> AnalysisResult:
        """Execute the analysis pipeline on *repo_path*.

        *on_progress*, when provided, is called with a status string at
        each major pipeline milestone.
        """
        def _report(msg: str) -> None:
            if on_progress is not None:
                on_progress(msg)

        repo_path = repo_path.resolve()

        # ----------------------------------------------------------
        # 1. Discover files
        # ----------------------------------------------------------
        _report("🔍 Discovering files…")
        discovery = FileDiscovery(self._config)
        all_files = discovery.discover(repo_path)
        current_commit = discovery.current_commit
        total_files = len(all_files)

        # Compute language breakdown and detector coverage
        language_breakdown: dict[str, int] = {}
        files_with_detectors = 0
        files_without_detectors = 0
        for f in all_files:
            language_breakdown[f.language] = language_breakdown.get(f.language, 0) + 1
            if self._registry.detectors_for_language(f.language):
                files_with_detectors += 1
            else:
                files_without_detectors += 1

        _report(f"📁 Found {total_files} files")
        logger.info("Discovered %d files in %s", total_files, repo_path)

        # ----------------------------------------------------------
        # 2. Determine which files need (re-)analysis
        # ----------------------------------------------------------
        cache_cfg = self._config.cache
        cache: CacheStore | None = None
        files_to_analyze: list[DiscoveredFile] = all_files
        files_cached = 0

        from code_intelligence.graph.backends import create_backend
        backend = create_backend(self._config.graph.backend, path=self._config.graph.path)
        builder = GraphBuilder(backend=backend)

        if cache_cfg.enabled:
            cache_path = repo_path / cache_cfg.directory / cache_cfg.db_name
            cache = CacheStore(cache_path)

        if incremental and cache is not None:
            last_commit = cache.get_last_commit()

            # Use ChangeDetector to find deleted files and purge stale cache
            if last_commit and current_commit and last_commit != current_commit:
                try:
                    change_detector = ChangeDetector()
                    changes = change_detector.detect_changes(repo_path, last_commit)
                    for changed in changes:
                        if changed.change_type == ChangeType.DELETED:
                            cache.remove_by_path(str(changed.path))
                        elif changed.change_type == ChangeType.MODIFIED:
                            cache.remove_by_path(str(changed.path))
                except Exception:
                    logger.debug("ChangeDetector failed, falling back to hash-based", exc_info=True)

            # Partition files into cached vs needs-analysis
            files_to_analyze = []
            for f in all_files:
                if cache.is_cached(f.content_hash):
                    nodes, edges = cache.load_cached_results(f.content_hash)
                    builder.add_nodes(nodes)
                    builder.add_edges(edges)
                    files_cached += 1
                else:
                    files_to_analyze.append(f)

            _report(f"💾 {files_cached} cached, {len(files_to_analyze)} to analyze")
            logger.info(
                "Incremental: %d cached, %d to analyze",
                files_cached,
                len(files_to_analyze),
            )

        files_analyzed = len(files_to_analyze)

        # ----------------------------------------------------------
        # 3 & 4. Parse and run detectors
        # ----------------------------------------------------------
        if files_to_analyze:
            _report(f"⚙️  Analyzing {files_analyzed} files…")
        parallelism = self._config.analysis.parallelism

        pm = self._parser_manager

        if parallelism <= 1 or len(files_to_analyze) <= 1:
            results = [
                _analyze_file(f, repo_path, self._registry, pm)
                for f in files_to_analyze
            ]
        else:
            max_workers = min(parallelism, len(files_to_analyze))
            # Use a list aligned with files_to_analyze to preserve
            # deterministic ordering regardless of thread completion order.
            result_slots: list[tuple[DiscoveredFile, DetectorResult] | None] = [None] * len(files_to_analyze)
            with ThreadPoolExecutor(max_workers=max_workers) as executor:
                futures = {
                    executor.submit(
                        _analyze_file, f, repo_path, self._registry, pm
                    ): idx
                    for idx, f in enumerate(files_to_analyze)
                }
                for future in as_completed(futures):
                    idx = futures[future]
                    try:
                        result_slots[idx] = future.result()
                    except Exception:
                        logger.warning(
                            "Analysis failed for %s",
                            files_to_analyze[idx].path,
                            exc_info=True,
                        )
            results = [r for r in result_slots if r is not None]

        # ----------------------------------------------------------
        # 5. Aggregate results into graph builder
        # ----------------------------------------------------------
        for file, detector_result in results:
            builder.merge_detector_result(detector_result)

            # Cache new results
            if cache is not None:
                try:
                    cache.store_results(
                        content_hash=file.content_hash,
                        file_path=str(file.path),
                        language=file.language,
                        nodes=detector_result.nodes,
                        edges=detector_result.edges,
                    )
                except Exception:
                    logger.warning(
                        "Failed to cache results for %s",
                        file.path,
                        exc_info=True,
                    )

        # ----------------------------------------------------------
        # 6. Run cross-file linkers
        # ----------------------------------------------------------
        _report("🔗 Linking cross-file relationships…")
        builder.run_linkers()

        # ----------------------------------------------------------
        # 6b. Classify layers (after linkers so all nodes are covered)
        # ----------------------------------------------------------
        from code_intelligence.classifiers.layer_classifier import LayerClassifier
        LayerClassifier().classify_store(builder._store)

        # ----------------------------------------------------------
        # 7. Record run and return result
        # ----------------------------------------------------------
        if cache is not None:
            try:
                cache.record_run(
                    commit_sha=current_commit or "",
                    file_count=total_files,
                )
            except Exception:
                logger.warning("Failed to record analysis run", exc_info=True)
            finally:
                cache.close()

        graph = builder.build()

        # Compute node breakdown
        node_breakdown: dict[str, int] = {}
        for node in graph.all_nodes():
            kind = node.kind.value
            node_breakdown[kind] = node_breakdown.get(kind, 0) + 1

        _report(f"✅ Analysis complete — {graph.node_count} nodes, {graph.edge_count} edges")
        logger.info(
            "Analysis complete: %d nodes, %d edges",
            graph.node_count,
            graph.edge_count,
        )

        return AnalysisResult(
            graph=graph,
            files_analyzed=files_analyzed,
            files_cached=files_cached,
            total_files=total_files,
            language_breakdown=language_breakdown,
            node_breakdown=node_breakdown,
            files_with_detectors=files_with_detectors,
            files_without_detectors=files_without_detectors,
        )
