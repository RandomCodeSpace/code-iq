# Fix OOM in Query Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate all `findAll()` / full-graph-into-heap calls from the serving path so the app queries Neo4j/H2 on disk and never materializes the entire graph in JVM memory.

**Architecture:** Replace every `graphStore.findAll()` and `cache.loadAllNodes()`/`cache.loadAllEdges()` call in the serving path with Cypher aggregation queries that return only the data needed. Three layers to fix: `GraphRepository` (add Cypher queries), `QueryService` (use them), `GraphController` + `McpTools` (remove in-memory caches).

**Tech Stack:** Spring Data Neo4j `@Query` annotations, Cypher aggregation (`count()`, `collect()`), Neo4j relationship property queries.

---

### Task 1: Add Cypher Aggregation Queries to GraphRepository

**Files:**
- Modify: `src/main/java/io/github/randomcodespace/iq/graph/GraphRepository.java`

These queries push counting/grouping into Neo4j so Java never sees the full node set.

- [ ] **Step 1: Add edge count query**

Add to `GraphRepository.java` after the existing `countByKind` method:

```java
@Query("MATCH (n:CodeNode)-[r:RELATES_TO]->(m:CodeNode) RETURN count(r)")
long countEdges();
```

- [ ] **Step 2: Add node-kind counts query**

```java
@Query("MATCH (n:CodeNode) RETURN n.kind AS kind, count(n) AS cnt")
List<Map<String, Object>> countNodesByKind();
```

- [ ] **Step 3: Add node-layer counts query**

```java
@Query("MATCH (n:CodeNode) WHERE n.layer IS NOT NULL RETURN n.layer AS layer, count(n) AS cnt")
List<Map<String, Object>> countNodesByLayer();
```

- [ ] **Step 4: Add paginated edge query**

```java
@Query("MATCH (s:CodeNode)-[r:RELATES_TO]->(t:CodeNode) RETURN r.id AS id, r.kind AS kind, r.sourceId AS sourceId, t.id AS targetId SKIP $offset LIMIT $limit")
List<Map<String, Object>> findEdgesPaginated(int offset, int limit);
```

- [ ] **Step 5: Add filtered paginated edge query**

```java
@Query("MATCH (s:CodeNode)-[r:RELATES_TO]->(t:CodeNode) WHERE r.kind = $kind RETURN r.id AS id, r.kind AS kind, r.sourceId AS sourceId, t.id AS targetId SKIP $offset LIMIT $limit")
List<Map<String, Object>> findEdgesByKindPaginated(String kind, int offset, int limit);
```

- [ ] **Step 6: Add total edge count by kind**

```java
@Query("MATCH (s:CodeNode)-[r:RELATES_TO]->(t:CodeNode) WHERE r.kind = $kind RETURN count(r)")
long countEdgesByKind(String kind);
```

- [ ] **Step 7: Add dead code query (nodes with no incoming RELATES_TO edges)**

```java
@Query("MATCH (n:CodeNode) WHERE n.kind IN $kinds AND NOT EXISTS { MATCH (m)-[:RELATES_TO]->(n) } RETURN n SKIP $offset LIMIT $limit")
List<CodeNode> findNodesWithoutIncoming(List<String> kinds, int offset, int limit);
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/randomcodespace/iq/graph/GraphRepository.java
git commit -m "feat: add Cypher aggregation queries to GraphRepository for OOM fix"
```

---

### Task 2: Add Facade Methods to GraphStore

**Files:**
- Modify: `src/main/java/io/github/randomcodespace/iq/graph/GraphStore.java`

GraphStore is the facade — all graph access goes through it, never GraphRepository directly.

- [ ] **Step 1: Add aggregation facade methods**

Add the following methods after the existing `countByKind` method in `GraphStore.java`:

