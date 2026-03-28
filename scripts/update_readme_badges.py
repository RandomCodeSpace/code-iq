#!/usr/bin/env python3
"""Update dynamic badges in README.md with current project stats."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parent.parent
README = ROOT / "README.md"


def _run(cmd: list[str], **kwargs) -> str:
    return subprocess.check_output(cmd, cwd=ROOT, text=True, **kwargs).strip()


def count_files() -> int:
    src = list((ROOT / "src").rglob("*.py"))
    tests = list((ROOT / "tests").rglob("*.py"))
    return len(src) + len(tests)


def count_loc() -> int:
    total = 0
    for d in ("src", "tests"):
        for f in (ROOT / d).rglob("*.py"):
            total += sum(1 for _ in f.open())
    return total


def count_tests() -> int:
    try:
        out = _run(
            [sys.executable, "-m", "pytest", "tests/", "-q", "--tb=no", "--no-header"],
            stderr=subprocess.STDOUT,
        )
    except subprocess.CalledProcessError as e:
        out = e.output
    # Match "113 passed" from pytest output
    m = re.search(r"(\d+) passed", out)
    return int(m.group(1)) if m else 0


def count_detectors_and_languages() -> tuple[int, int]:
    out = _run([
        sys.executable, "-c",
        "from code_intelligence.detectors.registry import DetectorRegistry; "
        "r = DetectorRegistry(); r.load_builtin_detectors(); "
        "langs = {l for d in r.all_detectors() for l in d.supported_languages}; "
        "print(len(r.all_detectors()), len(langs))",
    ])
    parts = out.strip().split()
    return int(parts[0]), int(parts[1])


def count_vulnerabilities() -> int:
    """Count open Dependabot alerts via gh CLI (requires repo access)."""
    try:
        out = _run(["gh", "api", "repos/RandomCodeSpace/code-iq/dependabot/alerts?state=open&per_page=100"])
        alerts = json.loads(out)
        return len(alerts) if isinstance(alerts, list) else 0
    except Exception:
        # Fallback: try GITHUB_TOKEN env var or return -1 to skip update
        return -1


def fmt(n: int) -> str:
    """Format number with commas for display, URL-encoded."""
    return f"{n:,}"


def badge(label: str, value: str, color: str, logo: str) -> str:
    """Generate a shields.io badge HTML snippet."""
    val_encoded = quote(value, safe="")
    link = "https://github.com/RandomCodeSpace/code-iq"
    if label == "vulnerabilities":
        link += "/security/dependabot"
    return (
        f'<a href="{link}">'
        f'<img src="https://img.shields.io/badge/{label}-{val_encoded}-{color}'
        f'?style=flat-square&logo={logo}&logoColor=white" alt="{value} {label.capitalize()}">'
        f"</a>"
    )


def update_badge(content: str, name: str, new_badge: str) -> str:
    """Replace content between DYNAMIC markers."""
    pattern = rf"(<!-- DYNAMIC:{name} -->).*?(<!-- /DYNAMIC:{name} -->)"
    replacement = rf"\g<1>{new_badge}\g<2>"
    return re.sub(pattern, replacement, content)


def main() -> None:
    print("Collecting project stats...")

    files = count_files()
    loc = count_loc()
    tests = count_tests()
    detectors, languages = count_detectors_and_languages()
    vulns = count_vulnerabilities()

    print(f"  Files: {files}")
    print(f"  LOC: {loc:,}")
    print(f"  Tests: {tests}")
    print(f"  Detectors: {detectors}")
    print(f"  Languages: {languages}")
    print(f"  Vulnerabilities: {vulns if vulns >= 0 else 'skipped (no access)'}")

    content = README.read_text()
    original = content

    content = update_badge(
        content, "detectors",
        badge("detectors", str(detectors), "brightgreen", "codefactor"),
    )
    content = update_badge(
        content, "languages",
        badge("languages", str(languages), "blue", "stackblitz"),
    )
    content = update_badge(
        content, "tests",
        badge("tests", f"{tests} passed", "brightgreen", "pytest"),
    )
    content = update_badge(
        content, "files",
        badge("files", str(files), "informational", "files"),
    )
    content = update_badge(
        content, "loc",
        badge("LOC", fmt(loc), "informational", "codacy"),
    )
    if vulns >= 0:
        vuln_color = "brightgreen" if vulns == 0 else "yellow" if vulns <= 3 else "red"
        vuln_label = "0" if vulns == 0 else str(vulns)
        content = update_badge(
            content, "vulnerabilities",
            badge("vulnerabilities", vuln_label, vuln_color, "hackthebox"),
        )

    if content != original:
        README.write_text(content)
        print("README.md updated.")
    else:
        print("No changes needed.")


if __name__ == "__main__":
    main()
