"""Tests for detectors/utils.py — shared detector utilities."""

from __future__ import annotations

import pytest

from osscodeiq.detectors.base import DetectorContext
from osscodeiq.detectors.utils import (
    decode_text,
    find_line_number,
    filename,
    iter_lines,
    matches_filename,
)


def _ctx(file_path: str = "src/foo/bar.py", content: bytes = b"") -> DetectorContext:
    """Helper to build a DetectorContext with minimal fields."""
    return DetectorContext(file_path=file_path, language="python", content=content)


# ---------- decode_text ----------


class TestDecodeText:
    def test_utf8_basic(self):
        ctx = _ctx(content=b"hello world")
        assert decode_text(ctx) == "hello world"

    def test_utf8_multibyte(self):
        ctx = _ctx(content="caf\u00e9".encode("utf-8"))
        assert decode_text(ctx) == "caf\u00e9"

    def test_empty_bytes(self):
        ctx = _ctx(content=b"")
        assert decode_text(ctx) == ""

    def test_invalid_utf8_replaced(self):
        # 0xFF is not valid UTF-8; errors="replace" should produce a replacement char
        ctx = _ctx(content=b"\xff\xfe")
        result = decode_text(ctx)
        assert "\ufffd" in result  # replacement character

    def test_latin1_bytes_replaced(self):
        # Latin-1 encoded e-acute (0xe9) is invalid standalone UTF-8
        ctx = _ctx(content=b"\xe9")
        result = decode_text(ctx)
        assert result is not None
        assert len(result) > 0

    def test_newlines_preserved(self):
        ctx = _ctx(content=b"line1\nline2\r\nline3")
        assert decode_text(ctx) == "line1\nline2\r\nline3"


# ---------- iter_lines ----------


class TestIterLines:
    def test_basic_lines(self):
        ctx = _ctx(content=b"line1\nline2\nline3")
        lines = list(iter_lines(ctx))
        assert len(lines) == 3
        assert lines[0] == (1, "line1")
        assert lines[1] == (2, "line2")
        assert lines[2] == (3, "line3")

    def test_single_line(self):
        ctx = _ctx(content=b"only one line")
        lines = list(iter_lines(ctx))
        assert len(lines) == 1
        assert lines[0] == (1, "only one line")

    def test_empty_content(self):
        ctx = _ctx(content=b"")
        lines = list(iter_lines(ctx))
        assert len(lines) == 1  # split("") gives [""]
        assert lines[0] == (1, "")

    def test_trailing_newline(self):
        ctx = _ctx(content=b"a\nb\n")
        lines = list(iter_lines(ctx))
        assert len(lines) == 3  # "a", "b", ""
        assert lines[2] == (3, "")

    def test_line_numbers_are_1_based(self):
        ctx = _ctx(content=b"first\nsecond\nthird")
        lines = list(iter_lines(ctx))
        for lineno, _ in lines:
            assert lineno >= 1


# ---------- find_line_number ----------


class TestFindLineNumber:
    def test_first_line(self):
        content = "line1\nline2\nline3"
        assert find_line_number(content, 0) == 1

    def test_second_line(self):
        content = "line1\nline2\nline3"
        # byte offset 6 is the start of "line2"
        assert find_line_number(content, 6) == 2

    def test_third_line(self):
        content = "line1\nline2\nline3"
        # byte offset 12 is the start of "line3"
        assert find_line_number(content, 12) == 3

    def test_offset_zero(self):
        assert find_line_number("abc\ndef", 0) == 1

    def test_offset_at_newline(self):
        content = "abc\ndef"
        # offset 3 is the newline itself
        assert find_line_number(content, 3) == 1
        # offset 4 is 'd'
        assert find_line_number(content, 4) == 2


# ---------- filename ----------


class TestFilename:
    def test_nested_path(self):
        ctx = _ctx(file_path="src/foo/bar.py")
        assert filename(ctx) == "bar.py"

    def test_root_file(self):
        ctx = _ctx(file_path="setup.py")
        assert filename(ctx) == "setup.py"

    def test_deeply_nested(self):
        ctx = _ctx(file_path="a/b/c/d/e.txt")
        assert filename(ctx) == "e.txt"

    def test_no_extension(self):
        ctx = _ctx(file_path="src/Dockerfile")
        assert filename(ctx) == "Dockerfile"


# ---------- matches_filename ----------


class TestMatchesFilename:
    def test_exact_match(self):
        ctx = _ctx(file_path="path/to/Dockerfile")
        assert matches_filename(ctx, "Dockerfile") is True

    def test_exact_no_match(self):
        ctx = _ctx(file_path="path/to/setup.py")
        assert matches_filename(ctx, "Dockerfile") is False

    def test_wildcard_match(self):
        ctx = _ctx(file_path="path/to/tsconfig.app.json")
        assert matches_filename(ctx, "tsconfig.*.json") is True

    def test_wildcard_no_match(self):
        ctx = _ctx(file_path="path/to/package.json")
        assert matches_filename(ctx, "tsconfig.*.json") is False

    def test_multiple_patterns(self):
        ctx = _ctx(file_path="path/to/package.json")
        assert matches_filename(ctx, "Dockerfile", "package.json") is True

    def test_multiple_patterns_no_match(self):
        ctx = _ctx(file_path="path/to/main.py")
        assert matches_filename(ctx, "Dockerfile", "package.json") is False

    def test_wildcard_prefix_suffix(self):
        ctx = _ctx(file_path="path/to/docker-compose.override.yml")
        assert matches_filename(ctx, "docker-compose.*.yml") is True
