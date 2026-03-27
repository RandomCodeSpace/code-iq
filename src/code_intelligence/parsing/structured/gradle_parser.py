"""Regex-based Gradle build file parser."""

from __future__ import annotations

import re
from typing import Any

# Patterns for Gradle dependency declarations.
# Matches: implementation 'group:artifact:version'
#          api "group:artifact:version"
#          compile 'group:artifact:version'
#          testImplementation("group:artifact:version")
_DEP_CONFIGS = (
    "implementation",
    "api",
    "compile",
    "compileOnly",
    "runtimeOnly",
    "testImplementation",
    "testCompile",
    "testRuntimeOnly",
    "annotationProcessor",
    "kapt",
)

_DEP_PATTERN = re.compile(
    r"(?P<config>"
    + "|".join(_DEP_CONFIGS)
    + r")\s*[\(\s]['\"](?P<coords>[^'\"]+)['\"]",
    re.MULTILINE,
)

# Plugin patterns: id 'xxx' or id("xxx")
_PLUGIN_PATTERN = re.compile(
    r"""id\s*[\(\s]['"](?P<plugin>[^'"]+)['"]""",
    re.MULTILINE,
)

# Group / version from the build file.
_GROUP_PATTERN = re.compile(r"""group\s*=\s*['"](?P<group>[^'"]+)['"]""")
_VERSION_PATTERN = re.compile(r"""version\s*=\s*['"](?P<version>[^'"]+)['"]""")


class GradleParser:
    """Extracts dependencies and metadata from ``build.gradle`` files."""

    def parse(self, content: bytes, file_path: str) -> dict[str, Any]:
        """Parse a Gradle build file and return structured data."""
        text = content.decode("utf-8", errors="replace")

        dependencies: list[dict[str, str]] = []
        for m in _DEP_PATTERN.finditer(text):
            config = m.group("config")
            coords = m.group("coords")
            parts = coords.split(":")
            dep: dict[str, str] = {"configuration": config, "raw": coords}
            if len(parts) >= 2:
                dep["group"] = parts[0]
                dep["artifact"] = parts[1]
            if len(parts) >= 3:
                dep["version"] = parts[2]
            dependencies.append(dep)

        plugins: list[str] = [
            m.group("plugin") for m in _PLUGIN_PATTERN.finditer(text)
        ]

        group_m = _GROUP_PATTERN.search(text)
        version_m = _VERSION_PATTERN.search(text)

        return {
            "type": "gradle",
            "file": file_path,
            "group": group_m.group("group") if group_m else None,
            "version": version_m.group("version") if version_m else None,
            "plugins": plugins,
            "dependencies": dependencies,
        }
