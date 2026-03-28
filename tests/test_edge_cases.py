"""Parametrized edge case tests -- runs every detector against hostile inputs.

Each test verifies that detectors don't crash on pathological input.
They may return empty results (correct behavior) or detect something
(also fine), but must NEVER raise an exception.
"""

import pytest
from code_intelligence.detectors.base import DetectorResult


def _make_ctx_for_detector(detector, factory):
    """Create a context matching the detector's first supported language."""
    lang = detector.supported_languages[0]
    # Map language to a plausible file path
    ext_map = {
        "java": "Test.java", "python": "test.py", "typescript": "test.ts",
        "javascript": "test.js", "go": "test.go", "csharp": "test.cs",
        "yaml": "test.yml", "json": "test.json", "xml": "test.xml",
        "toml": "test.toml", "ini": "test.ini", "properties": "test.properties",
        "markdown": "test.md", "proto": "test.proto", "sql": "test.sql",
        "batch": "test.bat", "bash": "test.sh", "powershell": "test.ps1",
        "kotlin": "test.kt", "rust": "test.rs", "scala": "test.scala",
        "terraform": "test.tf", "bicep": "test.bicep", "dockerfile": "Dockerfile",
        "gradle": "build.gradle", "cpp": "test.cpp", "c": "test.c",
        "ruby": "test.rb", "swift": "test.swift", "perl": "test.pl",
        "lua": "test.lua", "dart": "test.dart", "r": "test.r",
        "vue": "test.vue", "svelte": "test.svelte",
        "css": "test.css", "html": "test.html", "scss": "test.scss",
    }
    path = ext_map.get(lang, f"test.{lang}")
    return factory(language=lang, path=path)


class TestEmptyInput:
    """No detector should crash on empty input."""

    def test_empty_content(self, detector, empty_ctx):
        ctx = _make_ctx_for_detector(detector, empty_ctx)
        result = detector.detect(ctx)
        assert isinstance(result, DetectorResult)
        # May have 0 nodes (most likely) or some (file-level nodes) -- both OK


class TestBinaryInput:
    """No detector should crash on binary garbage."""

    def test_binary_garbage(self, detector, binary_ctx):
        ctx = _make_ctx_for_detector(detector, binary_ctx)
        result = detector.detect(ctx)
        assert isinstance(result, DetectorResult)


class TestMalformedUTF8:
    """No detector should crash on invalid UTF-8."""

    def test_malformed_utf8(self, detector, malformed_utf8_ctx):
        ctx = _make_ctx_for_detector(detector, malformed_utf8_ctx)
        result = detector.detect(ctx)
        assert isinstance(result, DetectorResult)


class TestUnicodeInput:
    """No detector should crash on unicode content."""

    def test_unicode_identifiers(self, detector, unicode_ctx):
        ctx = _make_ctx_for_detector(detector, unicode_ctx)
        result = detector.detect(ctx)
        assert isinstance(result, DetectorResult)


class TestNullBytes:
    """No detector should crash on embedded null bytes."""

    def test_null_bytes(self, detector, null_bytes_ctx):
        ctx = _make_ctx_for_detector(detector, null_bytes_ctx)
        result = detector.detect(ctx)
        assert isinstance(result, DetectorResult)


class TestSpecialPaths:
    """No detector should crash on file paths with special characters."""

    def test_special_chars_in_path(self, detector, special_chars_path_ctx):
        lang = detector.supported_languages[0]
        ctx = special_chars_path_ctx(language=lang)
        result = detector.detect(ctx)
        assert isinstance(result, DetectorResult)


class TestHugeInput:
    """No detector should handle large files without crashing.

    Note: This test uses a 50K-line file. Some detectors may be slow
    but must complete without errors.
    """

    def test_huge_file(self, detector, huge_ctx):
        ctx = _make_ctx_for_detector(detector, huge_ctx)
        result = detector.detect(ctx)
        assert isinstance(result, DetectorResult)
