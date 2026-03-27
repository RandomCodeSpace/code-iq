"""Abstract language support protocol for tree-sitter based parsing."""

from __future__ import annotations

from typing import Protocol, runtime_checkable

import tree_sitter


@runtime_checkable
class LanguageSupport(Protocol):
    """Protocol that language plugins must satisfy."""

    name: str
    extensions: tuple[str, ...]

    def get_language(self) -> tree_sitter.Language:
        """Return the tree-sitter Language object for this language."""
        ...

    def get_queries(self) -> dict[str, str]:
        """Return a mapping of query-name to tree-sitter query source."""
        ...
