"""Tests to improve coverage for discovery/file_discovery.py."""

from __future__ import annotations

import pytest
from pathlib import Path
from unittest.mock import patch

from osscodeiq.config import Config
from osscodeiq.discovery.file_discovery import (
    FileDiscovery,
    DiscoveredFile,
    _map_extension_to_language,
    _matches_any_pattern,
    _compile_exclude_patterns,
    _build_ignore_spec,
)


class TestMapExtensionToLanguage:
    def test_python(self):
        assert _map_extension_to_language(Path("foo.py")) == "python"

    def test_typescript(self):
        assert _map_extension_to_language(Path("app.ts")) == "typescript"

    def test_tsx(self):
        assert _map_extension_to_language(Path("comp.tsx")) == "typescript"

    def test_java(self):
        assert _map_extension_to_language(Path("App.java")) == "java"

    def test_yaml(self):
        assert _map_extension_to_language(Path("config.yaml")) == "yaml"
        assert _map_extension_to_language(Path("config.yml")) == "yaml"

    def test_gradle_kts(self):
        assert _map_extension_to_language(Path("build.gradle.kts")) == "gradle"

    def test_dockerfile(self):
        assert _map_extension_to_language(Path("Dockerfile")) == "dockerfile"

    def test_makefile(self):
        assert _map_extension_to_language(Path("Makefile")) == "makefile"

    def test_jenkinsfile(self):
        assert _map_extension_to_language(Path("Jenkinsfile")) == "groovy"

    def test_go_mod(self):
        assert _map_extension_to_language(Path("go.mod")) == "gomod"

    def test_unknown_extension(self):
        assert _map_extension_to_language(Path("README")) is None

    def test_pyi_stub(self):
        assert _map_extension_to_language(Path("types.pyi")) == "python"

    def test_razor(self):
        assert _map_extension_to_language(Path("page.razor")) == "razor"

    def test_cshtml(self):
        assert _map_extension_to_language(Path("view.cshtml")) == "cshtml"

    def test_mjs(self):
        assert _map_extension_to_language(Path("module.mjs")) == "javascript"

    def test_scss(self):
        assert _map_extension_to_language(Path("style.scss")) == "scss"

    def test_vue(self):
        assert _map_extension_to_language(Path("App.vue")) == "vue"

    def test_svelte(self):
        assert _map_extension_to_language(Path("App.svelte")) == "svelte"


class TestMatchesAnyPattern:
    def test_matches(self):
        assert _matches_any_pattern("node_modules/foo.js", ["node_modules/*"]) is True

    def test_no_match(self):
        assert _matches_any_pattern("src/app.py", ["node_modules/*"]) is False

    def test_empty_patterns(self):
        assert _matches_any_pattern("anything", []) is False


class TestCompileExcludePatterns:
    def test_empty(self):
        assert _compile_exclude_patterns([]) is None

    def test_single_pattern(self):
        regex = _compile_exclude_patterns(["*.pyc"])
        assert regex is not None
        assert regex.match("foo.pyc")

    def test_multiple_patterns(self):
        regex = _compile_exclude_patterns(["*.pyc", "*.pyo"])
        assert regex is not None
        assert regex.match("foo.pyc")
        assert regex.match("bar.pyo")


class TestBuildIgnoreSpec:
    def test_with_config_patterns(self, tmp_path):
        spec = _build_ignore_spec(tmp_path, ["node_modules", "*.pyc"])
        assert spec.match_file("node_modules/foo.js")

    def test_with_gitignore(self, tmp_path):
        (tmp_path / ".gitignore").write_text("dist/\n*.log\n")
        spec = _build_ignore_spec(tmp_path, [])
        assert spec.match_file("dist/bundle.js")
        assert spec.match_file("app.log")

    def test_with_codeignore(self, tmp_path):
        (tmp_path / ".codeignore").write_text("vendor/\n")
        spec = _build_ignore_spec(tmp_path, [])
        assert spec.match_file("vendor/lib.py")

    def test_ignores_comments(self, tmp_path):
        (tmp_path / ".gitignore").write_text("# comment\ndist/\n")
        spec = _build_ignore_spec(tmp_path, [])
        assert spec.match_file("dist/foo")
        assert not spec.match_file("src/foo.py")