```java
public long countEdges() {
    return repository.countEdges();
}

public List<Map<String, Object>> countNodesByKind() {
    return repository.countNodesByKind();
}

public List<Map<String, Object>> countNodesByLayer() {
    return repository.countNodesByLayer();
}

public List<Map<String, Object>> findEdgesPaginated(int offset, int limit) {
    return repository.findEdgesPaginated(offset, limit);
}

public List<Map<String, Object>> findEdgesByKindPaginated(String kind, int offset, int limit) {
    return repository.findEdgesByKindPaginated(kind, offset, limit);
}

public long countEdgesByKind(String kind) {
    return repository.countEdgesByKind(kind);
}

public List<CodeNode> findNodesWithoutIncoming(List<String> kinds, int offset, int limit) {
    return repository.findNodesWithoutIncoming(kinds, offset, limit);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/github/randomcodespace/iq/graph/GraphStore.java
git commit -m "feat: add aggregation facade methods to GraphStore"
```

---

### Task 3: Rewrite QueryService to Use Cypher Aggregation

**Files:**
- Modify: `src/main/java/io/github/randomcodespace/iq/query/QueryService.java`

This is the core fix. Every `findAll()` call gets replaced.

- [ ] **Step 1: Rewrite `getStats()` — replace findAll() with count queries**

Replace the entire `getStats()` method:

```java
@Cacheable("graph-stats")
public Map<String, Object> getStats() {
    long nodeCount = graphStore.count();
    long edgeCount = graphStore.countEdges();

    Map<String, Long> nodesByKind = new LinkedHashMap<>();
    for (Map<String, Object> row : graphStore.countNodesByKind()) {
        nodesByKind.put((String) row.get("kind"), ((Number) row.get("cnt")).longValue());
    }

    Map<String, Long> nodesByLayer = new LinkedHashMap<>();
    for (Map<String, Object> row : graphStore.countNodesByLayer()) {
        nodesByLayer.put((String) row.get("layer"), ((Number) row.get("cnt")).longValue());
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("node_count", nodeCount);
    result.put("edge_count", edgeCount);
    result.put("nodes_by_kind", nodesByKind);
    result.put("nodes_by_layer", nodesByLayer);
    return result;
}
```

- [ ] **Step 2: Rewrite `listKinds()` — replace findAll() with count query**

Replace the entire `listKinds()` method:

```java
@Cacheable("kinds-list")
public Map<String, Object> listKinds() {
    List<Map<String, Object>> rawCounts = graphStore.countNodesByKind();

    long total = 0;
    List<Map<String, Object>> kinds = new ArrayList<>();
    // Sort by count descending
    rawCounts.stream()
            .sorted((a, b) -> Long.compare(
                    ((Number) b.get("cnt")).longValue(),
                    ((Number) a.get("cnt")).longValue()))
            .forEach(row -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", row.get("kind"));
                m.put("count", ((Number) row.get("cnt")).longValue());
                kinds.add(m);
            });
    long totalNodes = rawCounts.stream()
            .mapToLong(r -> ((Number) r.get("cnt")).longValue())
            .sum();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kinds", kinds);
    result.put("total", totalNodes);
    return result;
}
```

- [ ] **Step 3: Rewrite `listEdges()` — replace findAll() with paginated Cypher**

Replace the entire `listEdges()` method:

```java
public Map<String, Object> listEdges(String kind, int limit, int offset) {
    List<Map<String, Object>> rawEdges;
    long total;
    if (kind != null && !kind.isBlank()) {
        rawEdges = graphStore.findEdgesByKindPaginated(kind, offset, limit);
        total = graphStore.countEdgesByKind(kind);
    } else {
        rawEdges = graphStore.findEdgesPaginated(offset, limit);
        total = graphStore.countEdges();
    }

    List<Map<String, Object>> edges = rawEdges.stream().map(row -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("id"));
        m.put("kind", row.get("kind"));
        m.put("source", row.get("sourceId"));
        m.put("target", row.get("targetId"));
        return m;
    }).toList();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("edges", edges);
    result.put("count", edges.size());
    result.put("total", total);
    return result;
}
```

- [ ] **Step 4: Rewrite `findDeadCode()` — replace two findAll() calls with Cypher**

