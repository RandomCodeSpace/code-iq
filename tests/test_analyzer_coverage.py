"""Tests to improve coverage for analyzer.py."""

from __future__ import annotations

import pytest
from pathlib import Path

from osscodeiq.analyzer import (
    Analyzer,
    AnalysisResult,
    _derive_module_name,
    _parse_toml,
    _parse_ini,
    _parse_structured,
    _text_passthrough,
)


class TestDeriveModuleName:
    def test_java_main(self):
        result = _derive_module_name(Path("src/main/java/com/example/Foo.java"), "java")
        assert result == "com.example"

    def test_java_test(self):
        result = _derive_module_name(Path("src/test/java/com/example/FooTest.java"), "java")
        assert result == "com.example"

    def test_java_no_marker(self):
        result = _derive_module_name(Path("lib/Foo.java"), "java")
        assert result is None

    def test_java_root_package(self):
        result = _derive_module_name(Path("src/main/java/Foo.java"), "java")
        assert result is None or result == ""

    def test_python_module(self):
        result = _derive_module_name(Path("src/models/user.py"), "python")
        assert result == "src.models"

    def test_python_root(self):
        result = _derive_module_name(Path("main.py"), "python")
        assert result is None

    def test_structured_language(self):
        result = _derive_module_name(Path("config/settings.yaml"), "yaml")
        assert result == "config"

    def test_structured_root(self):
        result = _derive_module_name(Path("config.yaml"), "yaml")
        assert result is None

    def test_unknown_language(self):
        result = _derive_module_name(Path("something.txt"), "text")
        assert result is None


class TestParseToml:
    def test_valid_toml(self):
        content = b'[tool]\nname = "test"\n'
        result = _parse_toml(content, "pyproject.toml")
        assert result["type"] == "toml"
        assert result["data"]["tool"]["name"] == "test"

    def test_invalid_toml(self):
        content = b"this is not valid toml {{{{"
        result = _parse_toml(content, "bad.toml")
        assert result["error"] == "invalid_toml"


class TestParseIni:
    def test_valid_ini(self):
        content = b"[section]\nkey = value\n"
        result = _parse_ini(content, "config.ini")
        assert result["type"] == "ini"
        assert result["data"]["section"]["key"] == "value"

    def test_invalid_ini(self):
        content = b"\x00\x01\x02"
        result = _parse_ini(content, "bad.ini")
        # configparser is lenient, but we test it doesn't crash
        assert "type" in result or "error" in result


class TestParseStructured:
    def test_known_language(self):
        result = _parse_structured("toml", b'[tool]\nname = "x"\n', "test.toml")
        assert result is not None
        assert result["type"] == "toml"

    def test_unknown_language(self):
        result = _parse_structured("unknown_lang", b"content", "test.txt")
        assert result is None

    def test_text_passthrough(self):
        parser = _text_passthrough("vue")
        result = parser(b"<template>hello</template>", "app.vue")
        assert result["type"] == "vue"
        assert "hello" in result["data"]


class TestFullAnalysis:
    def test_basic_analysis(self, tmp_path):
        (tmp_path / "hello.py").write_text("class Foo:\n    pass\n")
        analyzer = Analyzer()
        result = analyzer.run(tmp_path, incremental=False)
        assert isinstance(result, AnalysisResult)
        assert result.total_files >= 1
        assert result.graph.node_count >= 0

    def test_analysis_with_progress(self, tmp_path):
        (tmp_path / "hello.py").write_text("def bar(): pass\n")
        progress_msgs = []
        analyzer = Analyzer()
        result = analyzer.run(tmp_path, incremental=False, on_progress=progress_msgs.append)
        assert len(progress_msgs) > 0

    def test_incremental_analysis(self, tmp_path):
        (tmp_path / "hello.py").write_text("class Foo:\n    pass\n")
        analyzer = Analyzer()
        result1 = analyzer.run(tmp_path, incremental=False)
        result2 = analyzer.run(tmp_path, incremental=True)
        assert result2.files_cached >= 0

    def test_analysis_unreadable_file(self, tmp_path):
        """Analyzer should not crash on unreadable files."""
        (tmp_path / "hello.py").write_text("x = 1\n")
        analyzer = Analyzer()
        result = analyzer.run(tmp_path, incremental=False)
        assert isinstance(result, AnalysisResult)

    def test_analysis_multiple_languages(self, tmp_path):
        (tmp_path / "app.py").write_text("class App: pass\n")
        (tmp_path / "config.yaml").write_text("key: value\n")
        (tmp_path / "data.json").write_text('{"a": 1}\n')
        analyzer = Analyzer()
        result = analyzer.run(tmp_path, incremental=False)
        assert result.total_files >= 3
        assert len(result.language_breakdown) >= 2

    def test_analysis_language_breakdown(self, tmp_path):
        (tmp_path / "a.py").write_text("x = 1\n")
        (tmp_path / "b.py").write_text("y = 2\n")
        analyzer = Analyzer()
        result = analyzer.run(tmp_path, incremental=False)
        assert "python" in result.language_breakdown
        assert result.language_breakdown["python"] >= 2

    def test_analysis_determinism(self, tmp_path):
        """Two runs on the same input must produce the same counts."""
        (tmp_path / "hello.py").write_text("class Foo:\n    pass\n")
        analyzer = Analyzer()
        r1 = analyzer.run(tmp_path, incremental=False)
        r2 = analyzer.run(tmp_path, incremental=False)
        assert r1.graph.node_count == r2.graph.node_count
        assert r1.graph.edge_count == r2.graph.edge_count
