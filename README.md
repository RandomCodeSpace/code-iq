<p align="center">
  <h1 align="center">Code Intelligence</h1>
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
  <a href="https://app.snyk.io"><img src="https://img.shields.io/snyk/vulnerabilities/github/RandomCodeSpace/code-iq?style=flat-square&logo=snyk&label=Snyk" alt="Snyk Vulnerabilities"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=security_rating&style=flat-square" alt="Sonarcloud Security"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=reliability_rating" alt="Sonarcloud Reliability"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=sqale_rating" alt="Sonarcloud Maintainability"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=bugs" alt="Sonarcloud Bugs"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=vulnerabilities" alt="Sonarcloud Vulnerabilities"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq"><img src="https://img.shields.io/github/stars/RandomCodeSpace/code-iq?style=flat-square&logo=github&label=Stars" alt="Stars"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq/issues"><img src="https://img.shields.io/github/issues/RandomCodeSpace/code-iq?style=flat-square&logo=github&label=Issues" alt="Issues"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq/commits/main"><img src="https://img.shields.io/github/last-commit/RandomCodeSpace/code-iq?style=flat-square&logo=github&label=Last%20Commit" alt="Last Commit"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq"><img src="https://img.shields.io/badge/detectors-97-brightgreen?style=flat-square&logo=codefactor&logoColor=white" alt="97 Detectors"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq"><img src="https://img.shields.io/badge/languages-35-blue?style=flat-square&logo=stackblitz&logoColor=white" alt="35 Languages"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq"><img src="https://img.shields.io/badge/tests-1662-brightgreen?style=flat-square&logo=pytest&logoColor=white" alt="1662 Tests"></a>
</p>

---

**Code Intelligence** scans codebases to build a deterministic knowledge graph of code relationships — classes, methods, endpoints, entities, dependencies, infrastructure resources, auth patterns, and more. 97 detectors across 35 languages, 3 storage backends (NetworkX, SQLite, KuzuDB), interactive flow diagrams, and zero AI dependency.

## Features

- **97 detectors** across 35 languages — Java, Python, TypeScript, Go, C#, Rust, Kotlin, and more
- **Framework detection** — Spring Boot, Django, Flask, FastAPI, Express, NestJS, Gin, Echo, Actix-web, Axum, Quarkus, Micronaut, Prisma, Sequelize, Mongoose, Pydantic, Entity Framework Core, and 60+ more
- **Auth/security detection** — Spring Security, Django Auth, FastAPI Auth, NestJS Guards, Passport/JWT, LDAP, Azure AD, mTLS, CSRF, session/cookie auth
- **Frontend detection** — React, Vue, Angular, Svelte components, hooks, frontend routes (React Router, Vue Router, Next.js, Remix)
- **Infrastructure** — Terraform, Kubernetes, Docker Compose, Helm Charts, CloudFormation, Bicep, GitLab CI, GitHub Actions
- **Layer classification** — Every node tagged as `frontend`, `backend`, `infra`, `shared`, or `unknown`
- **Flow diagrams** — Generate interactive Mermaid architecture diagrams with drill-down (CI, Deploy, Runtime, Auth views)
- **3 storage backends** — NetworkX (in-memory), SQLite (file-based), KuzuDB (Cypher queries)
- **Bundle & distribute** — Package the graph DB + interactive HTML into a zip for sharing
- **100% deterministic** — Same input, same output, every time, on every backend
- **Plugin system** — Auto-discovered detectors + setuptools entry points for external plugins

## Quick Start

```bash
# Install
pip install -e .

# Analyze a codebase
code-intelligence analyze /path/to/repo

# Generate architecture flow diagram
code-intelligence flow /path/to/repo --format html --output flow.html

# Query the graph
code-intelligence find endpoints /path/to/repo
code-intelligence find guards /path/to/repo
code-intelligence find unprotected /path/to/repo

# Use Cypher queries (KuzuDB backend)
code-intelligence analyze /path/to/repo --backend kuzu
code-intelligence cypher "MATCH (e:CodeNode {kind: 'endpoint'})-[]->(s:CodeNode) RETURN e.label, s.label LIMIT 20" /path/to/repo --backend kuzu

# Bundle for distribution
code-intelligence bundle /path/to/repo --tag v2.1.0 --backend kuzu
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
code-intelligence analyze /path/to/repo
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
| 97 Detectors     |  Auto-discovered via pkgutil, 8 parallel workers
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
code-intelligence flow ./my-project --format mermaid

# Drill into specific layers
code-intelligence flow ./my-project --view ci       # CI/CD pipeline
code-intelligence flow ./my-project --view deploy   # Deployment topology
code-intelligence flow ./my-project --view runtime  # Service architecture
code-intelligence flow ./my-project --view auth     # Security coverage

# Interactive HTML with click-to-drill
code-intelligence flow ./my-project --format html --output flow.html
```

## Graph Model

### Node Types (31)
`module` `package` `class` `method` `endpoint` `entity` `repository` `query` `migration` `topic` `queue` `event` `interface` `abstract_class` `enum` `annotation_type` `protocol_message` `config_file` `config_key` `config_definition` `database_connection` `infra_resource` `azure_resource` `azure_function` `message_queue` `websocket_endpoint` `rmi_interface` `component` `guard` `middleware` `hook`

### Edge Types (26)
`depends_on` `imports` `extends` `implements` `calls` `injects` `exposes` `queries` `maps_to` `produces` `consumes` `publishes` `listens` `invokes_rmi` `exports_rmi` `reads_config` `migrates` `contains` `defines` `overrides` `connects_to` `triggers` `provisions` `sends_to` `receives_from` `protects` `renders`

## Storage Backends

| Backend | Type | Cypher | Bundleable | Use Case |
|---------|------|--------|------------|----------|
| **NetworkX** | In-memory | No | Via JSON | Default, fastest for analysis |
| **SQLite** | File | No | .db file | Persistent, zero dependencies |
| **KuzuDB** | File | Yes | Directory | Cypher queries, agentic AI |

```bash
code-intelligence analyze ./repo --backend kuzu
code-intelligence analyze ./repo --backend sqlite
```

## Development

```bash
git clone https://github.com/RandomCodeSpace/code-iq.git
cd code-iq
pip install -e ".[dev]"
pytest                    # 1,662 tests
code-intelligence analyze . # Analyze this repo
```

### Adding a New Detector

Just create a file — auto-discovered, zero registration:

```python
# src/code_intelligence/detectors/python/my_detector.py
from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.utils import decode_text
from code_intelligence.models.graph import GraphNode, NodeKind, SourceLocation

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
- Dependencies: typer, rich, tree-sitter, networkx, lxml, pyyaml, sqlparse, pydantic
- Optional: `pip install kuzu` for KuzuDB backend

## License

MIT License. See [LICENSE](LICENSE) for details.

---

<p align="center">
  Built with intelligence. No AI required.
</p>
