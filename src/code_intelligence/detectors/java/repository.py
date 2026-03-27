"""Spring Data repository detector for Java source files."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

_REPO_EXTENDS_RE = re.compile(
    r"interface\s+(\w+)\s+extends\s+((?:JpaRepository|CrudRepository|"
    r"PagingAndSortingRepository|ReactiveCrudRepository|"
    r"MongoRepository|ElasticsearchRepository|"
    r"R2dbcRepository|JpaSpecificationExecutor)\w*)"
    r"(?:<\s*(\w+)\s*,\s*[\w<>]+\s*>)?"
)
_REPOSITORY_ANNO_RE = re.compile(r"@Repository")
_INTERFACE_RE = re.compile(r"interface\s+(\w+)")
_GENERIC_PARAMS_RE = re.compile(r"<\s*(\w+)\s*,")
_QUERY_RE = re.compile(r'@Query\s*\(\s*(?:value\s*=\s*)?"([^"]+)"')
_METHOD_RE = re.compile(r"(?:public\s+)?(?:[\w<>\[\],?\s]+)\s+(\w+)\s*\(")


class RepositoryDetector:
    """Detects Spring Data repository interfaces."""

    name: str = "spring_repository"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        lines = text.split("\n")

        # Check for repository patterns
        has_repo_annotation = _REPOSITORY_ANNO_RE.search(text) is not None
        extends_match = _REPO_EXTENDS_RE.search(text)

        if not extends_match and not has_repo_annotation:
            return result

        # Find interface name and entity type
        interface_name: str | None = None
        entity_type: str | None = None
        parent_repo: str | None = None
        interface_line: int = 0

        if extends_match:
            interface_name = extends_match.group(1)
            parent_repo = extends_match.group(2)
            entity_type = extends_match.group(3)
            # Find line number
            for i, line in enumerate(lines):
                if interface_name and interface_name in line and "interface" in line:
                    interface_line = i + 1
                    break
        else:
            # Just @Repository on a class/interface
            for i, line in enumerate(lines):
                im = _INTERFACE_RE.search(line)
                if im:
                    interface_name = im.group(1)
                    interface_line = i + 1
                    # Try to extract generic params
                    gm = _GENERIC_PARAMS_RE.search(line)
                    if gm:
                        entity_type = gm.group(1)
                    break

        if not interface_name:
            return result

        repo_id = f"{ctx.file_path}:{interface_name}"

        properties: dict[str, object] = {}
        if parent_repo:
            properties["extends"] = parent_repo
        if entity_type:
            properties["entity_type"] = entity_type

        # Extract @Query methods
        custom_queries: list[dict[str, str]] = []
        for i, line in enumerate(lines):
            qm = _QUERY_RE.search(line)
            if qm:
                query_str = qm.group(1)
                # Find method name on next lines
                method_name = None
                for k in range(i + 1, min(i + 4, len(lines))):
                    mm = _METHOD_RE.search(lines[k])
                    if mm:
                        method_name = mm.group(1)
                        break
                custom_queries.append({
                    "query": query_str,
                    "method": method_name or "unknown",
                })

        if custom_queries:
            properties["custom_queries"] = custom_queries

        node = GraphNode(
            id=repo_id,
            kind=NodeKind.REPOSITORY,
            label=interface_name,
            fqn=interface_name,
            module=ctx.module_name,
            location=SourceLocation(file_path=ctx.file_path, line_start=interface_line),
            annotations=["@Repository"] if has_repo_annotation else [],
            properties=properties,
        )
        result.nodes.append(node)

        # Edge to entity
        if entity_type:
            edge = GraphEdge(
                source=repo_id,
                target=f"*:{entity_type}",
                kind=EdgeKind.QUERIES,
                label=f"{interface_name} queries {entity_type}",
            )
            result.edges.append(edge)

        return result
