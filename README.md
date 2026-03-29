<p align="center">
  <h1 align="center">OSSCodeIQ</h1>
  <p align="center">
    <strong>Deterministic code knowledge graph -- scans codebases to build a graph of services, endpoints, entities, infrastructure, auth patterns, and framework usage. No AI, pure static analysis.</strong>
  </p>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.randomcodespace.iq/code-iq"><img src="https://img.shields.io/maven-central/v/io.github.randomcodespace.iq/code-iq?style=flat-square&logo=apachemaven&label=Maven%20Central" alt="Maven Central"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/code-iq/ci.yml?branch=java&style=flat-square&logo=github&label=CI" alt="CI"></a>
  <a href="https://www.oracle.com/java/technologies/downloads/"><img src="https://img.shields.io/badge/Java-25-orange?style=flat-square&logo=openjdk&logoColor=white" alt="Java 25"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq/blob/java/LICENSE"><img src="https://img.shields.io/github/license/RandomCodeSpace/code-iq?style=flat-square&label=License" alt="MIT License"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=security_rating" alt="Security"></a>
  <a href="https://sonarcloud.io/summary/overall?id=RandomCodeSpace_code-iq"><img src="https://sonarcloud.io/api/project_badges/measure?project=RandomCodeSpace_code-iq&metric=reliability_rating" alt="Reliability"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq"><img src="https://img.shields.io/badge/detectors-106-brightgreen?style=flat-square&logo=codefactor&logoColor=white" alt="106 Detectors"></a>
  <a href="https://github.com/RandomCodeSpace/code-iq"><img src="https://img.shields.io/badge/languages-35%2B-blue?style=flat-square&logo=stackblitz&logoColor=white" alt="35+ Languages"></a>
</p>

---

**OSSCodeIQ** scans codebases to build a deterministic knowledge graph of code relationships -- classes, methods, endpoints, entities, dependencies, infrastructure resources, auth patterns, and more. 106 detectors across 35+ languages, Neo4j Embedded graph database, Hazelcast distributed cache, Spring AI MCP server, REST API, web UI, and zero AI dependency.

## Quick Start

```bash
# From Maven Central
mvn dependency:resolve -DgroupId=io.github.randomcodespace.iq -DartifactId=code-iq

# Or build from source
git clone https://github.com/RandomCodeSpace/code-iq.git
cd code-iq && git checkout java
mvn clean package -DskipTests

# Analyze a codebase
java -jar target/code-iq-*.jar analyze /path/to/repo

# View rich statistics
java -jar target/code-iq-*.jar stats /path/to/repo

# Start server (REST + MCP + UI)
java -jar target/code-iq-*.jar serve /path/to/repo
# Open http://localhost:8080 -- Explorer UI with drill-down cards, flow diagrams, MCP console
```

## Features

