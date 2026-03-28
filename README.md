<p align="center">
  <h1 align="center">OSSCodeIQ</h1>
  <p align="center">
    <strong>Deterministic code graph discovery and analysis CLI — no AI, pure pattern matching</strong>
  </p>
</p>

<p align="center">
  <a href="https://github.com/RandomCodeSpace/code-iq/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/code-iq/ci.yml?branch=main&style=flat-square&logo=github&label=CI" alt="CI"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq/actions/workflows/beta.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/code-iq/beta.yml?branch=main&style=flat-square&logo=github&label=Beta" alt="Beta Build"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq/releases"><img src="https://img.shields.io/github/v/release/RandomCodeSpace/code-iq?include_prereleases&style=flat-square&logo=github&label=Release" alt="Release"></a>
  <a href="https://www.python.org/downloads/"><img src="https://img.shields.io/badge/python-3.11%2B-blue?style=flat-square&logo=python&logoColor=white" alt="Python 3.11+"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq/blob/main/LICENSE"><img src="https://img.shields.io/github/license/RandomCodeSpace/code-iq?style=flat-square&label=License" alt="MIT License"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq/actions/workflows/sbom.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/code-iq/sbom.yml?branch=main&style=flat-square&logo=shieldsdotio&logoColor=white&label=SBOM%20%2B%20Audit" alt="SBOM + Dependency Audit"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=security_rating&style=flat-square" alt="Sonarcloud Security"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=reliability_rating" alt="Sonarcloud Reliability"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=sqale_rating" alt="Sonarcloud Maintainability"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=bugs" alt="Sonarcloud Bugs"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=vulnerabilities" alt="Sonarcloud Vulnerabilities"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq"><img src="https://img.shields.io/github/stars/RandomCodeSpace/code-iq?style=flat-square&logo=github&label=Stars" alt="Stars"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq/issues"><img src="https://img.shields.io/github/issues/RandomCodeSpace/code-iq?style=flat-square&logo=github&label=Issues" alt="Issues"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq/commits/main"><img src="https://img.shields.io/github/last-commit/RandomCodeSpace/code-iq?style=flat-square&logo=github&label=Last%20Commit" alt="Last Commit"></a>
  <a href="https://pypi.org/project/osscodeiq/"><img src="https://img.shields.io/pypi/v/osscodeiq?style=flat-square&logo=pypi&logoColor=white&label=PyPI" alt="PyPI"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq"><img src="https://img.shields.io/badge/detectors-115-brightgreen?style=flat-square&logo=codefactor&logoColor=white" alt="115 Detectors"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq"><img src="https://img.shields.io/badge/languages-35-blue?style=flat-square&logo=stackblitz&logoColor=white" alt="35 Languages"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq"><img src="https://img.shields.io/badge/tests-2146-brightgreen?style=flat-square&logo=pytest&logoColor=white" alt="2146 Tests"></a>
</p>

---

**OSSCodeIQ** scans codebases to build a deterministic knowledge graph of code relationships — classes, methods, endpoints, entities, dependencies, infrastructure resources, auth patterns, and more. 115 detectors across 35 languages, 3 storage backends (NetworkX, SQLite, KuzuDB), interactive web UI, REST API, MCP server, and zero AI dependency.

## Features

- **115 detectors** across 35 languages — Java, Python, TypeScript, Go, C#, Rust, Kotlin, and more
- **Framework detection** — Spring Boot, Django, Flask, FastAPI, Express, NestJS, Gin, Echo, Actix-web, Axum, Quarkus, Micronaut, Prisma, Sequelize, Mongoose, Pydantic, Entity Framework Core, and 60+ more
- **Auth/security detection** — Spring Security, Django Auth, FastAPI Auth, NestJS Guards, Passport/JWT, LDAP, Azure AD, mTLS, CSRF, session/cookie auth
- **Frontend detection** — React, Vue, Angular, Svelte components, hooks, frontend routes (React Router, Vue Router, Next.js, Remix)
- **Infrastructure** — Terraform, Kubernetes, Docker Compose, Helm Charts, CloudFormation, Bicep, GitLab CI, GitHub Actions
- **Layer classification** — Every node tagged as `frontend`, `backend`, `infra`, `shared`, or `unknown`
- **Web Explorer UI** — NiceGUI-powered progressive drill-down card interface with light/dark/system themes, animations, and search
- **MCP Tool Console** — Interactive terminal in the web UI for executing MCP graph queries
- **Flow diagrams** — Interactive Cytoscape.js architecture diagrams with drill-down (CI, Deploy, Runtime, Auth views)
- **REST API + MCP server** — 20+ REST endpoints and 20 MCP tools on a single port
- **3 storage backends** — NetworkX (in-memory), SQLite (file-based), KuzuDB (Cypher queries)
- **Bundle & distribute** — Package graph DB + source code + interactive HTML into a zip for Nexus/artifact publishing
- **100% deterministic** — Same input, same output, every time, on every backend
- **Plugin system** — Auto-discovered detectors + setuptools entry points for external plugins

