<p align="center">
  <h1 align="center">OSSCodeIQ</h1>
  <p align="center">
    <strong>Deterministic code knowledge graph -- scans codebases to build a graph of services, endpoints, entities, infrastructure, auth patterns, and framework usage. No AI, pure static analysis.</strong>
  </p>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.randomcodespace.iq/code-iq"><img src="https://img.shields.io/maven-central/v/io.github.randomcodespace.iq/code-iq?style=flat-square&logo=apachemaven&label=Maven%20Central" alt="Maven Central"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq/actions/workflows/ci-java.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/code-iq/ci-java.yml?branch=main&style=flat-square&logo=github&label=CI" alt="CI"></a>
  <a href="https://www.oracle.com/java/technologies/downloads/"><img src="https://img.shields.io/badge/Java-25-orange?style=flat-square&logo=openjdk&logoColor=white" alt="Java 25"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq/blob/main/LICENSE"><img src="https://img.shields.io/github/license/RandomCodeSpace/code-iq?style=flat-square&label=License" alt="MIT License"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=security_rating" alt="Security"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=reliability_rating" alt="Reliability"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq"><img src="https://img.shields.io/badge/detectors-97-brightgreen?style=flat-square&logo=codefactor&logoColor=white" alt="97 Detectors"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq"><img src="https://img.shields.io/badge/languages-35%2B-blue?style=flat-square&logo=stackblitz&logoColor=white" alt="35+ Languages"></a>
</p>

---

**OSSCodeIQ** scans codebases to build a deterministic knowledge graph of code relationships -- classes, methods, endpoints, entities, dependencies, infrastructure resources, auth patterns, service topology, and more. 97 detectors across 35+ languages, Neo4j Embedded graph database, Spring AI MCP server (31 tools), REST API (34 endpoints), React web UI, and zero AI dependency.

## Quick Start

```bash
# Build from source
git clone https://github.com/RandomCodeSpace/code-iq.git
cd code-iq
mvn clean package -DskipTests

# Analyze a codebase
java -jar target/code-iq-*-cli.jar analyze /path/to/repo

# Memory-efficient indexing (for large repos / CI)
java -jar target/code-iq-*-cli.jar index /path/to/repo

# View rich statistics
java -jar target/code-iq-*-cli.jar stats /path/to/repo

# Start server (REST + MCP + React UI)
java -jar target/code-iq-*-cli.jar serve /path/to/repo
# Open http://localhost:8080
```

## Features

