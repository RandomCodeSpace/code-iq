package io.github.randomcodespace.iq.graph;

import io.github.randomcodespace.iq.flow.FlowDataSource;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Facade service over the Neo4j graph backend.
 * <p>
 * Read queries use the embedded Neo4j API directly to avoid SDN's recursive
 * relationship hydration (CodeNode has @Relationship which causes SDN to load
 * the entire graph into heap). Write operations still use SDN repository.
 */
@Service
@ConditionalOnBean(GraphRepository.class)
public class GraphStore implements FlowDataSource {

    private static final Logger log = LoggerFactory.getLogger(GraphStore.class);

    private final GraphRepository repository;
    private final GraphDatabaseService graphDb;

    public GraphStore(GraphRepository repository, GraphDatabaseService graphDb) {
        this.repository = repository;
        this.graphDb = graphDb;
    }

    // --- Write operations (SDN) ---

    public CodeNode save(CodeNode node) {
        return repository.save(node);
    }

    public List<CodeNode> saveAll(Iterable<CodeNode> nodes) {
        return repository.saveAll(nodes);
    }

    public void deleteAll() {
        repository.deleteAll();
    }

    public void deleteById(String id) {
        repository.deleteById(id);
    }

    /**
     * Bulk save nodes and edges using UNWIND for batch Cypher inserts.
     * Creates an index on CodeNode.id for fast MATCH during edge creation.
     * Logs progress every 10K items for visibility on large graphs.
     */
    public void bulkSave(List<CodeNode> nodes) {
        if (nodes.isEmpty()) return;
        long start = System.currentTimeMillis();

        // 1. Clear existing data in batches to avoid memory pool limit
        log.info("Neo4j: clearing existing graph...");
        int deleted;
        do {
            try (Transaction tx = graphDb.beginTx()) {
                var result = tx.execute(
                        "MATCH (n) WITH n LIMIT 5000 DETACH DELETE n RETURN count(*) AS cnt");
                deleted = result.hasNext() ? ((Number) result.next().get("cnt")).intValue() : 0;
                tx.commit();
            }
        } while (deleted > 0);

        // 2. Create index on id property for fast MATCH during edge creation
        try (Transaction tx = graphDb.beginTx()) {
            tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.id)");
            tx.commit();
        }

        // 3. Save nodes using UNWIND for batch inserts
        int batchSize = 2000;
        int totalNodes = nodes.size();
        log.info("Neo4j: persisting {} nodes...", totalNodes);

        for (int i = 0; i < totalNodes; i += batchSize) {
            List<CodeNode> batch = nodes.subList(i, Math.min(i + batchSize, totalNodes));
            List<Map<String, Object>> batchProps = new ArrayList<>(batch.size());
            for (CodeNode node : batch) {
                batchProps.add(nodeToProps(node));
            }
            try (Transaction tx = graphDb.beginTx()) {
                tx.execute("UNWIND $batch AS props CREATE (n:CodeNode) SET n = props",
                        Map.of("batch", batchProps));
                tx.commit();
            }
            int done = Math.min(i + batchSize, totalNodes);
            if (done % 10000 < batchSize || done == totalNodes) {
                log.info("  nodes: {}/{} ({}%)", done, totalNodes, 100 * done / totalNodes);
            }
        }

        // 4. Build set of all saved node IDs for edge validation
        Set<String> savedNodeIds = new HashSet<>(totalNodes);
        for (CodeNode node : nodes) {
            savedNodeIds.add(node.getId());
        }

        // 5. Save edges using UNWIND for batch inserts
        List<CodeEdge> allEdges = nodes.stream()
                .flatMap(n -> n.getEdges().stream())
                .toList();
        int totalEdges = allEdges.size();
        log.info("Neo4j: persisting {} edges...", totalEdges);

