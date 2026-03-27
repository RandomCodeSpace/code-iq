"""GraphQL resolver detector for Java source files."""

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

_CLASS_RE = re.compile(r"(?:public\s+)?class\s+(\w+)")

# Spring GraphQL annotations
_QUERY_MAPPING_RE = re.compile(r"@QueryMapping(?:\s*\(\s*(?:name\s*=\s*)?\"([^\"]+)\"\s*\))?")
_MUTATION_MAPPING_RE = re.compile(r"@MutationMapping(?:\s*\(\s*(?:name\s*=\s*)?\"([^\"]+)\"\s*\))?")
_SUBSCRIPTION_MAPPING_RE = re.compile(r"@SubscriptionMapping(?:\s*\(\s*(?:name\s*=\s*)?\"([^\"]+)\"\s*\))?")
_SCHEMA_MAPPING_RE = re.compile(r'@SchemaMapping\s*\(\s*(?:typeName\s*=\s*"([^"]+)")?')
_BATCH_MAPPING_RE = re.compile(r"@BatchMapping(?:\s*\(\s*(?:field\s*=\s*)?\"([^\"]+)\"\s*\))?")

# DGS framework annotations
_DGS_QUERY_RE = re.compile(r"@DgsQuery(?:\s*\(\s*field\s*=\s*\"([^\"]+)\"\s*\))?")
_DGS_MUTATION_RE = re.compile(r"@DgsMutation(?:\s*\(\s*field\s*=\s*\"([^\"]+)\"\s*\))?")
_DGS_SUBSCRIPTION_RE = re.compile(r"@DgsSubscription(?:\s*\(\s*field\s*=\s*\"([^\"]+)\"\s*\))?")
_DGS_DATA_RE = re.compile(r"@DgsData\s*\(\s*parentType\s*=\s*\"([^\"]+)\"(?:\s*,\s*field\s*=\s*\"([^\"]+)\")?")

_METHOD_RE = re.compile(r"(?:public|protected|private)?\s*(?:[\w<>\[\],?\s]+)\s+(\w+)\s*\(")

# Controller annotation for GraphQL controllers
_CONTROLLER_RE = re.compile(r"@(?:Controller|DgsComponent)")


class GraphqlResolverDetector:
    """Detects GraphQL resolvers from Spring GraphQL and DGS framework annotations."""

    name: str = "graphql_resolver"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        lines = text.split("\n")

        # Quick check for relevant annotations
        if not any(kw in text for kw in (
            "@QueryMapping", "@MutationMapping", "@SubscriptionMapping",
            "@SchemaMapping", "@BatchMapping",
            "@DgsQuery", "@DgsMutation", "@DgsSubscription", "@DgsData",
        )):
            return result

        # Find class name
        class_name: str | None = None
        for line in lines:
            cm = _CLASS_RE.search(line)
            if cm:
                class_name = cm.group(1)
                break

        if not class_name:
            return result

        class_node_id = f"{ctx.file_path}:{class_name}"

        # All annotation patterns to scan
        patterns = [
            (_QUERY_MAPPING_RE, "Query"),
            (_MUTATION_MAPPING_RE, "Mutation"),
            (_SUBSCRIPTION_MAPPING_RE, "Subscription"),
            (_DGS_QUERY_RE, "Query"),
            (_DGS_MUTATION_RE, "Mutation"),
            (_DGS_SUBSCRIPTION_RE, "Subscription"),
        ]

        for i, line in enumerate(lines):
            for pattern, gql_type in patterns:
                m = pattern.search(line)
                if not m:
                    continue

                field_name = m.group(1) if m.lastindex and m.group(1) else None
                # Resolve field name from method if not in annotation
                if not field_name:
                    for k in range(i + 1, min(i + 4, len(lines))):
                        mm = _METHOD_RE.search(lines[k])
                        if mm:
                            field_name = mm.group(1)
                            break

                if not field_name:
                    continue

                resolver_id = f"{ctx.file_path}:{class_name}:{gql_type}:{field_name}"
                result.nodes.append(GraphNode(
                    id=resolver_id,
                    kind=NodeKind.ENDPOINT,
                    label=f"GraphQL {gql_type}.{field_name}",
                    fqn=f"{class_name}.{field_name}",
                    module=ctx.module_name,
                    location=SourceLocation(file_path=ctx.file_path, line_start=i + 1),
                    properties={
                        "graphql_type": gql_type,
                        "field": field_name,
                        "protocol": "graphql",
                    },
                ))

                result.edges.append(GraphEdge(
                    source=class_node_id,
                    target=resolver_id,
                    kind=EdgeKind.EXPOSES,
                    label=f"{class_name} exposes {gql_type}.{field_name}",
                ))

            # Handle @SchemaMapping
            sm = _SCHEMA_MAPPING_RE.search(line)
            if sm:
                type_name = sm.group(1) or "Unknown"
                method_name = None
                for k in range(i + 1, min(i + 4, len(lines))):
                    mm = _METHOD_RE.search(lines[k])
                    if mm:
                        method_name = mm.group(1)
                        break

                if method_name:
                    resolver_id = f"{ctx.file_path}:{class_name}:SchemaMapping:{type_name}.{method_name}"
                    result.nodes.append(GraphNode(
                        id=resolver_id,
                        kind=NodeKind.ENDPOINT,
                        label=f"GraphQL {type_name}.{method_name}",
                        fqn=f"{class_name}.{method_name}",
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=i + 1),
                        properties={
                            "graphql_type": type_name,
                            "field": method_name,
                            "protocol": "graphql",
                        },
                    ))
                    result.edges.append(GraphEdge(
                        source=class_node_id,
                        target=resolver_id,
                        kind=EdgeKind.EXPOSES,
                        label=f"{class_name} exposes {type_name}.{method_name}",
                    ))

            # Handle @DgsData
            dm = _DGS_DATA_RE.search(line)
            if dm:
                parent_type = dm.group(1)
                field_name = dm.group(2) if dm.lastindex and dm.lastindex >= 2 and dm.group(2) else None
                if not field_name:
                    for k in range(i + 1, min(i + 4, len(lines))):
                        mm = _METHOD_RE.search(lines[k])
                        if mm:
                            field_name = mm.group(1)
                            break

                if field_name:
                    resolver_id = f"{ctx.file_path}:{class_name}:DgsData:{parent_type}.{field_name}"
                    result.nodes.append(GraphNode(
                        id=resolver_id,
                        kind=NodeKind.ENDPOINT,
                        label=f"GraphQL {parent_type}.{field_name}",
                        fqn=f"{class_name}.{field_name}",
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=i + 1),
                        properties={
                            "graphql_type": parent_type,
                            "field": field_name,
                            "protocol": "graphql",
                            "framework": "dgs",
                        },
                    ))
                    result.edges.append(GraphEdge(
                        source=class_node_id,
                        target=resolver_id,
                        kind=EdgeKind.EXPOSES,
                        label=f"{class_name} exposes {parent_type}.{field_name}",
                    ))

        return result