- **106 detectors** across 35+ languages -- Java, Python, TypeScript, Go, C#, Rust, Kotlin, Scala, C++, and more
- **JavaParser AST** for deep Java analysis (Spring, JPA, Kafka, gRPC, JAX-RS, etc.)
- **ANTLR grammars** for 6 languages (TypeScript/JavaScript, Python, Go, C#, Rust, C++)
- **Neo4j Embedded** graph database -- full Cypher query support, no external server needed
- **Hazelcast distributed cache** -- K8s-ready, multi-node incremental analysis
- **Spring AI MCP server** -- 21 tools via streamable HTTP for AI-powered triage
- **REST API** -- 23+ endpoints for programmatic access
- **Web UI** -- Thymeleaf + HTMX progressive drill-down explorer with search
- **CLI with 12 commands** -- analyze, stats, graph, query, find, cypher, flow, serve, bundle, cache, plugins, version
- **Virtual threads** (Java 25) -- adaptive parallelism across all available cores
- **Flow diagrams** -- interactive Cytoscape.js architecture diagrams (CI, Deploy, Runtime, Auth views)
- **Bundle & distribute** -- package graph DB + source + interactive HTML into a ZIP
- **100% deterministic** -- same input, same output, every time
- **Incremental analysis** -- SQLite-backed file hash cache, only re-analyzes changed files

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
| `analyze [path]` | Scan codebase and build knowledge graph |
| `stats [path]` | Show rich categorized statistics from analyzed graph |
| `graph [path]` | Export graph in various formats (JSON, YAML, Mermaid, DOT) |
| `query [path]` | Query graph relationships (consumers, producers, callers, etc.) |
| `find [what] [path]` | Preset queries (endpoints, guards, entities, topics, etc.) |
| `cypher [query]` | Execute raw Cypher queries against Neo4j |
| `flow [path]` | Generate architecture flow diagrams |
| `serve [path]` | Start web UI + REST API + MCP server |
| `bundle [path]` | Package graph + source into distributable ZIP |
| `cache [action]` | Manage analysis cache (status, clear, rebuild) |
| `plugins [action]` | List and inspect detectors |
| `version` | Show version info |

## Architecture

```
code-iq analyze /path/to/repo
        |
        v
+------------------+
| File Discovery   |  git ls-files + extension/filename mapping (35+ languages)
+--------+---------+
         |
         v
+------------------+
| Parsing Layer    |  JavaParser AST (Java) + ANTLR (TS/Py/Go/C#/Rust/C++) + regex
+--------+---------+
         |
         v
+------------------+
| 106 Detectors    |  Spring-managed beans, virtual thread parallelism
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
| Graph Builder    |  Buffered flush: nodes first, then edges (determinism)
+--------+---------+
         |
         v
+------------------+
| Neo4j Embedded   |  Full Cypher support, embedded in process, no server needed
+------------------+
         |
         v
+------------------+
| Output           |  REST API + MCP + Web UI + CLI + Flow Diagrams
+------------------+
```

## Server

Start a unified server with Explorer UI, REST API, and MCP server on a single port:

```bash
java -jar target/code-iq-*.jar serve /path/to/repo --port 8080
```

### Web UI (`/`)
Thymeleaf + HTMX progressive drill-down explorer:
- Browse by node kind (Endpoints, Entities, Classes, Guards, etc.)
- Click to drill into individual nodes with full detail modals
- Client-side search filtering
- Architecture flow diagrams

### REST API (`/api`)
23+ endpoints for programmatic access:
- `/api/stats` -- Graph statistics
- `/api/stats/detailed` -- Rich categorized statistics
- `/api/kinds` -- Node kinds with counts
- `/api/kinds/{kind}` -- Paginated nodes by kind
- `/api/nodes`, `/api/edges` -- Paginated queries with `?kind=&limit=&offset=`
- `/api/nodes/{id}/detail` -- Full node detail with edges
- `/api/nodes/{id}/neighbors` -- Neighbor traversal
- `/api/ego/{center}` -- Ego subgraph
- `/api/query/cycles`, `/shortest-path`, `/consumers/{id}`, `/producers/{id}`, `/callers/{id}`, `/dependencies/{id}`, `/dependents/{id}`
- `/api/triage/component`, `/impact/{id}` -- Agentic triage tools
- `/api/search?q=` -- Free-text graph search
- `/api/file?path=` -- Serve source files (path traversal protected)
- `/api/flow/{view}` -- Flow diagrams
- `POST /api/analyze` -- Trigger analysis
- OpenAPI docs at `/swagger-ui.html`

### MCP Server (`/mcp`)
21 tools via Spring AI streamable HTTP for AI-powered code triage:
- `get_stats`, `get_detailed_stats`, `query_nodes`, `query_edges`
- `get_node_neighbors`, `get_ego_graph`
- `find_cycles`, `find_shortest_path`
- `find_consumers`, `find_producers`, `find_callers`
- `find_dependencies`, `find_dependents`
- `generate_flow`, `analyze_codebase`, `run_cypher`
- `find_component_by_file`, `trace_impact`, `find_related_endpoints`
- `search_graph`, `read_file`

## Graph Model

### Node Types (31)
`module` `package` `class` `method` `endpoint` `entity` `repository` `query` `migration` `topic` `queue` `event` `interface` `abstract_class` `enum` `annotation_type` `protocol_message` `config_file` `config_key` `config_definition` `database_connection` `infra_resource` `azure_resource` `azure_function` `message_queue` `websocket_endpoint` `rmi_interface` `component` `guard` `middleware` `hook`

### Edge Types (26)
`depends_on` `imports` `extends` `implements` `calls` `injects` `exposes` `queries` `maps_to` `produces` `consumes` `publishes` `listens` `invokes_rmi` `exports_rmi` `reads_config` `migrates` `contains` `defines` `overrides` `connects_to` `triggers` `provisions` `sends_to` `receives_from` `protects` `renders`

## Maven Coordinates

```xml
<dependency>
    <groupId>io.github.randomcodespace.iq</groupId>
    <artifactId>code-iq</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Docker

```bash
# Build
docker build -t code-iq .

# Analyze a codebase
docker run -v /path/to/repo:/data code-iq analyze /data

# Start server
docker run -p 8080:8080 -v /path/to/repo:/data code-iq serve /data
```

The Docker image uses Eclipse Temurin JDK 25, ZGC garbage collector, and Spring AOT cache for fast startup.

## Benchmark Results

Benchmarked against the Python implementation (OSSCodeIQ on PyPI). 3 runs per project, all deterministic.

| Project | Files | Java Nodes | Java Edges | Java Time (analysis) | Python Time (wall) | Speedup |
|---------|-------|-----------|------------|---------------------|--------------------|---------|
| spring-boot | 10,524 | 27,987 | 39,776 | 47.8s avg | 56.8s | 1.2x |
| kafka | 6,919 | 62,671 | 120,376 | 63.5s avg | 96.8s | 1.5x |
| contoso-real-estate | 484 | 4,034 | 4,039 | 1.3s avg | 7.6s | 5.8x |

Java consistently finds more nodes (+2-8%) and edges (+20-39%) than the Python version due to deeper AST-based detection. Results are fully deterministic across all 3 runs per project.

## Development

```bash
# Prerequisites: Java 25+, Maven 3.9+
git clone https://github.com/RandomCodeSpace/code-iq.git
cd code-iq && git checkout java

# Build
mvn clean package

# Run tests
mvn test

# Analyze this repo
java -jar target/code-iq-*.jar analyze .

# Start dev server
java -jar target/code-iq-*.jar serve .
```

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 25 (virtual threads, pattern matching, records) |
| Framework | Spring Boot 4.0.5 |
| Graph DB | Neo4j Embedded 2026.02.3 (Community Edition) |
| Cache | Hazelcast 5.6.0 (distributed, K8s auto-discovery) |
| MCP | Spring AI 1.1.4 (streamable HTTP) |
| Java AST | JavaParser 3.28.0 |
| Multi-lang AST | ANTLR 4.13.2 (6 grammars) |
| CLI | Picocli 4.7.7 |
| Web UI | Thymeleaf + HTMX |
| Incremental cache | SQLite (via sqlite-jdbc) |
| Build | Maven + Spring Boot Plugin |
| Docker | Eclipse Temurin 25, ZGC, Spring AOT |

## License

MIT License. See [LICENSE](LICENSE) for details.

---

<p align="center">
  Built with intelligence. No AI required.
</p>