Replace the entire `findDeadCode()` method:

```java
@Cacheable(value = "dead-code", key = "#kind + ':' + #limit")
public Map<String, Object> findDeadCode(String kind, int limit) {
    List<String> kinds;
    if (kind != null && !kind.isBlank()) {
        kinds = List.of(kind);
    } else {
        kinds = List.of(
                NodeKind.CLASS.getValue(),
                NodeKind.METHOD.getValue(),
                NodeKind.INTERFACE.getValue());
    }

    List<CodeNode> deadNodes = graphStore.findNodesWithoutIncoming(kinds, 0, limit);

    List<Map<String, Object>> deadCode = deadNodes.stream()
            .map(n -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", n.getId());
                m.put("kind", n.getKind().getValue());
                m.put("label", n.getLabel());
                m.put("file", n.getFilePath());
                return m;
            })
            .toList();

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("dead_code", deadCode);
    result.put("count", deadCode.size());
    return result;
}
```

- [ ] **Step 5: Remove unused imports**

Remove these imports from `QueryService.java` since `findAll()` is no longer called:

```java
// Remove: import java.util.HashSet;
// Remove: import java.util.Set;
// Remove: import java.util.stream.Collectors;
```

Keep `Collectors` only if still used by other methods — check before removing.

- [ ] **Step 6: Remove the TODO comment on line 36**

Delete the comment block:
```java
// TODO: Replace findAll() with Cypher aggregation queries for node/edge counts
//       to avoid loading entire graph into memory. Requires Neo4j to be running.
```

- [ ] **Step 7: Run tests**

Run: `mvn test -Dtest=QueryServiceTest -pl . -Dsurefire.useFile=false`
Expected: all tests pass (or update tests if they mock `findAll()`)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/randomcodespace/iq/query/QueryService.java
git commit -m "fix: replace findAll() with Cypher aggregation in QueryService to prevent OOM"
```

---

### Task 4: Remove In-Memory Graph Cache from GraphController

**Files:**
- Modify: `src/main/java/io/github/randomcodespace/iq/api/GraphController.java`

The controller has `cachedNodes` / `cachedEdges` fields that hold the entire H2 graph in memory. When Neo4j is available (the expected serving path after `enrich`), these are not needed. When Neo4j is NOT available, the H2 fallback paths still load everything into memory — fix those to return 503 pointing the user to run `enrich` instead.

- [ ] **Step 1: Remove in-memory cache fields and methods**

Remove lines 52-53 (fields), 68-84 (`ensureCacheLoaded`), 86-92 (`invalidateCache`), 95-104 (`getEffectiveNodes`), 106-119 (`getEffectiveEdges`):

```java
// DELETE these fields:
private volatile List<CodeNode> cachedNodes;
private volatile List<CodeEdge> cachedEdges;

