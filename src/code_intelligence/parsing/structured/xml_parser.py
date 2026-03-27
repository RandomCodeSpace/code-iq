"""XML structured file parser with special handling for Maven and Spring."""

from __future__ import annotations

from typing import Any

from lxml import etree


class XmlParser:
    """Parses XML files into structured dictionaries.

    Provides specialised extraction for:
    - Maven ``pom.xml`` (groupId, artifactId, dependencies, modules)
    - Spring XML configuration (beans, component-scan)
    """

    def parse(self, content: bytes, file_path: str) -> dict[str, Any]:
        """Parse *content* and return a structured dict."""
        try:
            parser = etree.XMLParser(resolve_entities=False, no_network=True)
            root = etree.fromstring(content, parser)
        except etree.XMLSyntaxError:
            return {"error": "invalid_xml", "file": file_path}

        file_lower = file_path.rsplit("/", 1)[-1].lower()

        if file_lower == "pom.xml":
            return self._parse_pom(root, file_path)

        # Detect Spring XML config by namespace or root tag.
        root_tag = etree.QName(root.tag).localname if root.tag else ""
        ns = root.nsmap.get(None, "")
        if root_tag == "beans" or "springframework" in ns:
            return self._parse_spring_xml(root, file_path)

        # Generic XML: return tag tree summary.
        return {
            "type": "xml",
            "file": file_path,
            "root_tag": root_tag,
            "namespaces": dict(root.nsmap),
        }

    # ------------------------------------------------------------------
    # Maven POM parsing
    # ------------------------------------------------------------------

    def _parse_pom(
        self, root: etree._Element, file_path: str
    ) -> dict[str, Any]:
        ns = root.nsmap.get(None, "")
        prefix = f"{{{ns}}}" if ns else ""

        def _text(parent: etree._Element, tag: str) -> str | None:
            el = parent.find(f"{prefix}{tag}")
            return el.text.strip() if el is not None and el.text else None

        group_id = _text(root, "groupId")
        artifact_id = _text(root, "artifactId")
        version = _text(root, "version")
        packaging = _text(root, "packaging")

        # Parent info
        parent_el = root.find(f"{prefix}parent")
        parent: dict[str, str | None] | None = None
        if parent_el is not None:
            parent = {
                "groupId": _text(parent_el, "groupId"),
                "artifactId": _text(parent_el, "artifactId"),
                "version": _text(parent_el, "version"),
            }
            if group_id is None:
                group_id = parent.get("groupId")

        # Dependencies
        deps: list[dict[str, str | None]] = []
        deps_el = root.find(f"{prefix}dependencies")
        if deps_el is not None:
            for dep in deps_el.findall(f"{prefix}dependency"):
                deps.append(
                    {
                        "groupId": _text(dep, "groupId"),
                        "artifactId": _text(dep, "artifactId"),
                        "version": _text(dep, "version"),
                        "scope": _text(dep, "scope"),
                    }
                )

        # Modules
        modules: list[str] = []
        modules_el = root.find(f"{prefix}modules")
        if modules_el is not None:
            for mod in modules_el.findall(f"{prefix}module"):
                if mod.text:
                    modules.append(mod.text.strip())

        return {
            "type": "pom",
            "file": file_path,
            "groupId": group_id,
            "artifactId": artifact_id,
            "version": version,
            "packaging": packaging,
            "parent": parent,
            "dependencies": deps,
            "modules": modules,
        }

    # ------------------------------------------------------------------
    # Spring XML config parsing
    # ------------------------------------------------------------------

    def _parse_spring_xml(
        self, root: etree._Element, file_path: str
    ) -> dict[str, Any]:
        ns = root.nsmap.get(None, "")
        prefix = f"{{{ns}}}" if ns else ""

        beans: list[dict[str, str | None]] = []
        for bean in root.iter(f"{prefix}bean"):
            beans.append(
                {
                    "id": bean.get("id"),
                    "class": bean.get("class"),
                    "scope": bean.get("scope"),
                }
            )

        # context:component-scan
        component_scans: list[str] = []
        ctx_ns = None
        for pfx, uri in root.nsmap.items():
            if "context" in (uri or ""):
                ctx_ns = uri
                break
        if ctx_ns:
            for scan in root.iter(f"{{{ctx_ns}}}component-scan"):
                pkg = scan.get("base-package")
                if pkg:
                    component_scans.append(pkg)

        return {
            "type": "spring_xml",
            "file": file_path,
            "beans": beans,
            "component_scans": component_scans,
        }