        int created = 0;
        int skipped = 0;
        for (int i = 0; i < totalEdges; i += batchSize) {
            List<CodeEdge> batch = allEdges.subList(i, Math.min(i + batchSize, totalEdges));
            List<Map<String, Object>> edgeBatch = new ArrayList<>(batch.size());
            for (CodeEdge edge : batch) {
                String sourceId = edge.getSourceId();
                String targetId = edge.getTarget() != null ? edge.getTarget().getId() : null;
                if (targetId == null || sourceId == null
                        || !savedNodeIds.contains(sourceId) || !savedNodeIds.contains(targetId)) {
                    skipped++;
                    continue;
                }
                edgeBatch.add(Map.of(
                        "sourceId", sourceId,
                        "targetId", targetId,
                        "edgeId", edge.getId(),
                        "kind", edge.getKind().getValue()
                ));
                created++;
            }
            if (!edgeBatch.isEmpty()) {
                try (Transaction tx = graphDb.beginTx()) {
                    tx.execute("""
                            UNWIND $batch AS e
                            MATCH (s:CodeNode {id: e.sourceId}), (t:CodeNode {id: e.targetId})
                            CREATE (s)-[:RELATES_TO {id: e.edgeId, kind: e.kind, sourceId: e.sourceId}]->(t)
                            """, Map.of("batch", edgeBatch));
                    tx.commit();
                }
            }
            int done = Math.min(i + batchSize, totalEdges);
            if (done % 10000 < batchSize || done == totalEdges) {
                log.info("  edges: {}/{} ({}%)", done, totalEdges, 100 * done / totalEdges);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Neo4j: bulk save complete — {} nodes, {} edges ({} skipped) in {}s",
                totalNodes, created, skipped, elapsed / 1000);
    }

    /** Convert a CodeNode to a flat property map for Cypher SET. */
    private Map<String, Object> nodeToProps(CodeNode node) {
        Map<String, Object> props = new HashMap<>();
        props.put("id", node.getId());
        props.put("kind", node.getKind().getValue());
        props.put("label", node.getLabel());
        if (node.getFqn() != null) props.put("fqn", node.getFqn());
        if (node.getModule() != null) props.put("module", node.getModule());
        if (node.getFilePath() != null) props.put("filePath", node.getFilePath());
        if (node.getLineStart() != null) props.put("lineStart", node.getLineStart());
        if (node.getLineEnd() != null) props.put("lineEnd", node.getLineEnd());
        if (node.getLayer() != null) props.put("layer", node.getLayer());
        if (node.getAnnotations() != null && !node.getAnnotations().isEmpty()) {
            props.put("annotations", String.join(",", node.getAnnotations()));
        }
        if (node.getProperties() != null) {
            for (var entry : node.getProperties().entrySet()) {
                if (entry.getValue() != null) {
                    props.put("prop_" + entry.getKey(), entry.getValue().toString());
                }
            }
        }
        return props;
    }

    // --- Read operations (embedded API, no relationship hydration) ---

