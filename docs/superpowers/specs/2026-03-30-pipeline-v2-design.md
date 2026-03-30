# Pipeline V2: Config-First Smart Indexing + Accurate Infrastructure Topology

## Problem Statement

The current indexing pipeline has three fundamental issues:

1. **Scale**: Scans all files equally — 30K files takes forever and OOMs. No intelligent filtering.
2. **Accuracy**: Detectors find annotations/patterns but don't trace them to actual infrastructure. Edges are missing, especially on the infrastructure side (databases, MQ, caches).
3. **Data quality**: The UI shows nodes without connections. It groups by kind but doesn't show real service-to-infrastructure topology. An AppDynamics-style connected graph is the goal.

## Design Principles

- **Config files are the entry point, not code files.** Infrastructure topology lives in `application.yml`, `docker-compose.yml`, `pom.xml`, `.env`, K8s manifests. Scan config first, build the infrastructure skeleton, then trace code connections.
- **Filter before parsing, not after.** Use `grep`-level keyword pre-scan to skip files that can't contribute to the graph. Don't parse a 500-line utility class through JavaParser if it has no infrastructure keywords.
- **Emit edges at detection time.** Detectors should produce `SERVICE --QUERIES--> DATABASE` edges directly, not just isolated nodes that linkers try to connect later.
- **Partition and flush.** Process one module/directory at a time, flush to DB, release memory. Never hold the entire graph in heap.
- **Bulk writes.** Use Neo4j `UNWIND` for 10-100x faster graph construction.

## Architecture

```
Phase 1: Discovery + Config Scan (< 5s)
  ├── git ls-files / directory walk (with smart filtering)
  ├── Classify files: config vs source vs skip
  ├── Parse ALL config files (yaml, json, xml, properties, toml, .env, docker-compose, k8s, terraform, pom.xml, build.gradle)
  ├── Extract: DB URLs, MQ brokers, cache hosts, API base URLs, service names, ports, topics, queues
  └── Output: InfrastructureRegistry (all declared infrastructure endpoints)

Phase 2: Targeted Source Scan (< 30s for 30K files)
  ├── Pre-filter: grep source files for infrastructure keywords (DataSource, JdbcTemplate, KafkaTemplate, @Entity, HttpClient, RestTemplate, WebClient, etc.)
  ├── Parse ONLY files that match (typically 5-15% of source files)
  ├── Detectors emit edges directly: SERVICE --edge_kind--> INFRA_NODE
  ├── Cross-reference with InfrastructureRegistry from Phase 1
  └── Output: Nodes + Edges flushed per-module to H2

Phase 3: Enrichment (< 10s)
  ├── Load from H2 to Neo4j (bulk UNWIND writes)
  ├── Run linkers (scoped to affected nodes only)
  ├── Layer classification
  └── Service boundary detection
```

## Component Design

### 1. InfrastructureRegistry

A new component that holds all discovered infrastructure endpoints. Built from config files in Phase 1, consumed by detectors in Phase 2.

```java
public class InfrastructureRegistry {
    // Databases: jdbc:postgresql://host:5432/dbname -> DATABASE node
    Map<String, InfraEndpoint> databases;
    // Message brokers: bootstrap.servers=kafka:9092 -> BROKER node
    Map<String, InfraEndpoint> messageBrokers;
    // Topics: spring.kafka.consumer.topics=orders -> TOPIC node
    Map<String, InfraEndpoint> topics;
    // Caches: spring.redis.host=redis:6379 -> CACHE node
    Map<String, InfraEndpoint> caches;
    // External APIs: api.user-service.url=http://... -> EXTERNAL_SERVICE node
    Map<String, InfraEndpoint> externalApis;
    // Service identity: spring.application.name=order-service
    String serviceName;
}
```

**Sources parsed** (per framework):
- Spring: `application.yml`, `application.properties`, `application-*.yml`
- Django: `settings.py`, `DATABASES`, `CACHES`, `CHANNEL_LAYERS`
- Node/NestJS: `.env`, `config/*.ts`, `ormconfig.json`
- Go: `.env`, `config.yaml`, `docker-compose.yml`
- Generic: `docker-compose.yml` (services, depends_on, networks), `pom.xml`/`build.gradle` (dependencies imply tech stack), K8s manifests (services, ConfigMaps, Secrets)

### 2. Smart File Filter (Pre-scan)

Before parsing any source file, do a fast byte-level keyword scan (not regex, just `contains`) to check if the file could contribute infrastructure edges.

```java
public class InfraKeywordFilter {
    // Language-specific keyword sets
    static final Set<String> JAVA_INFRA_KEYWORDS = Set.of(
        "DataSource", "JdbcTemplate", "JpaRepository", "EntityManager",
        "KafkaTemplate", "KafkaListener", "JmsTemplate", "RabbitTemplate",
        "RedisTemplate", "CacheManager", "RestTemplate", "WebClient",
        "FeignClient", "HttpClient", "@Entity", "@Table", "@Repository",
        "@Produces", "@Consumes", "ConnectionFactory", "SessionFactory"
    );
    static final Set<String> PYTHON_INFRA_KEYWORDS = Set.of(
        "sqlalchemy", "psycopg", "pymongo", "redis", "celery",
        "kafka", "pika", "boto3", "httpx", "requests.get",
        "FastAPI", "Django", "DATABASES", "create_engine"
    );
    static final Set<String> TYPESCRIPT_INFRA_KEYWORDS = Set.of(
        "TypeORM", "Prisma", "Sequelize", "Mongoose",
        "kafkajs", "amqplib", "ioredis", "bull",
        "HttpService", "fetch(", "axios"
    );
    // ... per language

    boolean hasInfraKeywords(byte[] content, String language);
}
```