## Quick Start

```bash
# Install from PyPI
pip install osscodeiq

# Analyze a codebase
osscodeiq analyze /path/to/repo

# Start the web UI + REST API + MCP server
osscodeiq serve /path/to/repo
# Open http://localhost:8000 — Explorer UI with drill-down cards, flow diagrams, MCP console

# Generate architecture flow diagram
osscodeiq flow /path/to/repo --format html --output flow.html

# Query the graph
osscodeiq find endpoints /path/to/repo
osscodeiq find guards /path/to/repo
osscodeiq find unprotected /path/to/repo

# Use Cypher queries (KuzuDB backend)
osscodeiq analyze /path/to/repo --backend kuzu
osscodeiq cypher "MATCH (e:CodeNode {kind: 'endpoint'})-[]->(s:CodeNode) RETURN e.label, s.label LIMIT 20" /path/to/repo --backend kuzu

# Bundle for distribution (graph DB + source code + visualizations)
osscodeiq bundle /path/to/repo --tag v2.1.0 --backend sqlite
```

## Supported Languages & Frameworks

### Java (28 detectors)
Spring REST, Spring Security, JPA/Hibernate, Kafka, RabbitMQ, JMS, gRPC, JAX-RS, WebSocket, Azure Functions, Cosmos DB, IBM MQ, TIBCO EMS, Quarkus, Micronaut

### Python (12 detectors)
Flask, Django (views + models), FastAPI, SQLAlchemy, Celery, Pydantic, Kafka (confluent/aiokafka), general structures (classes, functions, imports)

### TypeScript/JavaScript (22 detectors)
Express, NestJS, Fastify, Remix, GraphQL, TypeORM, Prisma, Sequelize, Mongoose, KafkaJS, React, Vue, Angular, Svelte, frontend routes

### Go (3 detectors)
Gin, Echo, Chi, gorilla/mux, net/http endpoints + GORM, sqlx, database/sql + general structures

### C# (4 detectors)
Entity Framework Core, Minimal APIs, ASP.NET Core, Azure Functions

### Rust (2 detectors)
Actix-web, Axum + general structures (traits, impls, macros)

### Kotlin (2 detectors)
Ktor + general structures (sealed/enum/annotation classes, extension functions)

### Infrastructure & Config (16 detectors)
Terraform, Kubernetes, K8s RBAC, Docker Compose, Dockerfile, Bicep, GitHub Actions, GitLab CI, Helm Charts, CloudFormation, JSON, YAML, TOML, INI, Properties, Markdown, Proto

### Auth & Security (9 detectors)
Spring Security, Django Auth, FastAPI Auth, NestJS Guards, Passport/JWT, K8s RBAC, LDAP, TLS/Certificate/Azure AD, Session/Header/CSRF

## Architecture

```
osscodeiq analyze /path/to/repo
        |
        v
+------------------+
| File Discovery   |  git ls-files + extension/filename mapping (35 languages)
+--------+---------+
         |
         v
+------------------+
| Parsing Layer    |  Tree-sitter (Java/Python/TS/JS) + structured parsers
+--------+---------+
         |
         v
+------------------+
| 115 Detectors    |  Auto-discovered via pkgutil, adaptive parallel workers
+--------+---------+
         |
         v
+------------------+
| Layer Classifier |  frontend / backend / infra / shared / unknown
+--------+---------+
         |
         v
+------------------+
| Cross-file       |  Topic linking, entity-repo matching, module containment
| Linkers          |
+--------+---------+
         |
         v
+------------------+
| Graph Backend    |  NetworkX (memory) | SQLite (file) | KuzuDB (Cypher)
+------------------+
         |
         v
+------------------+
| Output           |  JSON | YAML | Mermaid | DOT | Interactive HTML
+------------------+
```

