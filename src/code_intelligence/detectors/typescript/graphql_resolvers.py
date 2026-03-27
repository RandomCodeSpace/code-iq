"""GraphQL resolver detector for TypeScript/JavaScript."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation


class GraphQLResolverDetector:
    """Detects GraphQL resolvers and type definitions in TypeScript/JavaScript."""

    name: str = "typescript.graphql_resolvers"
    supported_languages: tuple[str, ...] = ("typescript", "javascript")

    # NestJS GraphQL: @Query(), @Mutation(), @Resolver()
    _NESTJS_RESOLVER = re.compile(
        r"@Resolver\(\s*(?:of\s*=>\s*)?(\w+)?\s*\)\s*\n\s*(?:export\s+)?class\s+(\w+)"
    )
    _NESTJS_QUERY = re.compile(
        r"@(Query|Mutation|Subscription)\(.*?\)\s*\n\s*(?:async\s+)?(\w+)"
    )

    # Apollo/generic: type Query { ... } or typeDefs with gql template
    _TYPEDEF_PATTERN = re.compile(
        r"type\s+(Query|Mutation|Subscription)\s*\{([^}]+)\}"
    )

    # resolvers object: Query: { users: ... }
    _RESOLVER_FIELD = re.compile(
        r"(Query|Mutation|Subscription)\s*:\s*\{([^}]+)\}"
    )

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")

        # NestJS-style resolvers
        for match in self._NESTJS_RESOLVER.finditer(text):
            entity_type = match.group(1)
            class_name = match.group(2)
            line = text[:match.start()].count("\n") + 1

            class_id = f"class:{ctx.file_path}::{class_name}"
            result.nodes.append(GraphNode(
                id=class_id,
                kind=NodeKind.CLASS,
                label=class_name,
                fqn=f"{ctx.file_path}::{class_name}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                annotations=["@Resolver"],
                properties={"framework": "nestjs-graphql", "entity_type": entity_type},
            ))

        for match in self._NESTJS_QUERY.finditer(text):
            op_type = match.group(1)
            func_name = match.group(2)
            line = text[:match.start()].count("\n") + 1

            node_id = f"endpoint:{ctx.module_name or ''}:graphql:{op_type}:{func_name}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.ENDPOINT,
                label=f"GraphQL {op_type}: {func_name}",
                fqn=f"{ctx.file_path}::{func_name}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                properties={
                    "protocol": "GraphQL",
                    "operation_type": op_type.lower(),
                    "field_name": func_name,
                },
            ))

        # Schema-defined resolvers
        for match in self._TYPEDEF_PATTERN.finditer(text):
            op_type = match.group(1)
            fields_block = match.group(2)
            base_line = text[:match.start()].count("\n") + 1

            for field_match in re.finditer(r"(\w+)\s*(?:\([^)]*\))?\s*:", fields_block):
                field_name = field_match.group(1)
                node_id = f"endpoint:{ctx.module_name or ''}:graphql:{op_type}:{field_name}"
                result.nodes.append(GraphNode(
                    id=node_id,
                    kind=NodeKind.ENDPOINT,
                    label=f"GraphQL {op_type}: {field_name}",
                    module=ctx.module_name,
                    location=SourceLocation(file_path=ctx.file_path, line_start=base_line),
                    properties={
                        "protocol": "GraphQL",
                        "operation_type": op_type.lower(),
                        "field_name": field_name,
                    },
                ))

        return result
