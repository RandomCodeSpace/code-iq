"""Configuration loading and defaults for code-intelligence."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, Field


class DiscoveryConfig(BaseModel):
    """File discovery configuration."""

    include_extensions: list[str] = Field(default_factory=lambda: [
        ".java", ".py", ".ts", ".tsx", ".js", ".jsx",
        ".xml", ".yaml", ".yml", ".json", ".properties",
        ".gradle", ".gradle.kts", ".sql", ".graphql", ".gql",
        ".proto", ".md", ".markdown",
        ".cs", ".go", ".tf", ".tfvars", ".hcl",
        ".cpp", ".cc", ".cxx", ".hpp", ".c", ".h",
        ".sh", ".bash", ".zsh", ".ps1", ".psm1", ".psd1",
        ".bat", ".cmd", ".bicep",
        ".rb", ".rs", ".kt", ".kts", ".scala", ".swift",
        ".r", ".R", ".pl", ".pm", ".lua", ".dart",
        ".toml", ".ini", ".cfg", ".conf",
        ".env", ".csv", ".dockerfile",
        ".vue", ".svelte",
        ".html", ".htm", ".css", ".scss", ".less",
        ".mjs", ".cjs", ".mts", ".cts", ".jsonc",
        ".groovy", ".pyi", ".razor", ".cshtml", ".adoc",
    ])
    exclude_patterns: list[str] = Field(default_factory=lambda: [
        "**/node_modules/**",
        "**/build/**",
        "**/target/**",
        "**/dist/**",
        "**/.git/**",
        "**/generated/**",
        "**/__pycache__/**",
        "**/venv/**",
        "**/.venv/**",
    ])
    max_file_size_bytes: int = 1_048_576  # 1MB


class CacheConfig(BaseModel):
    """Cache configuration."""

    enabled: bool = True
    directory: str = ".code-intelligence"
    db_name: str = "cache.db"


class AnalysisConfig(BaseModel):
    """Analysis configuration."""

    parallelism: int = 8
    incremental: bool = True


class OutputConfig(BaseModel):
    """Output configuration."""

    max_nodes: int = 500
    default_format: str = "json"
    default_view: str = "developer"


class DomainMapping(BaseModel):
    """Domain grouping for architect view."""

    name: str
    modules: list[str]


class GraphConfig(BaseModel):
    """Graph storage backend configuration."""

    backend: str = "networkx"  # networkx | kuzu | sqlite
    path: str | None = None    # For file-based backends (kuzu, sqlite)


class Config(BaseModel):
    """Root configuration for code-intelligence."""

    discovery: DiscoveryConfig = Field(default_factory=DiscoveryConfig)
    cache: CacheConfig = Field(default_factory=CacheConfig)
    analysis: AnalysisConfig = Field(default_factory=AnalysisConfig)
    output: OutputConfig = Field(default_factory=OutputConfig)
    graph: GraphConfig = Field(default_factory=GraphConfig)
    domains: list[DomainMapping] = Field(default_factory=list)

    @classmethod
    def load(cls, config_path: Path | None = None, project_path: Path | None = None) -> Config:
        """Load configuration from YAML file, falling back to defaults."""
        if config_path and config_path.exists():
            with open(config_path) as f:
                data: dict[str, Any] = yaml.safe_load(f) or {}
            return cls.model_validate(data)
        # Check for default config file in project root
        search_dir = project_path or Path.cwd()
        for name in (".code-intelligence.yml", ".code-intelligence.yaml"):
            default = search_dir / name
            if default.exists():
                with open(default) as f:
                    data = yaml.safe_load(f) or {}
                return cls.model_validate(data)
        return cls()

    @property
    def cache_path(self) -> Path:
        return Path(self.cache.directory) / self.cache.db_name
