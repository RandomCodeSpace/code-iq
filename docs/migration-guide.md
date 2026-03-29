# Migration Guide: Python to Java

This guide covers migrating from the Python OSSCodeIQ (`osscodeiq` on PyPI) to the Java rewrite (`io.github.randomcodespace.iq:code-iq` on Maven Central).

## Overview

The Java version is a ground-up rewrite of OSSCodeIQ using Java 25, Spring Boot 4, and Neo4j Embedded. It maintains API compatibility with the Python version -- same REST endpoints, same MCP tool names, same graph model -- but replaces the runtime, build system, and graph backend.

| Aspect | Python | Java |
|--------|--------|------|
| Runtime | Python 3.11+ | Java 25+ |
| Package manager | pip / uv | Maven |
| CLI tool | `osscodeiq` | `java -jar code-iq-*.jar` |
| Graph backends | NetworkX, SQLite, KuzuDB | Neo4j Embedded |
| Cache | None (full re-scan) | SQLite + Hazelcast (incremental) |
| MCP framework | fastmcp | Spring AI MCP |
| REST framework | FastAPI | Spring Boot (Spring MVC) |
| Web UI | NiceGUI | Thymeleaf + HTMX |
| Parsing | Regex + tree-sitter | Regex + JavaParser + ANTLR |
| Config file | pyproject.toml | pom.xml |

## What's Different

### Installation

**Python:**
```bash
pip install osscodeiq
osscodeiq analyze /path/to/repo
```

**Java:**
```bash
# Download JAR from Maven Central or build from source
git clone https://github.com/RandomCodeSpace/code-iq.git
cd code-iq && git checkout java
mvn clean package -DskipTests
java -jar target/code-iq-*.jar analyze /path/to/repo
```

Or with Docker:
```bash
docker run -v /path/to/repo:/data code-iq analyze /data
```

### Graph Backend

Python supports 3 backends (NetworkX, SQLite, KuzuDB). Java uses Neo4j Embedded exclusively.

- No `--backend` flag needed -- Neo4j is always used
- Cypher queries work out of the box (no need for `--backend kuzu`)
- Graph data stored in `.code-intelligence/neo4j/` within the analyzed codebase
- No external Neo4j server needed -- everything runs embedded in the JVM

### Incremental Analysis

Python re-scans everything on each run. Java tracks file hashes in a SQLite cache and only re-analyzes changed files.

- First run: full analysis (comparable to Python)
- Subsequent runs: only changed/new files analyzed, much faster
- Use `--no-cache` flag to force full re-analysis
- Cache stored in `.code-intelligence/analysis-cache.db`

### Parsing

| Language | Python | Java |
|----------|--------|------|
| Java | tree-sitter + regex | **JavaParser AST** (deeper analysis) |
| TypeScript/JS | tree-sitter + regex | **ANTLR grammar** |
| Python | tree-sitter + regex | **ANTLR grammar** |
| Go | regex | **ANTLR grammar** |
| C# | regex | **ANTLR grammar** |
| Rust | regex | **ANTLR grammar** |
| C++ | regex | **ANTLR grammar** |
| All others | regex | regex |

The Java version finds more nodes (+2-8%) and edges (+20-39%) than Python due to deeper AST-based detection.

### Server

Both versions serve REST API and MCP on a single port, but the defaults differ:

| Feature | Python | Java |
|---------|--------|------|
| Default port | 8000 | 8080 |
| REST API path | `/api` | `/api` (same) |
| MCP endpoint | `/mcp` | `/mcp` (same) |
| Web UI path | `/ui` (NiceGUI) | `/` (Thymeleaf) |
| OpenAPI docs | `/docs` | `/swagger-ui.html` |
| Health check | N/A | `/actuator/health` |

### Configuration

Python uses `pyproject.toml` for package config and CLI flags for runtime config. Java uses `application.properties` / `application.yml` plus an optional `.osscodeiq.yml` project-level config.

**Python config (CLI flags):**
```bash
osscodeiq analyze /path --backend sqlite
osscodeiq serve /path --port 9000
```

**Java config (`application.properties`):**
```properties
codeiq.root-path=/path/to/repo
codeiq.cache-dir=.code-intelligence
server.port=8080
```

**Project-level overrides (`.osscodeiq.yml`):**
Both Python and Java read `.osscodeiq.yml` from the codebase root for project-specific settings. This file format is the same in both versions.

## What's the Same

### Graph Model
Identical. 31 node types, 26 edge types, same enum values, same ID format (`{prefix}:{filepath}:{type}:{identifier}`).

### REST API Paths
Same endpoint paths under `/api`. Python and Java responses have the same JSON structure.