## Flow Diagrams

Generate architecture flow diagrams with drill-down views:

```bash
# High-level overview
osscodeiq flow ./my-project --format mermaid

# Drill into specific layers
osscodeiq flow ./my-project --view ci       # CI/CD pipeline
osscodeiq flow ./my-project --view deploy   # Deployment topology
osscodeiq flow ./my-project --view runtime  # Service architecture
osscodeiq flow ./my-project --view auth     # Security coverage

# Interactive HTML with click-to-drill
osscodeiq flow ./my-project --format html --output flow.html
```

## Graph Model

### Node Types (31)
`module` `package` `class` `method` `endpoint` `entity` `repository` `query` `migration` `topic` `queue` `event` `interface` `abstract_class` `enum` `annotation_type` `protocol_message` `config_file` `config_key` `config_definition` `database_connection` `infra_resource` `azure_resource` `azure_function` `message_queue` `websocket_endpoint` `rmi_interface` `component` `guard` `middleware` `hook`

### Edge Types (26)
`depends_on` `imports` `extends` `implements` `calls` `injects` `exposes` `queries` `maps_to` `produces` `consumes` `publishes` `listens` `invokes_rmi` `exports_rmi` `reads_config` `migrates` `contains` `defines` `overrides` `connects_to` `triggers` `provisions` `sends_to` `receives_from` `protects` `renders`

## Storage Backends

| Backend | Type | Cypher | Bundleable | Use Case |
|---------|------|--------|------------|----------|
| **SQLite** | File | No | .db file | Default, persistent, zero dependencies |
| **NetworkX** | In-memory | No | Via JSON | Fast in-process use |
| **KuzuDB** | File | Yes | Directory | Cypher queries, agentic AI |

```bash
osscodeiq analyze ./repo                   # SQLite (default)
osscodeiq analyze ./repo --backend kuzu    # KuzuDB with Cypher
osscodeiq analyze ./repo --backend networkx # In-memory
```

## Web UI & Server

Start a unified server with Explorer UI, REST API, and MCP server on a single port:

```bash
osscodeiq serve /path/to/repo
```

**Explorer UI** (`/ui`) — Progressive drill-down card interface:
- Browse by node kind (Endpoints, Entities, Classes, Guards, etc.)
- Click "Explore" to drill into individual nodes
- Click "Details" on any card for a full modal with properties, edges, and source location
- Client-side search filtering (no server round-trip)
- Light, dark, and system theme support with runtime toggle

**MCP Console** — Interactive terminal tab for executing MCP tools:
- 20 tools: `get_stats`, `search_graph`, `trace_impact`, `find_cycles`, etc.
- Type `help` to see all available commands

**REST API** (`/api`) — 20+ endpoints for programmatic access:
- `/api/kinds` — Node kinds with counts
- `/api/kinds/{kind}` — Paginated nodes by kind
- `/api/nodes/{id}/detail` — Full node detail with edges
- `/api/stats`, `/api/nodes`, `/api/edges`, `/api/search`, `/api/flow` and more
- Full OpenAPI docs at `/docs`

**MCP Server** (`/mcp`) — 20 tools via streamable HTTP for AI-powered triage

## Development

```bash
git clone https://github.com/RandomCodeSpace/code-iq.git
cd code-iq
pip install -e ".[dev]"
pytest                    # 2,146 tests
osscodeiq analyze .       # Analyze this repo
osscodeiq serve .         # Start the web UI
```

### Adding a New Detector

Just create a file — auto-discovered, zero registration:

```python
# src/osscodeiq/detectors/python/my_detector.py
from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.utils import decode_text
from osscodeiq.models.graph import GraphNode, NodeKind, SourceLocation

class MyDetector:
    name = "my_detector"
    supported_languages = ("python",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)
        # Your detection logic here
        return result
```

## Requirements

- Python 3.11+
- Dependencies: typer, rich, tree-sitter, networkx, lxml, pyyaml, sqlparse, pydantic, fastapi, uvicorn, fastmcp, nicegui
- Optional: `pip install kuzu` for KuzuDB backend

## License

MIT License. See [LICENSE](LICENSE) for details.

---

<p align="center">
  Built with intelligence. No AI required.
</p>
