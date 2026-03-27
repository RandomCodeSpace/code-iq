"""Thread-safe tree-sitter parser pool manager."""

from __future__ import annotations

import logging
import queue
from typing import TYPE_CHECKING

import tree_sitter

from code_intelligence.parsing.languages.java import JavaLanguageSupport
from code_intelligence.parsing.languages.python import PythonLanguageSupport
from code_intelligence.parsing.languages.typescript import (
    JavaScriptLanguageSupport,
    TypeScriptLanguageSupport,
)

if TYPE_CHECKING:
    from code_intelligence.discovery.file_discovery import DiscoveredFile
    from code_intelligence.parsing.languages.base import LanguageSupport

logger = logging.getLogger(__name__)

# Default pool size per language.
_DEFAULT_POOL_SIZE = 4


class ParserManager:
    """Manages a pool of tree-sitter parsers for thread-safe parsing."""

    def __init__(self, pool_size: int = _DEFAULT_POOL_SIZE) -> None:
        self._pool_size = pool_size
        self._languages: dict[str, LanguageSupport] = {}
        self._ts_languages: dict[str, tree_sitter.Language] = {}
        self._pools: dict[str, queue.Queue[tree_sitter.Parser]] = {}
        self._query_cache: dict[tuple[str, str], tree_sitter.Query] = {}

        # Auto-register built-in languages.
        self._register_builtins()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def register_language(self, name: str, support: LanguageSupport) -> None:
        """Register a language and pre-populate its parser pool."""
        self._languages[name] = support
        ts_lang = support.get_language()
        self._ts_languages[name] = ts_lang
        self._pools[name] = self._create_pool(ts_lang)

    def parse_file(
        self, file: DiscoveredFile, content: bytes
    ) -> tree_sitter.Tree | None:
        """Parse *content* using the parser for *file*'s language.

        Borrows a parser from the pool and returns it afterwards, making
        this method safe to call from multiple threads concurrently.
        """
        lang = file.language
        pool = self._pools.get(lang)
        if pool is None:
            logger.debug("No parser registered for language %s", lang)
            return None

        parser = pool.get()
        try:
            return parser.parse(content)
        finally:
            pool.put(parser)

    def get_query(
        self, language: str, query_name: str
    ) -> tree_sitter.Query | None:
        """Return a compiled tree-sitter Query, cached after first build."""
        cache_key = (language, query_name)
        if cache_key in self._query_cache:
            return self._query_cache[cache_key]

        support = self._languages.get(language)
        if support is None:
            return None

        queries = support.get_queries()
        source = queries.get(query_name)
        if source is None:
            return None

        ts_lang = self._ts_languages[language]
        compiled = tree_sitter.Query(ts_lang, source)
        self._query_cache[cache_key] = compiled
        return compiled

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------

    def _create_pool(
        self, ts_lang: tree_sitter.Language
    ) -> queue.Queue[tree_sitter.Parser]:
        pool: queue.Queue[tree_sitter.Parser] = queue.Queue(
            maxsize=self._pool_size
        )
        for _ in range(self._pool_size):
            parser = tree_sitter.Parser(ts_lang)
            pool.put(parser)
        return pool

    def _register_builtins(self) -> None:
        """Register languages that ship with the package."""
        builtins: list[LanguageSupport] = [
            JavaLanguageSupport(),  # type: ignore[list-item]
            PythonLanguageSupport(),  # type: ignore[list-item]
            TypeScriptLanguageSupport(),  # type: ignore[list-item]
            JavaScriptLanguageSupport(),  # type: ignore[list-item]
        ]
        for support in builtins:
            try:
                self.register_language(support.name, support)
            except Exception:  # noqa: BLE001
                logger.warning(
                    "Failed to register built-in language %s",
                    support.name,
                    exc_info=True,
                )
