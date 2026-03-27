"""Content hashing utilities for cache invalidation."""

from __future__ import annotations

import hashlib
from pathlib import Path


def hash_file_content(content: bytes) -> str:
    """Return the SHA-256 hex digest of *content*."""
    return hashlib.sha256(content).hexdigest()


def hash_file(path: Path) -> str:
    """Read *path* and return its SHA-256 hex digest.

    Reads in 8 KiB chunks to handle large files efficiently.
    """
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()
