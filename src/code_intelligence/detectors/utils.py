"""Shared utilities for code intelligence detectors."""

from __future__ import annotations

from typing import Iterator

from code_intelligence.detectors.base import DetectorContext


def decode_text(ctx: DetectorContext) -> str:
    """Decode raw bytes to text, handling encoding errors gracefully."""
    return ctx.content.decode("utf-8", errors="replace")


def iter_lines(ctx: DetectorContext) -> Iterator[tuple[int, str]]:
    """Yield (line_number, line_text) tuples from detector context.

    Line numbers are 1-based (matching source file conventions).
    """
    text = decode_text(ctx)
    for i, line in enumerate(text.split("\n")):
        yield i + 1, line


def find_line_number(text: str, byte_offset: int) -> int:
    """Find the 1-based line number for a byte offset in text."""
    return text[:byte_offset].count("\n") + 1


def filename(ctx: DetectorContext) -> str:
    """Extract the filename (without path) from the detector context."""
    return ctx.file_path.rsplit("/", 1)[-1] if "/" in ctx.file_path else ctx.file_path


def matches_filename(ctx: DetectorContext, *patterns: str) -> bool:
    """Check if the file matches any of the given filename patterns.

    Supports exact match and prefix+suffix matching.
    Examples: matches_filename(ctx, "package.json", "tsconfig.*.json")
    """
    name = filename(ctx)
    for pattern in patterns:
        if "*" in pattern:
            prefix, suffix = pattern.split("*", 1)
            if name.startswith(prefix) and name.endswith(suffix):
                return True
        elif name == pattern:
            return True
    return False
