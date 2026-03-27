"""Git diff-based incremental change detection."""

from __future__ import annotations

import re
import subprocess
from pathlib import Path

from code_intelligence.discovery.file_discovery import (
    ChangeType,
    DiscoveredFile,
    _compute_sha256,
    _map_extension_to_language,
)


# Mapping from git status letters to ChangeType.
_GIT_STATUS_MAP: dict[str, ChangeType] = {
    "A": ChangeType.ADDED,
    "M": ChangeType.MODIFIED,
    "D": ChangeType.DELETED,
    "R": ChangeType.MODIFIED,  # Rename treated as modified
}


class ChangeDetector:
    """Detects file changes between two git commits."""

    def detect_changes(
        self, repo_path: Path, last_commit: str
    ) -> list[DiscoveredFile]:
        """Return files that changed between *last_commit* and HEAD.

        Uses ``git diff --name-status <last_commit>..HEAD``.
        """
        repo_path = repo_path.resolve()

        if not re.fullmatch(r'[0-9a-fA-F]{4,40}', last_commit):
            raise ValueError(f"Invalid commit SHA: {last_commit}")

        result = subprocess.run(
            ["git", "diff", "--name-status", f"{last_commit}..HEAD"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True,
        )

        discovered: list[DiscoveredFile] = []
        for line in result.stdout.splitlines():
            if not line.strip():
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue

            status_letter = parts[0][0]  # First char handles R100 etc.
            # For renames the *destination* path is the last element.
            file_rel = parts[-1]
            change_type = _GIT_STATUS_MAP.get(status_letter)
            if change_type is None:
                continue

            rel_path = Path(file_rel)
            lang = _map_extension_to_language(rel_path)
            if lang is None:
                continue

            abs_path = repo_path / rel_path

            if change_type is ChangeType.DELETED:
                discovered.append(
                    DiscoveredFile(
                        path=rel_path,
                        language=lang,
                        content_hash="",
                        size_bytes=0,
                        change_type=change_type,
                    )
                )
            else:
                try:
                    size = abs_path.stat().st_size
                    content_hash = _compute_sha256(abs_path)
                except OSError:
                    continue
                discovered.append(
                    DiscoveredFile(
                        path=rel_path,
                        language=lang,
                        content_hash=content_hash,
                        size_bytes=size,
                        change_type=change_type,
                    )
                )

        return discovered