### MCP Tool Names
Same tool names: `get_stats`, `query_nodes`, `query_edges`, `get_node_neighbors`, `get_ego_graph`, `find_cycles`, `find_shortest_path`, `find_consumers`, `find_producers`, `find_callers`, `find_dependencies`, `find_dependents`, `generate_flow`, `analyze_codebase`, `run_cypher`, `find_component_by_file`, `trace_impact`, `find_related_endpoints`, `search_graph`, `read_file`. Java adds `get_detailed_stats`.

### Detection Patterns
Same regex patterns for most detectors. Java detectors find the same code patterns as Python. The Java version finds strictly more (never fewer) due to AST-based detection on top of regex.

### Flow Diagrams
Same 5 views: `overview`, `ci`, `deploy`, `runtime`, `auth`. Same Cytoscape.js + Dagre.js rendering.

### Determinism Guarantee
Both versions guarantee identical output for identical input. Same codebase analyzed twice produces the exact same node and edge counts.

## CLI Command Mapping

| Python | Java | Notes |
|--------|------|-------|
| `osscodeiq analyze [path]` | `code-iq analyze [path]` | Java adds `--no-cache`, `--incremental`, `--parallelism` |
| `osscodeiq stats [path]` | `code-iq stats [path]` | New in Java -- rich categorized stats |
| `osscodeiq graph [path]` | `code-iq graph [path]` | Same |
| `osscodeiq query [path]` | `code-iq query [path]` | Same |
| `osscodeiq find [what] [path]` | `code-iq find [what] [path]` | Same |
| `osscodeiq cypher [query]` | `code-iq cypher [query]` | Java: always Neo4j (no `--backend kuzu` needed) |
| `osscodeiq flow [path]` | `code-iq flow [path]` | Same |
| `osscodeiq serve [path]` | `code-iq serve [path]` | Default port: 8080 (was 8000) |
| `osscodeiq bundle [path]` | `code-iq bundle [path]` | Same |
| `osscodeiq cache [action]` | `code-iq cache [action]` | Java: manages SQLite hash cache |
| `osscodeiq plugins [action]` | `code-iq plugins [action]` | Same |
| `osscodeiq version` | `code-iq version` | Same |

Where `code-iq` means `java -jar target/code-iq-*.jar`.

## Migration Steps

### 1. Install Java 25+

Download from [Oracle](https://www.oracle.com/java/technologies/downloads/) or use sdkman:
```bash
sdk install java 25-open
```

### 2. Build from source

```bash
git clone https://github.com/RandomCodeSpace/code-iq.git
cd code-iq && git checkout java
mvn clean package -DskipTests
```

### 3. Re-analyze your codebase

The Java version uses a different graph backend (Neo4j vs NetworkX/SQLite/KuzuDB), so you must re-analyze:
```bash
java -jar target/code-iq-*.jar analyze /path/to/repo
```

This creates `.code-intelligence/` in the codebase root with:
- `neo4j/` -- embedded graph database
- `analysis-cache.db` -- SQLite file hash cache for incremental analysis

### 4. Verify results

```bash
# Compare node/edge counts
java -jar target/code-iq-*.jar stats /path/to/repo

# Test REST API
java -jar target/code-iq-*.jar serve /path/to/repo
curl http://localhost:8080/api/stats
```

The Java version should find equal or more nodes/edges than the Python version.

### 5. Update CI/CD

Replace `pip install osscodeiq` with Maven build or Docker:

```yaml
# GitHub Actions example
- uses: actions/setup-java@v4
  with:
    java-version: '25'
    distribution: 'temurin'
- run: mvn clean package -DskipTests
- run: java -jar target/code-iq-*.jar analyze .
```

Or use Docker:
```yaml
- run: docker run -v ${{ github.workspace }}:/data code-iq analyze /data
```

### 6. Update MCP client config

If you have MCP clients configured to connect to the Python server, update the port:

```json
{
  "mcpServers": {
    "code-iq": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

## Known Differences

1. **Startup time**: Java has 8-16s Spring Boot startup overhead (Neo4j init, Spring context). Python starts nearly instantly. The Java version compensates with faster analysis throughput.

2. **Memory usage**: Java requires more heap memory due to Neo4j Embedded. Default JVM settings work for most codebases. For 50K+ file repos, consider `-Xmx4g`.

3. **More detections**: Java consistently finds 2-8% more nodes and 20-39% more edges than Python, due to deeper AST-based analysis (JavaParser, ANTLR). These are real patterns, not false positives.

4. **No backend choice**: Java uses Neo4j Embedded only. If you need KuzuDB or NetworkX specifically, continue using the Python version.

5. **Web UI differences**: Python uses NiceGUI (reactive SPA). Java uses Thymeleaf + HTMX (server-rendered with progressive enhancement). Same functionality, different implementation.