// DELETE these methods entirely:
// ensureCacheLoaded()
// invalidateCache()
// getEffectiveNodes()
// getEffectiveEdges()
```

- [ ] **Step 2: Simplify `getStats()` — remove H2 fallback**

Replace the `getStats()` method:

```java
@GetMapping("/stats")
public Map<String, Object> getStats() {
    requireQueryService();
    return queryService.getStats();
}
```

- [ ] **Step 3: Simplify `getDetailedStats()` — require Neo4j**

Replace the `getDetailedStats()` method. Since `StatsService.computeStats()` requires full node/edge lists, and we're eliminating full-graph loading, this endpoint should use Cypher-based stats when category is "all" or "graph", and return 503 for categories that require full node iteration until they too are migrated to Cypher:

```java
@GetMapping("/stats/detailed")
public Map<String, Object> getDetailedStats(
        @RequestParam(defaultValue = "all") String category) {
    requireQueryService();
    // For "all" and "graph", use the Cypher-backed getStats()
    if ("all".equalsIgnoreCase(category) || "graph".equalsIgnoreCase(category)) {
        return queryService.getStats();
    }
    throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
            "Detailed stats by category not yet supported via Neo4j. Use /api/stats instead.");
}
```

- [ ] **Step 4: Simplify `listKinds()` — remove H2 fallback**

Replace the `listKinds()` method:

```java
@GetMapping("/kinds")
public Map<String, Object> listKinds() {
    requireQueryService();
    return queryService.listKinds();
}
```

- [ ] **Step 5: Simplify `nodesByKind()` — remove H2 fallback**

Replace the `nodesByKind()` method:

```java
@GetMapping("/kinds/{kind}")
public Map<String, Object> nodesByKind(
        @PathVariable String kind,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset) {
    requireQueryService();
    return queryService.nodesByKind(kind, Math.min(limit, 1000), offset);
}
```

- [ ] **Step 6: Simplify `listNodes()` — remove H2 fallback**

Replace the `listNodes()` method:

```java
@GetMapping("/nodes")
public Map<String, Object> listNodes(
        @RequestParam(required = false) String kind,
        @RequestParam(defaultValue = "100") int limit,
        @RequestParam(defaultValue = "0") int offset) {
    requireQueryService();
    return queryService.listNodes(kind, Math.min(limit, 1000), offset);
}
```

- [ ] **Step 7: Simplify `findNode()` — use search query instead of findAll**

Replace:

```java
@GetMapping("/nodes/find")
public List<Map<String, Object>> findNode(@RequestParam String q) {
    requireQueryService();
    return queryService.searchGraph(q, 50);
}
```

- [ ] **Step 8: Simplify `nodeDetail()` — remove H2 fallback**

Replace:

```java
@GetMapping("/nodes/{nodeId}/detail")
public Map<String, Object> nodeDetail(@PathVariable String nodeId) {
    requireQueryService();
    Map<String, Object> result = queryService.nodeDetailWithEdges(nodeId);
    if (result == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Node not found: " + nodeId);
    }
    return result;
}
```

- [ ] **Step 9: Simplify `listEdges()` — remove H2 fallback**

Replace:

```java
@GetMapping("/edges")
public Map<String, Object> listEdges(
        @RequestParam(required = false) String kind,
        @RequestParam(defaultValue = "100") int limit,
        @RequestParam(defaultValue = "0") int offset) {
    requireQueryService();
    return queryService.listEdges(kind, Math.min(limit, 1000), offset);
}
```

- [ ] **Step 10: Simplify `findDeadCode()` — remove H2 fallback**

Replace:

```java
@GetMapping("/query/dead-code")
public ResponseEntity<?> findDeadCode(
        @RequestParam(required = false) String kind,
        @RequestParam(defaultValue = "100") int limit) {
    requireQueryService();
    return ResponseEntity.ok(queryService.findDeadCode(kind, Math.min(limit, 1000)));
}
```

- [ ] **Step 11: Simplify `searchGraph()` — remove H2 fallback**

Replace:

```java
@GetMapping("/search")
public List<Map<String, Object>> searchGraph(
        @RequestParam String q,
        @RequestParam(defaultValue = "50") int limit) {
    requireQueryService();
    return queryService.searchGraph(q, Math.min(limit, 1000));
}
```

- [ ] **Step 12: Remove `invalidateCache()` call from `triggerAnalysis()`**

In the `triggerAnalysis()` method, remove the `invalidateCache()` call (the method no longer exists):

```java
@PostMapping("/analyze")
public ResponseEntity<?> triggerAnalysis(
        @RequestParam(defaultValue = "false") boolean incremental) {
    if (!analysisRunning.compareAndSet(false, true)) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Analysis already in progress"));
    }
    try {
        AnalysisResult result = analyzer.run(Path.of(config.getRootPath()), null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "complete");
        response.put("total_files", result.totalFiles());
        response.put("files_analyzed", result.filesAnalyzed());
        response.put("node_count", result.nodeCount());
        response.put("edge_count", result.edgeCount());
        response.put("elapsed_ms", result.elapsed().toMillis());
        return ResponseEntity.ok(response);
    } finally {
        analysisRunning.set(false);
    }
}
```

- [ ] **Step 13: Clean up unused imports**

Remove imports that are no longer needed:

```java
// Remove if unused:
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import io.github.randomcodespace.iq.query.StatsService;
import io.github.randomcodespace.iq.query.TopologyService;
import java.util.HashSet;
import java.util.Set;
```

Remove `StatsService` and `TopologyService` from constructor injection if no longer referenced. Also remove `nodeToMap()` and `edgeToMap()` private methods if no longer called.

- [ ] **Step 14: Commit**

```bash
git add src/main/java/io/github/randomcodespace/iq/api/GraphController.java
git commit -m "fix: remove in-memory graph cache from GraphController, require Neo4j for serving"
```

---

### Task 5: Remove In-Memory Graph Cache from McpTools

**Files:**
- Modify: `src/main/java/io/github/randomcodespace/iq/mcp/McpTools.java`

Same pattern as GraphController — McpTools has `cachedNodes`/`cachedEdges` that hold entire graph in heap.

- [ ] **Step 1: Remove cache fields and methods**

Remove these fields:
```java
private volatile List<CodeNode> cachedNodes;
private volatile List<CodeEdge> cachedEdges;
```

Remove these methods:
```java
// ensureCacheLoaded()
// invalidateCache()
// getCachedData()
// The CacheData record
```

- [ ] **Step 2: Rewrite `getDetailedStats()` tool to use QueryService**

Replace the `getDetailedStats()` method:

```java
@Tool(name = "get_detailed_stats", description = "Get rich categorized statistics: frameworks, infra, connections, auth, architecture. Category: all, graph, languages, frameworks, infra, connections, auth, architecture.")
public String getDetailedStats(
        @ToolParam(description = "Category filter (default: all)", required = false) String category) {
    try {
        return toJson(queryService.getStats());
    } catch (Exception e) {
        return toJson(Map.of("error", e.getMessage()));
    }
}
```

- [ ] **Step 3: Update any other MCP tool methods that use `getCachedData()`**

Search for all references to `getCachedData()`, `cachedNodes`, or `cachedEdges` in McpTools.java and replace them with `queryService` calls. Each one that loads the full graph should use a paginated or aggregation query instead.

- [ ] **Step 4: Clean up unused imports**

Remove:
```java
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
// and any others no longer referenced
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/randomcodespace/iq/mcp/McpTools.java
git commit -m "fix: remove in-memory graph cache from McpTools, delegate to QueryService"
```

---

### Task 6: Add JVM Heap Configuration

**Files:**
- Modify: `Dockerfile`

- [ ] **Step 1: Add `-Xmx` to Dockerfile ENTRYPOINT**

Replace the ENTRYPOINT line:

```dockerfile
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-XX:+UseZGC", "-Xmx2g", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
```

- [ ] **Step 2: Commit**

```bash
git add Dockerfile
git commit -m "fix: add -Xmx2g heap limit to Dockerfile to prevent unbounded memory growth"
```

---

### Task 7: Build and Verify

**Files:** None (verification only)

- [ ] **Step 1: Compile the project**

Run: `mvn clean compile -DskipTests`
Expected: BUILD SUCCESS with no compilation errors

- [ ] **Step 2: Run all tests**

Run: `mvn test -Dsurefire.useFile=false`
Expected: All tests pass. Fix any test failures caused by removed `findAll()` calls or changed constructor signatures.

- [ ] **Step 3: Fix test failures**

If tests fail because they mock `findAll()` or use `cachedNodes`, update them to mock the new aggregation methods instead. Typical changes:
- `QueryServiceTest`: mock `countNodesByKind()`, `countEdges()`, `countNodesByLayer()` instead of `findAll()`
- `GraphControllerTest`: remove H2 cache setup, mock `queryService` methods
- `McpToolsTest`: remove `getCachedData()` setup, mock `queryService` methods

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "fix: update tests for OOM fix — use aggregation queries instead of findAll()"
```