**How it works:** Read the raw file bytes (no parsing), scan for keyword substrings. If none found, skip the file entirely. This is O(n) in file size with no parsing overhead.

**Expected result:** For a 30K-file codebase, typically 1,500-4,000 files pass the keyword filter. The rest are utilities, tests, models, DTOs — no infrastructure connections.

### 3. Infrastructure-Aware Detectors

Current detectors emit nodes. New detectors emit **nodes + edges to infrastructure**.

**Example — Java Database Detector (rewritten):**

Current behavior:
```
Finds @Entity → emits ENTITY node
Finds @Repository → emits CLASS node
No connection between them. No connection to actual database.
```

New behavior:
```
Finds @Entity + @Table(name="users") → emits ENTITY node "users"
Finds @Repository<User, Long> → emits edge: SERVICE --QUERIES--> ENTITY:users
Finds spring.datasource.url in registry → emits edge: SERVICE --CONNECTS_TO--> DATABASE:postgresql
Result: order-service --QUERIES--> users --STORED_IN--> postgresql:orders-db
```

**Key change:** Detectors receive the `InfrastructureRegistry` as context. They match code patterns against registered infrastructure to create real edges.

### 4. Partitioned Analysis

Instead of loading all files into memory, process by module:

```
for each module directory:
    1. Collect files in this module
    2. Run detectors (parallel, virtual threads)
    3. Flush nodes + edges to H2
    4. Release memory (clear batch)
```

**Module detection:** Use directory structure heuristics:
- Java: directories containing `pom.xml` or `build.gradle`
- Python: directories containing `__init__.py` or `setup.py`
- Node: directories containing `package.json`
- Go: directories containing `go.mod`
- Fallback: top-level directories under the repo root

### 5. Bulk Neo4j Writes

Replace individual Cypher statements with UNWIND batch operations:

```cypher
// Current (slow): one statement per node
CREATE (n:CodeNode {id: $id, kind: $kind, ...})

// New (10-100x faster): batch of 1000 nodes
UNWIND $nodes AS node
CREATE (n:CodeNode)
SET n = node

// Edges in bulk
UNWIND $edges AS edge
MATCH (s:CodeNode {id: edge.sourceId}), (t:CodeNode {id: edge.targetId})
CREATE (s)-[r:RELATES_TO {kind: edge.kind, id: edge.id}]->(t)
```

### 6. Query Layer (Serving)

The serving layer (already partially fixed today) must:
- **Never call `findAll()`** for anything except flow/topology views
- Use **Cypher aggregation** for stats (counts, groupings)
- Use **paginated queries** for node/edge listings
- Bypass SDN for all reads (use embedded Neo4j API to avoid relationship hydration OOM)

### 7. UI Data Contract

The API must return data in a format that enables the AppDynamics-style topology view:

```json
{
  "services": [
    {"id": "order-service", "kind": "SERVICE", "layer": "backend"}
  ],
  "infrastructure": [
    {"id": "postgresql:orders-db", "kind": "DATABASE", "type": "postgresql"},
    {"id": "kafka:orders-topic", "kind": "TOPIC", "type": "kafka"}
  ],
  "connections": [
    {"source": "order-service", "target": "postgresql:orders-db", "kind": "QUERIES"},
    {"source": "order-service", "target": "kafka:orders-topic", "kind": "PRODUCES"}
  ]
}
```

## What Changes vs Current Architecture

| Component | Current | New |
|-----------|---------|-----|
| File discovery | Scan all files, filter by extension | Config first, then keyword-filtered source files |
| Detectors | Emit nodes only, hope linkers connect | Emit nodes + edges, use InfrastructureRegistry |
| Config parsing | Config files treated same as code | Config files parsed first, populate InfrastructureRegistry |
| Memory model | All nodes/edges in heap, flush at end | Per-module partition, flush after each module |
| Neo4j writes | Individual Cypher statements | Bulk UNWIND batches |
| Linkers | Run on entire graph | Scoped to affected nodes |
| Query layer | findAll() + in-memory processing | Cypher aggregation + paginated queries (done today) |

## What Stays the Same

- Detector interface (`Detector.detect(DetectorContext)`)
- Node/Edge model (`CodeNode`, `CodeEdge`, `NodeKind`, `EdgeKind`)
- H2 incremental cache (content-hash based)
- Neo4j embedded as the graph database
- Spring Boot + Picocli CLI framework
- MCP server + REST API + Web UI
- Determinism guarantees (sorted collections, indexed slots)

## Implementation Order

1. **InfrastructureRegistry + Config Parsers** — new component, no existing code changes
2. **Smart File Filter** — new component, plugs into FileDiscovery
3. **Partitioned Analyzer** — refactor Analyzer.run() to process per-module
4. **Bulk Neo4j writes** — refactor EnrichCommand to use UNWIND
5. **Infrastructure-Aware Detectors** — update existing detectors to emit edges + use registry
6. **Topology API endpoint** — new `/api/topology` returning the AppDynamics-style format
7. **UI topology view** — Cytoscape graph with service + infrastructure nodes

## Success Criteria

- Index testDir (13 repos, 30K files, 5.2GB) without OOM on a 4-core / 4GB machine
- Complete indexing in under 60 seconds (Phase 1+2)
- Produce connected topology: every service has at least database or messaging edges
- Dashboard shows accurate file counts, language breakdown, framework detection
- Topology view shows AppDynamics-style connected graph (services → databases → topics → caches)
- MCP `get_topology` returns complete service map without code context
