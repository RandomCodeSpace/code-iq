"""Django model detector."""

from __future__ import annotations

import re

from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.utils import decode_text
from osscodeiq.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation


class DjangoModelDetector:
    """Detects Django model, manager, and relationship definitions."""

    name: str = "python.django_models"
    supported_languages: tuple[str, ...] = ("python",)

    _DJANGO_MODEL_RE = re.compile(
        r"^class\s+(\w+)\s*\(\s*[\w.]*Model\s*\)", re.MULTILINE
    )
    _FK_RE = re.compile(
        r"(\w+)\s*=\s*models\.(?:ForeignKey|OneToOneField)\s*\(\s*[\"']?(\w+)",
        re.MULTILINE,
    )
    _M2M_RE = re.compile(
        r"(\w+)\s*=\s*models\.ManyToManyField\s*\(\s*[\"']?(\w+)", re.MULTILINE
    )
    _FIELD_RE = re.compile(r"(\w+)\s*=\s*models\.(\w+Field)\s*\(", re.MULTILINE)
    _META_TABLE_RE = re.compile(r"db_table\s*=\s*[\"'](\w+)[\"']")
    _META_ORDERING_RE = re.compile(r"ordering\s*=\s*(\[.*?\])")
    _MANAGER_RE = re.compile(
        r"^class\s+(\w+)\s*\(\s*[\w.]*Manager\s*\)", re.MULTILINE
    )
    _MANAGER_ASSIGNMENT_RE = re.compile(r"(\w+)\s*=\s*(\w+)\s*\(\s*\)", re.MULTILINE)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)

        # Detect managers first so we can link them
        manager_names: dict[str, str] = {}
        for match in self._MANAGER_RE.finditer(text):
            mgr_name = match.group(1)
            line = text[: match.start()].count("\n") + 1
            node_id = f"django:{ctx.file_path}:manager:{mgr_name}"
            manager_names[mgr_name] = node_id
            result.nodes.append(
                GraphNode(
                    id=node_id,
                    kind=NodeKind.REPOSITORY,
                    label=mgr_name,
                    fqn=f"{ctx.file_path}::{mgr_name}",
                    module=ctx.module_name,
                    location=SourceLocation(file_path=ctx.file_path, line_start=line),
                    properties={"framework": "django", "type": "manager"},
                )
            )

        # Detect models
        for match in self._DJANGO_MODEL_RE.finditer(text):
            class_name = match.group(1)
            line = text[: match.start()].count("\n") + 1

            # Determine class body boundaries
            class_start = match.start()
            next_class = re.search(r"\nclass\s+\w+", text[match.end() :])
            class_body = (
                text[class_start : match.end() + next_class.start()]
                if next_class
                else text[class_start:]
            )

            # Extract fields
            fields: dict[str, str] = {}
            for fm in self._FIELD_RE.finditer(class_body):
                fields[fm.group(1)] = fm.group(2)

            # Extract Meta properties
            table_name: str | None = None
            ordering: str | None = None
            meta_match = re.search(r"class\s+Meta\s*:", class_body)
            if meta_match:
                meta_start = meta_match.end()
                meta_end = len(class_body)
                for cm in re.finditer(r"\n\s{4}\S", class_body[meta_start:]):
                    meta_end = meta_start + cm.start()
                    break
                meta_block = class_body[meta_start:meta_end]
                table_match = self._META_TABLE_RE.search(meta_block)
                if table_match:
                    table_name = table_match.group(1)
                ordering_match = self._META_ORDERING_RE.search(meta_block)
                if ordering_match:
                    ordering = ordering_match.group(1)

            node_id = f"django:{ctx.file_path}:model:{class_name}"
            props: dict = {
                "fields": fields,
                "framework": "django",
            }
            if table_name:
                props["table_name"] = table_name
            if ordering:
                props["ordering"] = ordering

            result.nodes.append(
                GraphNode(
                    id=node_id,
                    kind=NodeKind.ENTITY,
                    label=class_name,
                    fqn=f"{ctx.file_path}::{class_name}",
                    module=ctx.module_name,
                    location=SourceLocation(file_path=ctx.file_path, line_start=line),
                    properties=props,
                )
            )

            # FK / OneToOne edges
            for fk in self._FK_RE.finditer(class_body):
                target = fk.group(2)
                target_id = f"django:{ctx.file_path}:model:{target}"
                result.edges.append(
                    GraphEdge(
                        source=node_id,
                        target=target_id,
                        kind=EdgeKind.DEPENDS_ON,
                        label=fk.group(1),
                    )
                )

            # M2M edges
            for m2m in self._M2M_RE.finditer(class_body):
                target = m2m.group(2)
                target_id = f"django:{ctx.file_path}:model:{target}"
                result.edges.append(
                    GraphEdge(
                        source=node_id,
                        target=target_id,
                        kind=EdgeKind.DEPENDS_ON,
                        label=m2m.group(1),
                    )
                )

            # Manager assignments (objects = MyManager())
            for ma in self._MANAGER_ASSIGNMENT_RE.finditer(class_body):
                mgr_class = ma.group(2)
                if mgr_class in manager_names:
                    result.edges.append(
                        GraphEdge(
                            source=node_id,
                            target=manager_names[mgr_class],
                            kind=EdgeKind.QUERIES,
                            label=ma.group(1),
                        )
                    )

        return result