- **97 detectors** across 35+ languages -- Java, Python, TypeScript, Go, C#, Rust, Kotlin, Scala, C++, and more
- **JavaParser AST** for deep Java analysis (Spring, JPA, Kafka, gRPC, JAX-RS, etc.)
- **ANTLR grammars** for 10 languages (TypeScript, JavaScript, Python, Go, C#, Rust, Kotlin, Scala, C++)
- **Neo4j Embedded** graph database -- full Cypher query support, no external server needed
- **H2 analysis cache** -- batched streaming for memory-efficient indexing on CI runners
- **Spring AI MCP server** -- 31 tools via streamable HTTP for AI-powered triage
- **REST API** -- 34 endpoints for programmatic access
- **React UI** -- Dashboard, Topology (Cytoscape.js), Explorer, Flow, MCP Console, API Docs (Swagger)
- **Service Topology** -- AppDynamics-style service map with blast radius, circular deps, bottleneck detection
- **CLI with 15 commands** -- analyze, index, enrich, serve, stats, graph, query, find, cypher, flow, topology, bundle, cache, plugins, version
- **Virtual threads** (Java 25) -- adaptive parallelism across all available cores
- **Config-driven pipeline** -- `.osscodeiq.yml` to control languages, detectors, parsers, excludes
- **Multi-repo support** -- `--graph` + `--service-name` for shared graph across repositories
- **Flow diagrams** -- interactive Cytoscape.js architecture diagrams (Overview, CI, Deploy, Runtime, Auth views)
- **Bundle & distribute** -- package graph DB + source + interactive HTML into a ZIP
- **100% deterministic** -- same input, same output, every time
- **Incremental analysis** -- H2-backed file hash cache, only re-analyzes changed files
- **1,440 tests** passing

## Three-Command Architecture

For memory-constrained environments (K8s CI runners, 4GB RAM):

```bash
# 1. Index: batched H2 streaming, low memory (~1-2GB for 20K files)
java -jar code-iq-*-cli.jar index /path/to/repo --batch-size 500

# 2. Enrich: load H2 into Neo4j, run linkers + classifier + topology
java -jar code-iq-*-cli.jar enrich /path/to/repo

# 3. Serve: REST API + MCP + React UI
java -jar code-iq-*-cli.jar serve /path/to/repo
```

For quick analysis (sufficient memory available):

```bash
# Single-command: in-memory analysis + Neo4j
java -jar code-iq-*-cli.jar analyze /path/to/repo
```

## Frameworks Detected

### Java
Spring REST, Spring Security, JPA/Hibernate, Kafka, RabbitMQ, JMS, gRPC, JAX-RS, WebSocket, Azure Functions, Cosmos DB, IBM MQ, TIBCO EMS, Quarkus, Micronaut, Spring Events, RMI

### Python
Flask, Django (views + models + auth), FastAPI (routes + auth), SQLAlchemy, Celery, Pydantic, Kafka (confluent/aiokafka)

### TypeScript / JavaScript
Express, NestJS (controllers + guards), Fastify, Remix, GraphQL resolvers, TypeORM, Prisma, Sequelize, Mongoose, KafkaJS, Passport/JWT

### Frontend
React, Vue, Angular, Svelte components, frontend routes (React Router, Vue Router, Next.js, Remix)

### Go
Gin, Echo, Chi, gorilla/mux, net/http, GORM, sqlx, database/sql

### C#
Entity Framework Core, Minimal APIs, ASP.NET Core, Azure Functions

### Rust
Actix-web, Axum, traits, impls, macros

### Kotlin
Ktor routes, sealed/enum/annotation classes, extension functions

### Infrastructure & Config
Terraform, Kubernetes, K8s RBAC, Docker Compose, Dockerfile, Bicep, GitHub Actions, GitLab CI, Helm Charts, CloudFormation, OpenAPI, JSON, YAML, TOML, INI, Properties, SQL, Markdown, Proto

### Auth & Security
Spring Security, Django Auth, FastAPI Auth, NestJS Guards, Passport/JWT, K8s RBAC, LDAP, TLS/Certificate/Azure AD, Session/Header/CSRF

## CLI Commands

| Command | Description |
|---------|-------------|
| `analyze [path]` | Scan codebase and build knowledge graph (in-memory, legacy) |
| `index [path]` | Memory-efficient batched indexing to H2 (for CI/large repos) |
| `enrich [path]` | Load H2 into Neo4j, run linkers + classifier + topology |
| `serve [path]` | Start React UI + REST API + MCP server |
| `stats [path]` | Show rich categorized statistics (graph, languages, frameworks, infra, auth) |
| `graph [path]` | Export graph in various formats (JSON, YAML, Mermaid, DOT) |
| `query [path]` | Query graph relationships (consumers, producers, callers, etc.) |
| `find [what] [path]` | Preset queries (endpoints, guards, entities, topics, etc.) |
| `cypher [query]` | Execute raw Cypher queries against Neo4j |
| `topology [path]` | Service topology queries (blast radius, circular deps, bottlenecks) |
| `flow [path]` | Generate architecture flow diagrams |
| `bundle [path]` | Package graph + source into distributable ZIP |
| `cache [action]` | Manage analysis cache (status, clear, rebuild) |
| `plugins [action]` | List/inspect detectors, suggest config, generate docs |
| `version` | Show version info |

## Architecture

```
code-iq index /path/to/repo
        |
        v
+------------------+
| File Discovery   |  git ls-files + extension/filename mapping (35+ languages)
+--------+---------+
         |
         v
+------------------+
| Parsing Layer    |  JavaParser AST (Java) + ANTLR (10 grammars) + regex fallback
+--------+---------+
         |
         v
+------------------+
| 97 Detectors     |  Spring-managed beans, virtual thread parallelism
+--------+---------+
         |
         v
+------------------+
| Graph Builder    |  Buffered flush: nodes first, then edges (determinism)
+--------+---------+
         |
         v
+------------------+
| H2 Cache         |  Batched streaming (500 files/batch), incremental support
+--------+---------+

code-iq enrich /path/to/repo
         |
         v
+------------------+
| Neo4j Bulk Load  |  H2 -> Neo4j Embedded, full Cypher support
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
| Layer Classifier |  frontend / backend / infra / shared / unknown
+--------+---------+
         |
         v
+------------------+
| Service Detector |  Auto-detect modules from build files (pom.xml, package.json, etc.)
+------------------+

code-iq serve /path/to/repo
         |
    +----+----+--------+
    |         |        |
    v         v        v
 REST API   MCP     React UI
 (34 ep)   (31 tools) (6 pages)
```

## Server

Start a unified server with React UI, REST API, and MCP server on a single port:

```bash
java -jar target/code-iq-*-cli.jar serve /path/to/repo --port 8080
```

### React UI (`/`)
Modern React 18 + TypeScript + Tailwind CSS interface:
- **Dashboard** -- graph statistics, language/framework breakdown, top node kinds
- **Topology** -- Cytoscape.js service dependency map with drill-down
- **Explorer** -- browse by node kind, click to drill into details with edges
- **Flow** -- interactive architecture diagrams (Overview, CI, Deploy, Runtime, Auth)
- **Console** -- MCP Inspector for tool invocation with category filters and JSON response viewer
- **API Docs** -- embedded Swagger/OpenAPI documentation
- Dark/light/system theme toggle

### REST API (`/api`)
34 endpoints for programmatic access:
- `/api/stats`, `/api/stats/detailed?category=` -- graph and categorized statistics
- `/api/kinds`, `/api/kinds/{kind}` -- node kinds with counts, paginated nodes
- `/api/nodes`, `/api/edges` -- paginated queries with `?kind=&limit=&offset=`
- `/api/nodes/{id}/detail`, `/api/nodes/{id}/neighbors` -- node detail and traversal
- `/api/ego/{center}` -- ego subgraph
- `/api/query/cycles`, `/shortest-path`, `/consumers/{id}`, `/producers/{id}`, `/callers/{id}`, `/dependencies/{id}`, `/dependents/{id}`
- `/api/topology`, `/api/topology/services/{name}`, `/api/topology/blast-radius/{id}`, `/api/topology/circular`, `/api/topology/bottlenecks`, `/api/topology/dead`
- `/api/triage/component?file=`, `/api/triage/impact/{id}` -- agentic triage
- `/api/search?q=` -- free-text graph search
- `/api/file?path=` -- source files (path traversal protected)
- `/api/flow`, `/api/flow/{view}`, `/api/flow/{view}/{nodeId}/children`, `/api/flow/{view}/{nodeId}/parent` -- flow diagrams
- `/api/topology/services/{name}/deps`, `/api/topology/services/{name}/dependents` -- service dependencies
- `/api/topology/path` -- find path between services

### MCP Server (`/mcp`)
31 tools via Spring AI streamable HTTP for AI-powered code triage:

**Core (21 tools):**
`get_stats`, `get_detailed_stats`, `query_nodes`, `query_edges`, `get_node_neighbors`, `get_ego_graph`, `find_cycles`, `find_shortest_path`, `find_consumers`, `find_producers`, `find_callers`, `find_dependencies`, `find_dependents`, `find_dead_code`, `generate_flow`, `run_cypher`, `find_component_by_file`, `trace_impact`, `find_related_endpoints`, `search_graph`, `read_file`

**Topology (10 tools):**
`get_topology`, `service_detail`, `service_dependencies`, `service_dependents`, `blast_radius`, `find_path`, `find_bottlenecks`, `find_circular_deps`, `find_dead_services`, `find_node`

## Service Topology

AppDynamics-style service topology from static code analysis:

```bash
# View service topology
java -jar code-iq-*-cli.jar topology /path/to/monorepo

# Blast radius analysis
java -jar code-iq-*-cli.jar topology /path/to/repo --blast-radius service-name

# Multi-repo support
java -jar code-iq-*-cli.jar index /repo1 --graph /shared --service-name frontend
java -jar code-iq-*-cli.jar index /repo2 --graph /shared --service-name backend
java -jar code-iq-*-cli.jar serve /shared
```

- Auto-detects service boundaries from build files (pom.xml, package.json, go.mod, build.gradle, Cargo.toml, *.csproj)
- Runtime connections only: CALLS, PRODUCES, CONSUMES, QUERIES, CONNECTS_TO
- Build dependencies excluded from topology (SBOM only)
- Blast radius, circular dependency detection, bottleneck analysis, dead service detection

## Config-Driven Pipeline

Create `.osscodeiq.yml` in your repo root, or auto-generate with `code-iq plugins suggest`:

```yaml
pipeline:
  parallelism: 4
  batch-size: 500

languages:
  - java
  - typescript
  - yaml

detectors:
  categories:
    - endpoints
    - entities
    - auth
    - config

exclude:
  - "**/node_modules/**"
  - "**/build/**"
  - "**/*.min.js"
```

```bash
# Auto-generate optimized config for your repo
java -jar code-iq-*-cli.jar plugins suggest /path/to/repo

# List all detectors by category
java -jar code-iq-*-cli.jar plugins list

# Generate detector reference docs
java -jar code-iq-*-cli.jar plugins docs --format markdown
```

## Graph Model

### Node Types (32)
`module` `package` `class` `method` `endpoint` `entity` `repository` `query` `migration` `topic` `queue` `event` `interface` `abstract_class` `enum` `annotation_type` `protocol_message` `config_file` `config_key` `config_definition` `database_connection` `infra_resource` `azure_resource` `azure_function` `message_queue` `websocket_endpoint` `rmi_interface` `component` `guard` `middleware` `hook` `service`

### Edge Types (27)
`depends_on` `imports` `extends` `implements` `calls` `injects` `exposes` `queries` `maps_to` `produces` `consumes` `publishes` `listens` `invokes_rmi` `exports_rmi` `reads_config` `migrates` `contains` `defines` `overrides` `connects_to` `triggers` `provisions` `sends_to` `receives_from` `protects` `renders`

## Benchmark Results

Benchmarked on 13 real-world projects. All results deterministic across 3 runs.

| Project | Files | Nodes | Edges | Time |
|---------|-------|-------|-------|------|
| kubernetes | 20,240 | 193,391 | 349,707 | 9s |
| kafka | 6,919 | 62,692 | 120,422 | 50s |
| django | 3,467 | 51,402 | 99,086 | 54s |
| spring-boot | 10,524 | 27,993 | 39,776 | 27s |
| fastapi | 2,740 | 25,475 | 30,430 | 10s |
| bitnami-charts | 3,699 | 46,363 | 78,263 | 4s |
| nest | 2,037 | 5,757 | 11,904 | 1s |

### Memory Profile

| Mode | Project | Peak RAM |
|------|---------|----------|
| `analyze` (in-memory) | kubernetes 20K files | 2.9 GB |
| `index` (batched H2) | kubernetes 20K files | 2.1 GB |
| `index` (batched H2) | terraform 9K files | 1.0 GB |

## Development

```bash
# Prerequisites: Java 25+, Maven 3.9+
git clone https://github.com/RandomCodeSpace/code-iq.git
cd code-iq

# Build
mvn clean package

# Run tests (1,440 tests)
mvn test

# Analyze this repo
java -jar target/code-iq-*-cli.jar analyze .

# Start dev server
java -jar target/code-iq-*-cli.jar serve .
```

## Maven Coordinates

```xml
<dependency>
    <groupId>io.github.randomcodespace.iq</groupId>
    <artifactId>code-iq</artifactId>
    <version>0.0.1-beta.0</version>
</dependency>
```

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 25 (virtual threads, pattern matching, records) |
| Framework | Spring Boot 4.0.5 |
| Graph DB | Neo4j Embedded 2026.02.3 (Community Edition) |
| Analysis Cache | H2 (pure Java, virtual thread safe) |
| Cache | Spring Cache (simple in-memory, @Cacheable on query methods) |
| MCP | Spring AI 2.0.0-M3 (streamable HTTP) |
| Java AST | JavaParser 3.28.0 |
| Multi-lang AST | ANTLR 4.13.2 (10 grammars) |
| CLI | Picocli 4.7.7 |
| Web UI | React 18 + TypeScript + Vite + Tailwind CSS |
| Build | Maven + Spring Boot Plugin |

## License

MIT License. See [LICENSE](LICENSE) for details.

---

<p align="center">
  Built with intelligence. No AI required.
</p>