class TestFileDiscovery:
    def test_discover_non_git(self, tmp_path):
        """Discover files in a non-git directory using os.walk fallback."""
        (tmp_path / "app.py").write_text("x = 1\n")
        (tmp_path / "config.yaml").write_text("key: val\n")
        (tmp_path / "readme.txt").write_text("no extension mapping\n")

        discovery = FileDiscovery()
        with patch.object(FileDiscovery, "_is_git_repo", return_value=False):
            files = discovery.discover(tmp_path)

        languages = {f.language for f in files}
        assert "python" in languages
        assert "yaml" in languages
        assert discovery.current_commit is None

    def test_discover_excludes_ignored(self, tmp_path):
        """Excluded patterns should filter out files."""
        (tmp_path / "app.py").write_text("x = 1\n")
        sub = tmp_path / "node_modules"
        sub.mkdir()
        (sub / "lib.js").write_text("module.exports = {}\n")

        config = Config()
        config.discovery.exclude_patterns = ["node_modules"]
        discovery = FileDiscovery(config)
        with patch.object(FileDiscovery, "_is_git_repo", return_value=False):
            files = discovery.discover(tmp_path)

        paths = [str(f.path) for f in files]
        assert not any("node_modules" in p for p in paths)

    def test_discover_respects_max_file_size(self, tmp_path):
        """Files exceeding max_file_size_bytes should be skipped."""
        (tmp_path / "big.py").write_text("x" * 2000)
        (tmp_path / "small.py").write_text("x = 1\n")

        config = Config()
        config.discovery.max_file_size_bytes = 100
        discovery = FileDiscovery(config)
        with patch.object(FileDiscovery, "_is_git_repo", return_value=False):
            files = discovery.discover(tmp_path)

        paths = [str(f.path) for f in files]
        assert any("small.py" in p for p in paths)
        assert not any("big.py" in p for p in paths)

    def test_discovered_file_has_content_hash(self, tmp_path):
        (tmp_path / "app.py").write_text("x = 1\n")
        discovery = FileDiscovery()
        with patch.object(FileDiscovery, "_is_git_repo", return_value=False):
            files = discovery.discover(tmp_path)
        assert len(files) >= 1
        assert files[0].content_hash  # non-empty hash

    def test_discover_extensionless_files(self, tmp_path):
        """Dockerfile and Makefile should be discovered via _FILENAME_MAP."""
        (tmp_path / "Dockerfile").write_text("FROM python:3.11\n")
        (tmp_path / "Makefile").write_text("all:\n\techo hi\n")

        discovery = FileDiscovery()
        with patch.object(FileDiscovery, "_is_git_repo", return_value=False):
            files = discovery.discover(tmp_path)

        languages = {f.language for f in files}
        assert "dockerfile" in languages
        assert "makefile" in languages

    def test_walk_files(self, tmp_path):
        """Test the _walk_files static method directly."""
        sub = tmp_path / "sub"
        sub.mkdir()
        (sub / "test.py").write_text("pass\n")
        (tmp_path / "root.py").write_text("pass\n")

        paths = FileDiscovery._walk_files(tmp_path)
        assert len(paths) >= 2

    def test_discover_subdirectories(self, tmp_path):
        """Files in subdirectories should be discovered."""
        sub = tmp_path / "src" / "models"
        sub.mkdir(parents=True)
        (sub / "user.py").write_text("class User: pass\n")

        discovery = FileDiscovery()
        with patch.object(FileDiscovery, "_is_git_repo", return_value=False):
            files = discovery.discover(tmp_path)

        assert len(files) >= 1
        assert any("user.py" in str(f.path) for f in files)