    public Optional<CodeNode> findById(String id) {
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode {id: $id}) RETURN n", Map.of("id", id));
            if (result.hasNext()) {
                var neo4jNode = (org.neo4j.graphdb.Node) result.next().get("n");
                CodeNode node = nodeFromNeo4j(neo4jNode);
                // Hydrate outgoing edges for this single node
                hydrateEdgesForNode(tx, node);
                return Optional.of(node);
            }
            return Optional.empty();
        }
    }

    /**
     * Load all nodes WITH their edges attached. Used by FlowDataSource and
     * TopologyService which need the full graph with relationships.
     * <p>
     * Unlike other read methods, this hydrates edges because flow/topology
     * views iterate node.getEdges() to build diagrams.
     */
    public List<CodeNode> findAll() {
        List<CodeNode> nodes = queryNodes("MATCH (n:CodeNode) RETURN n", Map.of());
        hydrateEdges(nodes);
        return nodes;
    }

    public List<CodeNode> findByKind(NodeKind kind) {
        return queryNodes("MATCH (n:CodeNode) WHERE n.kind = $kind RETURN n",
                Map.of("kind", kind.getValue()));
    }

    public List<CodeNode> findByLayer(String layer) {
        return queryNodes("MATCH (n:CodeNode) WHERE n.layer = $layer RETURN n",
                Map.of("layer", layer));
    }

    public List<CodeNode> findByModule(String module) {
        return queryNodes("MATCH (n:CodeNode) WHERE n.module = $module RETURN n",
                Map.of("module", module));
    }

    public List<CodeNode> findByFilePath(String filePath) {
        return queryNodes("MATCH (n:CodeNode) WHERE n.filePath = $filePath RETURN n",
                Map.of("filePath", filePath));
    }

    public List<CodeNode> search(String text) {
        return queryNodes(
                "MATCH (n:CodeNode) WHERE n.label CONTAINS $text OR n.fqn CONTAINS $text RETURN n",
                Map.of("text", text));
    }

    public List<CodeNode> search(String text, int limit) {
        return queryNodes(
                "MATCH (n:CodeNode) WHERE toLower(n.label) CONTAINS toLower($text) "
                        + "OR toLower(n.fqn) CONTAINS toLower($text) RETURN n LIMIT $limit",
                Map.of("text", text, "limit", limit));
    }

    public List<CodeNode> findNeighbors(String nodeId) {
        return queryNodes(
                "MATCH (n:CodeNode)-[r]-(m:CodeNode) WHERE n.id = $nodeId RETURN m",
                Map.of("nodeId", nodeId));
    }

    public List<CodeNode> findOutgoingNeighbors(String nodeId) {
        return queryNodes(
                "MATCH (n:CodeNode)-[r]->(m:CodeNode) WHERE n.id = $nodeId RETURN m",
                Map.of("nodeId", nodeId));
    }

    public List<CodeNode> findIncomingNeighbors(String nodeId) {
        return queryNodes(
                "MATCH (n:CodeNode)<-[r]-(m:CodeNode) WHERE n.id = $nodeId RETURN m",
                Map.of("nodeId", nodeId));
    }

    public long count() {
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute("MATCH (n:CodeNode) RETURN count(n) AS cnt");
            if (result.hasNext()) {
                return ((Number) result.next().get("cnt")).longValue();
            }
            return 0;
        }
    }

    // --- Graph traversal queries ---

    public List<String> findShortestPath(String source, String target) {
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH p = shortestPath((a:CodeNode {id: $source})-[*..20]-(b:CodeNode {id: $target})) "
                            + "RETURN [n IN nodes(p) | n.id] AS ids",
                    Map.of("source", source, "target", target));
            if (result.hasNext()) {
                @SuppressWarnings("unchecked")
                List<String> ids = (List<String>) result.next().get("ids");
                return ids;
            }
            return List.of();
        }
    }

    public List<CodeNode> findEgoGraph(String center, int radius) {
        return queryNodes(
                "MATCH (a:CodeNode {id: $center})-[*1..$radius]-(b:CodeNode) RETURN DISTINCT b",
                Map.of("center", center, "radius", radius));
    }

    public List<CodeNode> traceImpact(String nodeId, int depth) {
        return queryNodes(
                "MATCH (a:CodeNode {id: $nodeId})-[:RELATES_TO*1..$depth]->(b:CodeNode) RETURN DISTINCT b",
                Map.of("nodeId", nodeId, "depth", depth));
    }

    public List<List<String>> findCycles(int limit) {
        List<List<String>> cycles = new ArrayList<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH p = (a:CodeNode)-[:RELATES_TO*2..10]->(a) "
                            + "RETURN [n IN nodes(p) | n.id] AS ids LIMIT $limit",
                    Map.of("limit", limit));
            while (result.hasNext()) {
                @SuppressWarnings("unchecked")
                List<String> ids = (List<String>) result.next().get("ids");
                cycles.add(ids);
            }
        }
        return cycles;
    }

    public List<CodeNode> findConsumers(String targetId) {
        return queryNodes(
                "MATCH (n:CodeNode)<-[r:RELATES_TO]-(m:CodeNode) "
                        + "WHERE n.id = $targetId AND r.kind IN ['consumes', 'listens'] RETURN m",
                Map.of("targetId", targetId));
    }

    public List<CodeNode> findProducers(String targetId) {
        return queryNodes(
                "MATCH (n:CodeNode)<-[r:RELATES_TO]-(m:CodeNode) "
                        + "WHERE n.id = $targetId AND r.kind IN ['produces', 'publishes'] RETURN m",
                Map.of("targetId", targetId));
    }

    public List<CodeNode> findCallers(String targetId) {
        return queryNodes(
                "MATCH (n:CodeNode)<-[r:RELATES_TO]-(m:CodeNode) "
                        + "WHERE n.id = $targetId AND r.kind = 'calls' RETURN m",
                Map.of("targetId", targetId));
    }

    public List<CodeNode> findDependencies(String moduleId) {
        return queryNodes(
                "MATCH (n:CodeNode)-[r:RELATES_TO]->(m:CodeNode) "
                        + "WHERE n.id = $moduleId AND r.kind = 'depends_on' RETURN m",
                Map.of("moduleId", moduleId));
    }

    public List<CodeNode> findDependents(String moduleId) {
        return queryNodes(
                "MATCH (n:CodeNode)<-[r:RELATES_TO]-(m:CodeNode) "
                        + "WHERE n.id = $moduleId AND r.kind = 'depends_on' RETURN m",
                Map.of("moduleId", moduleId));
    }

    public List<CodeNode> findByKindPaginated(String kind, int offset, int limit) {
        return queryNodes(
                "MATCH (n:CodeNode) WHERE n.kind = $kind RETURN n SKIP $offset LIMIT $limit",
                Map.of("kind", kind, "offset", offset, "limit", limit));
    }

    public List<CodeNode> findAllPaginated(int offset, int limit) {
        return queryNodes(
                "MATCH (n:CodeNode) RETURN n SKIP $offset LIMIT $limit",
                Map.of("offset", offset, "limit", limit));
    }

    public long countByKind(String kind) {
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.kind = $kind RETURN count(n) AS cnt",
                    Map.of("kind", kind));
            if (result.hasNext()) {
                return ((Number) result.next().get("cnt")).longValue();
            }
            return 0;
        }
    }

    // --- Aggregation queries ---

    public long countEdges() {
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute("MATCH ()-[r:RELATES_TO]->() RETURN count(r) AS cnt");
            if (result.hasNext()) {
                return ((Number) result.next().get("cnt")).longValue();
            }
            return 0;
        }
    }

    public long countDistinctFiles() {
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.filePath IS NOT NULL "
                            + "RETURN count(DISTINCT n.filePath) AS cnt");
            if (result.hasNext()) {
                return ((Number) result.next().get("cnt")).longValue();
            }
            return 0;
        }
    }

    /**
     * Count nodes grouped by file extension (language proxy).
     * Extracts extension from filePath using string manipulation in Cypher.
     */
    public List<Map<String, Object>> countByFileExtension() {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.filePath IS NOT NULL AND n.filePath CONTAINS '.' "
                            + "WITH split(n.filePath, '.')[-1] AS ext "
                            + "RETURN ext, count(*) AS cnt ORDER BY cnt DESC");
            while (result.hasNext()) {
                var row = result.next();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ext", row.get("ext"));
                m.put("cnt", ((Number) row.get("cnt")).longValue());
                rows.add(m);
            }
        }
        return rows;
    }

    public List<Map<String, Object>> countNodesByKind() {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute("MATCH (n:CodeNode) RETURN n.kind AS kind, count(n) AS cnt");
            while (result.hasNext()) {
                var row = result.next();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", row.get("kind"));
                m.put("cnt", ((Number) row.get("cnt")).longValue());
                rows.add(m);
            }
        }
        return rows;
    }

    public List<Map<String, Object>> countNodesByLayer() {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.layer IS NOT NULL RETURN n.layer AS layer, count(n) AS cnt");
            while (result.hasNext()) {
                var row = result.next();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("layer", row.get("layer"));
                m.put("cnt", ((Number) row.get("cnt")).longValue());
                rows.add(m);
            }
        }
        return rows;
    }

    public List<Map<String, Object>> findEdgesPaginated(int offset, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (s:CodeNode)-[r:RELATES_TO]->(t:CodeNode) "
                            + "RETURN r.id AS id, r.kind AS kind, s.id AS sourceId, t.id AS targetId "
                            + "SKIP $offset LIMIT $limit",
                    Map.of("offset", offset, "limit", limit));
            while (result.hasNext()) {
                var row = result.next();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", row.get("id"));
                m.put("kind", row.get("kind"));
                m.put("sourceId", row.get("sourceId"));
                m.put("targetId", row.get("targetId"));
                rows.add(m);
            }
        }
        return rows;
    }

    public List<Map<String, Object>> findEdgesByKindPaginated(String kind, int offset, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (s:CodeNode)-[r:RELATES_TO]->(t:CodeNode) WHERE r.kind = $kind "
                            + "RETURN r.id AS id, r.kind AS kind, s.id AS sourceId, t.id AS targetId "
                            + "SKIP $offset LIMIT $limit",
                    Map.of("kind", kind, "offset", offset, "limit", limit));
            while (result.hasNext()) {
                var row = result.next();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", row.get("id"));
                m.put("kind", row.get("kind"));
                m.put("sourceId", row.get("sourceId"));
                m.put("targetId", row.get("targetId"));
                rows.add(m);
            }
        }
        return rows;
    }

    public long countEdgesByKind(String kind) {
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH ()-[r:RELATES_TO]->() WHERE r.kind = $kind RETURN count(r) AS cnt",
                    Map.of("kind", kind));
            if (result.hasNext()) {
                return ((Number) result.next().get("cnt")).longValue();
            }
            return 0;
        }
    }

    /**
     * Find nodes that have no incoming semantic edges.
     * <p>
     * Structural edges (contains, defines, configures, documents) are excluded because
     * every node typically has at least one incoming structural edge from its parent
     * module/config_file. Dead code detection should only consider semantic edges like
     * calls, imports, depends_on, uses, extends, implements.
     *
     * @param kinds      node kinds to consider (e.g. class, method, interface)
     * @param semanticEdgeKinds edge kinds that count as "usage" — nodes without any
     *                          incoming edge of these kinds are considered dead
     * @param excludeNodeKinds  node kinds to exclude (e.g. endpoints, entry points)
     * @param offset     pagination offset
     * @param limit      pagination limit
     */
    public List<CodeNode> findNodesWithoutIncomingSemantic(List<String> kinds,
                                                           List<String> semanticEdgeKinds,
                                                           List<String> excludeNodeKinds,
                                                           int offset, int limit) {
        return queryNodes(
                "MATCH (n:CodeNode) WHERE n.kind IN $kinds "
                        + "AND NOT n.kind IN $excludeKinds "
                        + "AND NOT EXISTS { MATCH (m)-[r:RELATES_TO]->(n) WHERE r.kind IN $semanticKinds } "
                        + "RETURN n SKIP $offset LIMIT $limit",
                Map.of("kinds", kinds,
                        "semanticKinds", semanticEdgeKinds,
                        "excludeKinds", excludeNodeKinds,
                        "offset", offset,
                        "limit", limit));
    }

    /**
     * @deprecated Use {@link #findNodesWithoutIncomingSemantic} instead. This method
     * counts ALL incoming edges including structural ones, producing false negatives.
     */
    @Deprecated
    public List<CodeNode> findNodesWithoutIncoming(List<String> kinds, int offset, int limit) {
        return queryNodes(
                "MATCH (n:CodeNode) WHERE n.kind IN $kinds "
                        + "AND NOT EXISTS { MATCH (m)-[:RELATES_TO]->(n) } "
                        + "RETURN n SKIP $offset LIMIT $limit",
                Map.of("kinds", kinds, "offset", offset, "limit", limit));
    }

    // --- Topology queries ---

    /**
     * Build an AppDynamics-style service topology map using Cypher queries.
     * <p>
     * Returns three lists:
     * <ul>
     *   <li>{@code services} — all nodes with kind=service, with a count of sibling nodes in the same module</li>
     *   <li>{@code infrastructure} — database/topic/queue/infra nodes</li>
     *   <li>{@code connections} — RELATES_TO edges from services to infra nodes, grouped by source/target/kind</li>
     * </ul>
     */
    public Map<String, Object> getTopology() {
        List<String> infraKinds = List.of(
                "database_connection", "topic", "queue", "message_queue", "infra_resource");

        List<Map<String, Object>> services = new ArrayList<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (s:CodeNode) WHERE s.kind = 'service' "
                            + "OPTIONAL MATCH (m:CodeNode) WHERE m.module = s.module AND m.id <> s.id "
                            + "RETURN s.id AS id, s.label AS label, s.layer AS layer, s.kind AS kind, "
                            + "count(m) AS node_count");
            while (result.hasNext()) {
                var row = result.next();
                Map<String, Object> svc = new LinkedHashMap<>();
                svc.put("id", row.get("id"));
                svc.put("label", row.get("label"));
                svc.put("kind", row.get("kind"));
                svc.put("layer", row.get("layer"));
                Object nc = row.get("node_count");
                svc.put("node_count", nc instanceof Number n ? n.longValue() : 0L);
                services.add(svc);
            }
        }

        List<Map<String, Object>> infrastructure = new ArrayList<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.kind IN $kinds "
                            + "RETURN n.id AS id, n.label AS label, n.kind AS kind",
                    Map.of("kinds", infraKinds));
            while (result.hasNext()) {
                var row = result.next();
                String id = (String) row.get("id");
                String kind = (String) row.get("kind");
                Map<String, Object> infra = new LinkedHashMap<>();
                infra.put("id", id);
                infra.put("label", row.get("label"));
                infra.put("kind", kind);
                // Derive type from id prefix (e.g., "postgresql:orders-db" → "postgresql")
                if (id != null && id.contains(":")) {
                    infra.put("type", id.split(":", 2)[0]);
                } else {
                    infra.put("type", kind);
                }
                infrastructure.add(infra);
            }
        }

        List<Map<String, Object>> connections = new ArrayList<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (s:CodeNode)-[r:RELATES_TO]->(t:CodeNode) "
                            + "WHERE s.kind = 'service' AND t.kind IN $kinds "
                            + "RETURN s.id AS source, t.id AS target, r.kind AS kind, count(r) AS cnt",
                    Map.of("kinds", infraKinds));
            while (result.hasNext()) {
                var row = result.next();
                Map<String, Object> conn = new LinkedHashMap<>();
                conn.put("source", row.get("source"));
                conn.put("target", row.get("target"));
                conn.put("kind", row.get("kind"));
                Object cnt = row.get("cnt");
                conn.put("count", cnt instanceof Number n ? n.longValue() : 0L);
                connections.add(conn);
            }
        }

        Map<String, Object> topology = new LinkedHashMap<>();
        topology.put("services", services);
        topology.put("infrastructure", infrastructure);
        topology.put("connections", connections);
        return topology;
    }

    // --- Internal helpers ---

    /**
     * Run a Cypher query that returns nodes as 'n' or 'm' and map them to CodeNode
     * without loading relationships (avoids SDN recursive hydration).
     */
    private List<CodeNode> queryNodes(String cypher, Map<String, Object> params) {
        List<CodeNode> nodes = new ArrayList<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(cypher, params);
            List<String> columns = result.columns();
            // Use the first column that returns a Node
            String nodeCol = columns.size() == 1 ? columns.getFirst() : "n";
            // Try 'm' if 'n' isn't in columns (neighbor queries return 'm')
            if (!columns.contains(nodeCol) && columns.contains("m")) {
                nodeCol = "m";
            }
            // For queries returning 'b' (ego graph, trace impact)
            if (!columns.contains(nodeCol) && columns.contains("b")) {
                nodeCol = "b";
            }
            while (result.hasNext()) {
                var row = result.next();
                Object val = row.get(nodeCol);
                if (val instanceof org.neo4j.graphdb.Node neo4jNode) {
                    nodes.add(nodeFromNeo4j(neo4jNode));
                }
            }
        }
        return nodes;
    }

    /**
     * Hydrate edges on a list of nodes by querying all RELATES_TO relationships.
     * Only used by findAll() for flow/topology views that need edge data.
     */
    private void hydrateEdges(List<CodeNode> nodes) {
        if (nodes.isEmpty()) return;

        // Build id→node lookup
        Map<String, CodeNode> nodeById = new HashMap<>(nodes.size());
        for (CodeNode node : nodes) {
            nodeById.put(node.getId(), node);
        }

        // Query all edges and attach to source nodes
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (s:CodeNode)-[r:RELATES_TO]->(t:CodeNode) "
                            + "RETURN r.id AS id, r.kind AS kind, s.id AS sourceId, t.id AS targetId");
            while (result.hasNext()) {
                var row = result.next();
                String sourceId = (String) row.get("sourceId");
                String targetId = (String) row.get("targetId");
                String edgeId = (String) row.get("id");
                String kindStr = (String) row.get("kind");

                CodeNode source = nodeById.get(sourceId);
                CodeNode target = nodeById.get(targetId);
                if (source != null && target != null) {
                    EdgeKind edgeKind;
                    try {
                        edgeKind = EdgeKind.fromValue(kindStr);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    source.getEdges().add(new CodeEdge(edgeId, edgeKind, sourceId, target));
                }
            }
        }
    }

    /**
     * Hydrate edges for a single node within an existing transaction.
     * Used by findById() to populate outgoing edges for node detail views.
     */
    private void hydrateEdgesForNode(Transaction tx, CodeNode node) {
        var result = tx.execute(
                "MATCH (s:CodeNode {id: $nodeId})-[r:RELATES_TO]->(t:CodeNode) "
                        + "RETURN r.id AS id, r.kind AS kind, t.id AS targetId, t",
                Map.of("nodeId", node.getId()));
        while (result.hasNext()) {
            var row = result.next();
            String edgeId = (String) row.get("id");
            String kindStr = (String) row.get("kind");
            String targetId = (String) row.get("targetId");
            EdgeKind edgeKind;
            try {
                edgeKind = EdgeKind.fromValue(kindStr);
            } catch (IllegalArgumentException e) {
                continue;
            }
            // Build a lightweight target node (id only for reference)
            var targetNeo4j = (org.neo4j.graphdb.Node) row.get("t");
            CodeNode target = nodeFromNeo4j(targetNeo4j);
            node.getEdges().add(new CodeEdge(edgeId, edgeKind, node.getId(), target));
        }
    }

    /**
     * Convert a Neo4j embedded Node to a CodeNode without loading relationships.
     * This is the key to avoiding OOM — we read only scalar properties.
     */
    private static CodeNode nodeFromNeo4j(org.neo4j.graphdb.Node neo4jNode) {
        String id = (String) neo4jNode.getProperty("id", null);
        String kindStr = (String) neo4jNode.getProperty("kind", null);
        NodeKind kind = kindStr != null ? NodeKind.fromValue(kindStr) : NodeKind.MODULE;
        String label = (String) neo4jNode.getProperty("label", "");

        CodeNode node = new CodeNode(id, kind, label);
        node.setFqn((String) neo4jNode.getProperty("fqn", null));
        node.setModule((String) neo4jNode.getProperty("module", null));
        node.setFilePath((String) neo4jNode.getProperty("filePath", null));
        node.setLayer((String) neo4jNode.getProperty("layer", null));

        Object lineStart = neo4jNode.getProperty("lineStart", null);
        if (lineStart instanceof Number n) node.setLineStart(n.intValue());
        Object lineEnd = neo4jNode.getProperty("lineEnd", null);
        if (lineEnd instanceof Number n) node.setLineEnd(n.intValue());

        // Restore annotations
        String annotations = (String) neo4jNode.getProperty("annotations", null);
        if (annotations != null && !annotations.isBlank()) {
            node.setAnnotations(new ArrayList<>(List.of(annotations.split(","))));
        }

        // Restore properties from prop_* prefixed keys
        for (String key : neo4jNode.getPropertyKeys()) {
            if (key.startsWith("prop_")) {
                String propName = key.substring(5);
                node.getProperties().put(propName, neo4jNode.getProperty(key));
            }
        }

        return node;
    }
}
