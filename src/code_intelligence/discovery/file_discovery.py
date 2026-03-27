"""Git-aware file discovery for code intelligence."""

from __future__ import annotations

import fnmatch
import hashlib
import os
import re
import subprocess
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

from code_intelligence.config import Config


class ChangeType(Enum):
    """Type of file change detected by git."""

    ADDED = "added"
    MODIFIED = "modified"
    DELETED = "deleted"


# Map file extensions to language identifiers.
_EXTENSION_MAP: dict[str, str] = {
    ".java": "java",
    ".py": "python",
    ".ts": "typescript",
    ".tsx": "typescript",
    ".js": "javascript",
    ".jsx": "javascript",
    ".xml": "xml",
    ".yaml": "yaml",
    ".yml": "yaml",
    ".json": "json",
    ".properties": "properties",
    ".gradle": "gradle",
    ".gradle.kts": "gradle",
    ".sql": "sql",
    ".graphql": "graphql",
    ".gql": "graphql",
    ".proto": "proto",
    ".md": "markdown",
}


@dataclass(frozen=True, slots=True)
class DiscoveredFile:
    """A file discovered during repository scanning."""

    path: Path
    language: str
    content_hash: str
    size_bytes: int
    change_type: ChangeType | None = None


def _map_extension_to_language(file_path: Path) -> str | None:
    """Map a file's extension to a language string."""
    name = file_path.name
    # Check compound extensions first (e.g. .gradle.kts)
    for ext, lang in _EXTENSION_MAP.items():
        if name.endswith(ext):
            return lang
    return None


def _matches_any_pattern(path_str: str, patterns: list[str]) -> bool:
    """Check if a path matches any of the given glob patterns."""
    for pattern in patterns:
        if fnmatch.fnmatch(path_str, pattern):
            return True
    return False


def _compile_exclude_patterns(patterns: list[str]) -> re.Pattern[str] | None:
    """Compile a list of glob patterns into a single regex for fast matching."""
    if not patterns:
        return None
    return re.compile("|".join(fnmatch.translate(p) for p in patterns))


def _compute_sha256(file_path: Path) -> str:
    """Compute SHA-256 hex digest for a file.

    Delegates to :func:`code_intelligence.cache.hasher.hash_file` for
    consistency, falling back to a local implementation if the import fails.
    """
    from code_intelligence.cache.hasher import hash_file

    return hash_file(file_path)
    return h.hexdigest()


class FileDiscovery:
    """Discovers files in a repository using git or filesystem walk."""

    def __init__(self, config: Config | None = None) -> None:
        self._config = config or Config()
        self._current_commit: str | None = None

    @property
    def current_commit(self) -> str | None:
        """The HEAD commit hash from the last discovery run."""
        return self._current_commit

    def discover(
        self, repo_path: Path, incremental: bool = True
    ) -> list[DiscoveredFile]:
        """Discover tracked files in a repository.

        Uses ``git ls-files`` for git repos (fast, ~50ms for large repos).
        Falls back to ``os.walk`` for non-git directories.
        """
        repo_path = repo_path.resolve()
        discovery_cfg = self._config.discovery

        if self._is_git_repo(repo_path):
            self._current_commit = self._git_head(repo_path)
            relative_paths = self._git_ls_files(repo_path)
        else:
            self._current_commit = None
            relative_paths = self._walk_files(repo_path)

        exclude_re = _compile_exclude_patterns(discovery_cfg.exclude_patterns)

        result: list[DiscoveredFile] = []
        for rel in relative_paths:
            abs_path = repo_path / rel
            rel_path = Path(rel)

            # Extension filter
            lang = _map_extension_to_language(rel_path)
            if lang is None:
                continue

            # Check include extensions
            if not any(
                rel.endswith(ext) for ext in discovery_cfg.include_extensions
            ):
                continue

            # Check exclude patterns
            if exclude_re and exclude_re.match(str(rel_path)):
                continue

            # Size guard
            try:
                size = abs_path.stat().st_size
            except OSError:
                continue
            if size > discovery_cfg.max_file_size_bytes:
                continue

            content_hash = _compute_sha256(abs_path)
            result.append(
                DiscoveredFile(
                    path=rel_path,
                    language=lang,
                    content_hash=content_hash,
                    size_bytes=size,
                )
            )

        return result

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _is_git_repo(path: Path) -> bool:
        try:
            subprocess.run(
                ["git", "rev-parse", "--git-dir"],
                cwd=path,
                capture_output=True,
                check=True,
            )
            return True
        except (subprocess.CalledProcessError, FileNotFoundError):
            return False

    @staticmethod
    def _git_head(repo_path: Path) -> str:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True,
        )
        return result.stdout.strip()

    @staticmethod
    def _git_ls_files(repo_path: Path) -> list[str]:
        result = subprocess.run(
            ["git", "ls-files"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True,
        )
        return [line for line in result.stdout.splitlines() if line]

    @staticmethod
    def _walk_files(root: Path) -> list[str]:
        paths: list[str] = []
        for dirpath, _dirnames, filenames in os.walk(root):
            for fname in filenames:
                abs_p = Path(dirpath) / fname
                rel = str(abs_p.relative_to(root))
                paths.append(rel)
        return paths
