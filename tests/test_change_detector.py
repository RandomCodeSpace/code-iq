"""Tests for discovery/change_detector.py — git-based incremental change detection."""

from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from osscodeiq.discovery.change_detector import ChangeDetector
from osscodeiq.discovery.file_discovery import ChangeType


def _git(repo: Path, *args: str) -> subprocess.CompletedProcess[str]:
    """Run a git command in the repo."""
    return subprocess.run(
        ["git"] + list(args),
        cwd=repo,
        capture_output=True,
        text=True,
        check=True,
    )


def _init_repo(tmp_path: Path) -> Path:
    """Create a git repo with an initial commit."""
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init")
    _git(repo, "config", "user.email", "test@test.com")
    _git(repo, "config", "user.name", "Test")
    # Initial commit with a file
    (repo / "main.py").write_text("print('hello')\n")
    _git(repo, "add", ".")
    _git(repo, "commit", "-m", "initial")
    return repo


def _get_head(repo: Path) -> str:
    result = _git(repo, "rev-parse", "HEAD")
    return result.stdout.strip()


class TestChangeDetector:
    def test_detect_added_file(self, tmp_path: Path):
        repo = _init_repo(tmp_path)
        base_commit = _get_head(repo)
        # Add a new file
        (repo / "new_file.py").write_text("x = 1\n")
        _git(repo, "add", ".")
        _git(repo, "commit", "-m", "add new file")

        detector = ChangeDetector()
        changes = detector.detect_changes(repo, base_commit)
        assert len(changes) == 1
        assert changes[0].change_type == ChangeType.ADDED
        assert changes[0].path == Path("new_file.py")
        assert changes[0].size_bytes > 0
        assert changes[0].content_hash != ""

    def test_detect_modified_file(self, tmp_path: Path):
        repo = _init_repo(tmp_path)
        base_commit = _get_head(repo)
        # Modify existing file
        (repo / "main.py").write_text("print('modified')\n")
        _git(repo, "add", ".")
        _git(repo, "commit", "-m", "modify file")

        detector = ChangeDetector()
        changes = detector.detect_changes(repo, base_commit)
        assert len(changes) == 1
        assert changes[0].change_type == ChangeType.MODIFIED

    def test_detect_deleted_file(self, tmp_path: Path):
        repo = _init_repo(tmp_path)
        base_commit = _get_head(repo)
        # Delete existing file
        (repo / "main.py").unlink()
        _git(repo, "add", ".")
        _git(repo, "commit", "-m", "delete file")

        detector = ChangeDetector()
        changes = detector.detect_changes(repo, base_commit)
        assert len(changes) == 1
        assert changes[0].change_type == ChangeType.DELETED
        assert changes[0].size_bytes == 0
        assert changes[0].content_hash == ""

    def test_no_changes(self, tmp_path: Path):
        repo = _init_repo(tmp_path)
        head = _get_head(repo)
        detector = ChangeDetector()
        changes = detector.detect_changes(repo, head)
        assert changes == []

    def test_multiple_changes(self, tmp_path: Path):
        repo = _init_repo(tmp_path)
        base_commit = _get_head(repo)
        # Add and modify
        (repo / "added.py").write_text("new = True\n")
        (repo / "main.py").write_text("print('changed')\n")
        _git(repo, "add", ".")
        _git(repo, "commit", "-m", "multiple changes")

        detector = ChangeDetector()
        changes = detector.detect_changes(repo, base_commit)
        assert len(changes) == 2
        types = {c.change_type for c in changes}
        assert ChangeType.ADDED in types
        assert ChangeType.MODIFIED in types

    def test_non_code_files_ignored(self, tmp_path: Path):
        repo = _init_repo(tmp_path)
        base_commit = _get_head(repo)
        # Add a file with unrecognised extension
        (repo / "image.png").write_bytes(b"\x89PNG\r\n")
        _git(repo, "add", ".")
        _git(repo, "commit", "-m", "add image")

        detector = ChangeDetector()
        changes = detector.detect_changes(repo, base_commit)
        # .png is not in the language map, so it should be skipped
        assert len(changes) == 0

    def test_invalid_commit_sha_raises(self, tmp_path: Path):
        repo = _init_repo(tmp_path)
        detector = ChangeDetector()
        with pytest.raises(ValueError, match="Invalid commit SHA"):
            detector.detect_changes(repo, "not-a-sha!")

    def test_short_sha_accepted(self, tmp_path: Path):
        repo = _init_repo(tmp_path)
        full_sha = _get_head(repo)
        short_sha = full_sha[:7]
        # Add a change
        (repo / "extra.py").write_text("y = 2\n")
        _git(repo, "add", ".")
        _git(repo, "commit", "-m", "add extra")

        detector = ChangeDetector()
        changes = detector.detect_changes(repo, short_sha)
        assert len(changes) == 1

    def test_renamed_file_detected(self, tmp_path: Path):
        repo = _init_repo(tmp_path)
        base_commit = _get_head(repo)
        # Rename file
        _git(repo, "mv", "main.py", "renamed.py")
        _git(repo, "commit", "-m", "rename file")

        detector = ChangeDetector()
        changes = detector.detect_changes(repo, base_commit)
        # Renamed files show up as MODIFIED with the new name
        assert len(changes) >= 1
        paths = [str(c.path) for c in changes]
        assert "renamed.py" in paths
