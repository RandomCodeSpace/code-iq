package io.github.randomcodespace.iq.graph;

import io.github.randomcodespace.iq.flow.FlowDataSource;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.Confidence;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

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
    private static final String PROP_CNT = "cnt";
    private static final String PROP_CONFIDENCE = "confidence";
    private static final String PROP_CONNECTIONS = "connections";
    private static final String PROP_EXT = "ext";
    private static final String PROP_FILEPATH = "filePath";
    private static final String PROP_ID = "id";
    private static final String PROP_IDS = "ids";
    private static final String PROP_KIND = "kind";
    private static final String PROP_KINDS = "kinds";
    private static final String PROP_LABEL = "label";
    private static final String PROP_LAYER = "layer";
    private static final String PROP_LIMIT = "limit";
    private static final String PROP_METHOD = "method";
    private static final String PROP_MODULE = "module";
    private static final String PROP_NODEID = "nodeId";
    private static final String PROP_OFFSET = "offset";
    private static final String PROP_SOURCE = "source";
    private static final String PROP_SOURCEID = "sourceId";
    private static final String PROP_TARGET = "target";
    private static final String PROP_TARGETID = "targetId";
    private static final String PROP_TEXT = "text";


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
                deleted = result.hasNext() ? ((Number) result.next().get(PROP_CNT)).intValue() : 0;
                tx.commit();
            }
        } while (deleted > 0);

        // 2. Create indexes: id for MATCH, label_lower/fqn_lower for fast case-insensitive search
        try (Transaction tx = graphDb.beginTx()) {
            tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.id)");
            tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.label_lower)");
            tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.fqn_lower)");
            tx.execute("CREATE FULLTEXT INDEX search_index IF NOT EXISTS "
                    + "FOR (n:CodeNode) ON EACH [n.label_lower, n.fqn_lower] "
                    + "OPTIONS {indexConfig: {`fulltext.analyzer`: 'keyword'}}");
            tx.execute("CREATE FULLTEXT INDEX lexical_index IF NOT EXISTS "
                    + "FOR (n:CodeNode) ON EACH [n.prop_lex_comment, n.prop_lex_config_keys] "
                    + "OPTIONS {indexConfig: {`fulltext.analyzer`: 'standard'}}");
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

        // 5. Collect all edges and identify missing target/source nodes
        List<CodeEdge> allEdges = nodes.stream()
                .flatMap(n -> n.getEdges().stream())
                .toList();
        int totalEdges = allEdges.size();

        // Pre-scan for stub nodes needed (edges referencing nodes not in the graph)
        Set<String> stubNodeIds = new LinkedHashSet<>();
        for (CodeEdge edge : allEdges) {
            String sourceId = edge.getSourceId();
            String targetId = edge.getTarget() != null ? edge.getTarget().getId() : null;
            if (sourceId != null && !savedNodeIds.contains(sourceId)) stubNodeIds.add(sourceId);
            if (targetId != null && !savedNodeIds.contains(targetId)) stubNodeIds.add(targetId);
        }

        // Create stub nodes for external references so no edges are lost
        if (!stubNodeIds.isEmpty()) {
            log.info("Neo4j: creating {} stub nodes for external edge references", stubNodeIds.size());
            List<Map<String, Object>> stubBatch = new ArrayList<>();
            for (String stubId : stubNodeIds) {
                stubBatch.add(Map.of(PROP_ID, stubId, PROP_KIND, "external", "label", stubId));
                savedNodeIds.add(stubId);
                if (stubBatch.size() >= batchSize) {
                    try (Transaction tx = graphDb.beginTx()) {
                        tx.execute("UNWIND $batch AS n MERGE (node:CodeNode {id: n.id}) "
                                + "ON CREATE SET node.kind = n.kind, node.label = n.label",
                                Map.of("batch", stubBatch));
                        tx.commit();
                    }
                    stubBatch.clear();
                }
            }
            if (!stubBatch.isEmpty()) {
                try (Transaction tx = graphDb.beginTx()) {
                    tx.execute("UNWIND $batch AS n MERGE (node:CodeNode {id: n.id}) "
                            + "ON CREATE SET node.kind = n.kind, node.label = n.label",
                            Map.of("batch", stubBatch));
                    tx.commit();
                }
            }
        }

        // 6. Save edges using UNWIND for batch inserts
        log.info("Neo4j: persisting {} edges...", totalEdges);

        int created = 0;
        int skipped = 0;
        for (int i = 0; i < totalEdges; i += batchSize) {
            List<CodeEdge> batch = allEdges.subList(i, Math.min(i + batchSize, totalEdges));
            List<Map<String, Object>> edgeBatch = new ArrayList<>(batch.size());
            for (CodeEdge edge : batch) {
                String sourceId = edge.getSourceId();
                String targetId = edge.getTarget() != null ? edge.getTarget().getId() : null;
                if (targetId == null || sourceId == null) {
                    skipped++;
                    continue;
                }
                // Stubs were already created above — all IDs should exist now
                if (!savedNodeIds.contains(sourceId) || !savedNodeIds.contains(targetId)) {
                    skipped++;
                    continue;
                }
                // HashMap (not Map.of) so we can null-skip optional fields.
                Map<String, Object> edgeProps = new HashMap<>(6);
                edgeProps.put(PROP_SOURCEID, sourceId);
                edgeProps.put(PROP_TARGETID, targetId);
                edgeProps.put("edgeId", edge.getId());
                edgeProps.put(PROP_KIND, edge.getKind().getValue());
                edgeProps.put(PROP_CONFIDENCE, edge.getConfidence().name());
                if (edge.getSource() != null) {
                    edgeProps.put(PROP_SOURCE, edge.getSource());
                }
                edgeBatch.add(edgeProps);
                created++;
            }
            if (!edgeBatch.isEmpty()) {
                try (Transaction tx = graphDb.beginTx()) {
                    // coalesce(e.source, NULL) — Cypher accepts missing map keys as NULL,
                    // so omitting `source` from the param map cleanly results in r.source IS NULL.
                    tx.execute("""
                            UNWIND $batch AS e
                            MATCH (s:CodeNode {id: e.sourceId}), (t:CodeNode {id: e.targetId})
                            CREATE (s)-[:RELATES_TO {
                                id: e.edgeId,
                                kind: e.kind,
                                sourceId: e.sourceId,
                                confidence: e.confidence,
                                source: e.source
                            }]->(t)
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
        props.put(PROP_ID, node.getId());
        props.put(PROP_KIND, node.getKind().getValue());
        props.put(PROP_LABEL, node.getLabel());
        if (node.getFqn() != null) props.put("fqn", node.getFqn());
        if (node.getModule() != null) props.put(PROP_MODULE, node.getModule());
        if (node.getFilePath() != null) props.put(PROP_FILEPATH, node.getFilePath());
        if (node.getLineStart() != null) props.put("lineStart", node.getLineStart());
        if (node.getLineEnd() != null) props.put("lineEnd", node.getLineEnd());
        if (node.getLayer() != null) props.put(PROP_LAYER, node.getLayer());
        // Confidence + source are typed first-class fields on CodeNode (not entries
        // in node.getProperties()) — store as bare Neo4j properties alongside layer/kind.
        // Confidence is never null at rest (setter normalizes to LEXICAL); store the
        // enum name so Cypher filters like WHERE n.confidence = 'RESOLVED' match.
        props.put(PROP_CONFIDENCE, node.getConfidence().name());
        if (node.getSource() != null) props.put(PROP_SOURCE, node.getSource());
        if (node.getAnnotations() != null && !node.getAnnotations().isEmpty()) {
            props.put("annotations", String.join(",", node.getAnnotations()));
        }
        // Pre-lowered properties for index-backed case-insensitive search
        props.put("label_lower", node.getLabel() != null ? node.getLabel().toLowerCase() : "");
        if (node.getFqn() != null) props.put("fqn_lower", node.getFqn().toLowerCase());
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
                    "MATCH (n:CodeNode {id: $id}) RETURN n", Map.of(PROP_ID, id));
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
                Map.of(PROP_KIND, kind.getValue()));
    }

    public List<CodeNode> findByLayer(String layer) {
        return queryNodes("MATCH (n:CodeNode) WHERE n.layer = $layer RETURN n",
                Map.of(PROP_LAYER, layer));
    }

    public List<CodeNode> findByModule(String module) {
        return queryNodes("MATCH (n:CodeNode) WHERE n.module = $module RETURN n",
                Map.of(PROP_MODULE, module));
    }

    public List<CodeNode> findByFilePath(String filePath) {
        return queryNodes("MATCH (n:CodeNode) WHERE n.filePath = $filePath RETURN n",
                Map.of(PROP_FILEPATH, filePath));
    }

    public List<CodeNode> search(String text) {
        return queryNodes(
                "CALL db.index.fulltext.queryNodes('search_index', $text) "
                        + "YIELD node RETURN node AS n",
                Map.of(PROP_TEXT, toLuceneQuery(text)));
    }

    public List<CodeNode> search(String text, int limit) {
        return queryNodes(
                "CALL db.index.fulltext.queryNodes('search_index', $text) "
                        + "YIELD node RETURN node AS n LIMIT $limit",
                Map.of(PROP_TEXT, toLuceneQuery(text), PROP_LIMIT, limit));
    }

    /**
     * Search the lexical index ({@code prop_lex_comment} and {@code prop_lex_config_keys})
     * for nodes matching {@code text}. Used by {@code LexicalQueryService} for doc comment
     * and config key retrieval.
     */
    public List<CodeNode> searchLexical(String text, int limit) {
        return queryNodes(
                "CALL db.index.fulltext.queryNodes('lexical_index', $text) "
                        + "YIELD node RETURN node AS n LIMIT $limit",
                Map.of(PROP_TEXT, toLuceneQuery(text), PROP_LIMIT, limit));
    }

    /**
     * Wraps a search term in Lucene wildcard syntax for substring matching against
     * the fulltext index (which stores lowercased property values via keyword analyzer).
     * Escapes Lucene special characters before wrapping.
     */
    private static String toLuceneQuery(String text) {
        String lower = text.toLowerCase();
        String escaped = lower
                .replace("\\", "\\\\")
                .replace("+", "\\+")
                .replace("-", "\\-")
                .replace("!", "\\!")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("^", "\\^")
                .replace("\"", "\\\"")
                .replace("~", "\\~")
                .replace(":", "\\:")
                .replace("/", "\\/");
        return "*" + escaped + "*";
    }

    public List<CodeNode> findNeighbors(String nodeId) {
        return queryNodes(
                "MATCH (n:CodeNode)-[r]-(m:CodeNode) WHERE n.id = $nodeId RETURN m",
                Map.of(PROP_NODEID, nodeId));
    }

    /**
     * Single-query batch lookup: for a list of node IDs, find all neighboring nodes
     * whose kind is ENDPOINT or WEBSOCKET_ENDPOINT.
     * Returns a map from source node ID to the list of endpoint neighbors found.
     */
    public Map<String, List<CodeNode>> findEndpointNeighborsBatch(List<String> nodeIds) {
        Map<String, List<CodeNode>> result = new LinkedHashMap<>();
        if (nodeIds.isEmpty()) return result;
        List<String> epKinds = List.of(NodeKind.ENDPOINT.getValue(), NodeKind.WEBSOCKET_ENDPOINT.getValue());
        try (Transaction tx = graphDb.beginTx()) {
            var qr = tx.execute(
                    "UNWIND $ids AS nid "
                            + "MATCH (n:CodeNode {id: nid})-[]-(ep:CodeNode) "
                            + "WHERE ep.kind IN $epKinds "
                            + "RETURN nid AS matchId, ep",
                    Map.of(PROP_IDS, nodeIds, "epKinds", epKinds));
            while (qr.hasNext()) {
                var row = qr.next();
                String matchId = (String) row.get("matchId");
                if (row.get("ep") instanceof org.neo4j.graphdb.Node neo4jNode) {
                    result.computeIfAbsent(matchId, k -> new ArrayList<>()).add(nodeFromNeo4j(neo4jNode));
                }
            }
        }
        return result;
    }

    public List<CodeNode> findOutgoingNeighbors(String nodeId) {
        return queryNodes(
                "MATCH (n:CodeNode)-[r]->(m:CodeNode) WHERE n.id = $nodeId RETURN m",
                Map.of(PROP_NODEID, nodeId));
    }

    public List<CodeNode> findIncomingNeighbors(String nodeId) {
        return queryNodes(
                "MATCH (n:CodeNode)<-[r]-(m:CodeNode) WHERE n.id = $nodeId RETURN m",
                Map.of(PROP_NODEID, nodeId));
    }

    public long count() {
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute("MATCH (n:CodeNode) RETURN count(n) AS cnt");
            if (result.hasNext()) {
                return ((Number) result.next().get(PROP_CNT)).longValue();
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
                    Map.of(PROP_SOURCE, source, PROP_TARGET, target));
            if (result.hasNext()) {
                @SuppressWarnings("unchecked")
                List<String> ids = (List<String>) result.next().get(PROP_IDS);
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
                Map.of(PROP_NODEID, nodeId, "depth", depth));
    }

    public List<List<String>> findCycles(int limit) {
        List<List<String>> cycles = new ArrayList<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH p = (a:CodeNode)-[:RELATES_TO*2..10]->(a) "
                            + "RETURN [n IN nodes(p) | n.id] AS ids LIMIT $limit",
                    Map.of(PROP_LIMIT, limit));
            while (result.hasNext()) {
                @SuppressWarnings("unchecked")
                List<String> ids = (List<String>) result.next().get(PROP_IDS);
                cycles.add(ids);
            }
        }
        return cycles;
    }

    public List<CodeNode> findConsumers(String targetId) {
        return queryNodes(
                "MATCH (n:CodeNode)<-[r:RELATES_TO]-(m:CodeNode) "
                        + "WHERE n.id = $targetId AND r.kind IN ['consumes', 'listens'] RETURN m",
                Map.of(PROP_TARGETID, targetId));
    }

    public List<CodeNode> findProducers(String targetId) {
        return queryNodes(
                "MATCH (n:CodeNode)<-[r:RELATES_TO]-(m:CodeNode) "
                        + "WHERE n.id = $targetId AND r.kind IN ['produces', 'publishes'] RETURN m",
                Map.of(PROP_TARGETID, targetId));
    }

    public List<CodeNode> findCallers(String targetId) {
        return queryNodes(
                "MATCH (n:CodeNode)<-[r:RELATES_TO]-(m:CodeNode) "
                        + "WHERE n.id = $targetId AND r.kind = 'calls' RETURN m",
                Map.of(PROP_TARGETID, targetId));
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
                "MATCH (n:CodeNode) WHERE n.kind = $kind RETURN n ORDER BY n.id SKIP $offset LIMIT $limit",
                Map.of(PROP_KIND, kind, PROP_OFFSET, offset, PROP_LIMIT, limit));
    }

    public List<CodeNode> findAllPaginated(int offset, int limit) {
        return queryNodes(
                "MATCH (n:CodeNode) RETURN n ORDER BY n.id SKIP $offset LIMIT $limit",
                Map.of(PROP_OFFSET, offset, PROP_LIMIT, limit));
    }

    public long countByKind(String kind) {
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.kind = $kind RETURN count(n) AS cnt",
                    Map.of(PROP_KIND, kind));
            if (result.hasNext()) {
                return ((Number) result.next().get(PROP_CNT)).longValue();
            }
            return 0;
        }
    }

    // --- Aggregation queries ---

    public long countEdges() {
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute("MATCH ()-[r:RELATES_TO]->() RETURN count(r) AS cnt");
            if (result.hasNext()) {
                return ((Number) result.next().get(PROP_CNT)).longValue();
            }
            return 0;
        }
    }

    public long countDistinctFiles() {
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.filePath IS NOT NULL AND n.filePath <> '' "
                            + "RETURN count(DISTINCT n.filePath) AS cnt");
            if (result.hasNext()) {
                return ((Number) result.next().get(PROP_CNT)).longValue();
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
                m.put(PROP_EXT, row.get(PROP_EXT));
                m.put(PROP_CNT, ((Number) row.get(PROP_CNT)).longValue());
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
                m.put(PROP_KIND, row.get(PROP_KIND));
                m.put(PROP_CNT, ((Number) row.get(PROP_CNT)).longValue());
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
                m.put(PROP_LAYER, row.get(PROP_LAYER));
                m.put(PROP_CNT, ((Number) row.get(PROP_CNT)).longValue());
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
                            + "ORDER BY r.id SKIP $offset LIMIT $limit",
                    Map.of(PROP_OFFSET, offset, PROP_LIMIT, limit));
            while (result.hasNext()) {
                var row = result.next();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put(PROP_ID, row.get(PROP_ID));
                m.put(PROP_KIND, row.get(PROP_KIND));
                m.put(PROP_SOURCEID, row.get(PROP_SOURCEID));
                m.put(PROP_TARGETID, row.get(PROP_TARGETID));
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
                            + "ORDER BY r.id SKIP $offset LIMIT $limit",
                    Map.of(PROP_KIND, kind, PROP_OFFSET, offset, PROP_LIMIT, limit));
            while (result.hasNext()) {
                var row = result.next();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put(PROP_ID, row.get(PROP_ID));
                m.put(PROP_KIND, row.get(PROP_KIND));
                m.put(PROP_SOURCEID, row.get(PROP_SOURCEID));
                m.put(PROP_TARGETID, row.get(PROP_TARGETID));
                rows.add(m);
            }
        }
        return rows;
    }

    /** Result wrapper for file-path queries, carrying a truncation flag. */
    public record FilePathResult(List<Map<String, Object>> rows, boolean truncated) {}

    /**
     * Return each distinct filePath that has at least one CodeNode, along with the count
     * of nodes stored at that path. Results are capped at {@code maxFiles}; if more paths
     * exist the {@link FilePathResult#truncated()} flag is set to {@code true}.
     */
    public FilePathResult getFilePathsWithCounts(int maxFiles) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Transaction tx = graphDb.beginTx()) {
            // When maxFiles is very large (e.g., Integer.MAX_VALUE for unlimited treemap),
            // skip the LIMIT clause entirely to avoid integer overflow
            String query = "MATCH (n:CodeNode) WHERE n.filePath IS NOT NULL "
                    + "RETURN n.filePath AS filePath, count(n) AS nodeCount "
                    + "ORDER BY n.filePath";
            Result result;
            if (maxFiles < 1_000_000) {
                result = tx.execute(query + " LIMIT $limit",
                        Map.of(PROP_LIMIT, (long) (maxFiles + 1)));
            } else {
                result = tx.execute(query);
            }
            while (result.hasNext()) {
                var row = result.next();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put(PROP_FILEPATH, row.get(PROP_FILEPATH));
                m.put("nodeCount", ((Number) row.get("nodeCount")).longValue());
                rows.add(m);
            }
        }
        boolean truncated = rows.size() > maxFiles;
        if (truncated) {
            rows = rows.subList(0, maxFiles);
        }
        return new FilePathResult(rows, truncated);
    }

    public long countEdgesByKind(String kind) {
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH ()-[r:RELATES_TO]->() WHERE r.kind = $kind RETURN count(r) AS cnt",
                    Map.of(PROP_KIND, kind));
            if (result.hasNext()) {
                return ((Number) result.next().get(PROP_CNT)).longValue();
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
                Map.of(PROP_KINDS, kinds,
                        "semanticKinds", semanticEdgeKinds,
                        "excludeKinds", excludeNodeKinds,
                        PROP_OFFSET, offset,
                        PROP_LIMIT, limit));
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
                Map.of(PROP_KINDS, kinds, PROP_OFFSET, offset, PROP_LIMIT, limit));
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
                svc.put(PROP_ID, row.get(PROP_ID));
                svc.put(PROP_LABEL, row.get(PROP_LABEL));
                svc.put(PROP_KIND, row.get(PROP_KIND));
                svc.put(PROP_LAYER, row.get(PROP_LAYER));
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
                    Map.of(PROP_KINDS, infraKinds));
            while (result.hasNext()) {
                var row = result.next();
                String id = (String) row.get(PROP_ID);
                String kind = (String) row.get(PROP_KIND);
                Map<String, Object> infra = new LinkedHashMap<>();
                infra.put(PROP_ID, id);
                infra.put(PROP_LABEL, row.get(PROP_LABEL));
                infra.put(PROP_KIND, kind);
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
                    Map.of(PROP_KINDS, infraKinds));
            while (result.hasNext()) {
                var row = result.next();
                Map<String, Object> conn = new LinkedHashMap<>();
                conn.put(PROP_SOURCE, row.get(PROP_SOURCE));
                conn.put(PROP_TARGET, row.get(PROP_TARGET));
                conn.put(PROP_KIND, row.get(PROP_KIND));
                Object cnt = row.get(PROP_CNT);
                conn.put("count", cnt instanceof Number n ? n.longValue() : 0L);
                connections.add(conn);
            }
        }

        Map<String, Object> topology = new LinkedHashMap<>();
        topology.put("services", services);
        topology.put("infrastructure", infrastructure);
        topology.put(PROP_CONNECTIONS, connections);
        return topology;
    }

    // --- Stats aggregation queries (Cypher-only, no node hydration) ---

    /**
     * Compute all categorized stats via Cypher aggregation.
     * Never loads full nodes into heap — safe for 100K+ node graphs.
     */
    public Map<String, Object> computeAggregateStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("graph", computeGraphStats());
        result.put("languages", computeLanguageStats());
        result.put("frameworks", computeFrameworkStats());
        result.put("infra", computeInfraStats());
        result.put(PROP_CONNECTIONS, computeConnectionStats());
        result.put("auth", computeAuthStats());
        result.put("architecture", computeArchitectureStats());
        return result;
    }

    /**
     * Compute stats for a single category via Cypher aggregation.
     */
    public Map<String, Object> computeAggregateCategoryStats(String category) {
        return switch (category.toLowerCase()) {
            case "graph" -> computeGraphStats();
            case "languages" -> computeLanguageStats();
            case "frameworks" -> computeFrameworkStats();
            case "infra" -> computeInfraStats();
            case PROP_CONNECTIONS -> computeConnectionStats();
            case "auth" -> computeAuthStats();
            case "architecture" -> computeArchitectureStats();
            default -> null;
        };
    }

    private Map<String, Object> computeGraphStats() {
        Map<String, Object> graph = new LinkedHashMap<>();
        try (Transaction tx = graphDb.beginTx()) {
            var r1 = tx.execute("MATCH (n:CodeNode) RETURN count(n) AS cnt");
            graph.put("nodes", r1.hasNext() ? ((Number) r1.next().get(PROP_CNT)).longValue() : 0L);
            var r2 = tx.execute("MATCH ()-[r:RELATES_TO]->() RETURN count(r) AS cnt");
            graph.put("edges", r2.hasNext() ? ((Number) r2.next().get(PROP_CNT)).longValue() : 0L);
            var r3 = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.filePath IS NOT NULL AND n.filePath <> '' "
                            + "RETURN count(DISTINCT n.filePath) AS cnt");
            graph.put("files", r3.hasNext() ? ((Number) r3.next().get(PROP_CNT)).longValue() : 0L);

            // Edge kind breakdown
            var r4 = tx.execute(
                    "MATCH ()-[r:RELATES_TO]->() "
                            + "RETURN r.kind AS kind, count(r) AS cnt ORDER BY cnt DESC");
            Map<String, Long> edgesByKind = new LinkedHashMap<>();
            while (r4.hasNext()) {
                var row = r4.next();
                String kind = (String) row.get(PROP_KIND);
                long cnt = ((Number) row.get(PROP_CNT)).longValue();
                if (kind != null) edgesByKind.put(kind, cnt);
            }
            graph.put("edges_by_kind", edgesByKind);
        }
        return graph;
    }

    private Map<String, Object> computeLanguageStats() {
        Map<String, Long> langCounts = new TreeMap<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.prop_language IS NOT NULL "
                            + "RETURN toLower(n.prop_language) AS lang, count(n) AS cnt");
            while (result.hasNext()) {
                var row = result.next();
                String lang = String.valueOf(row.get("lang")).trim();
                if (!lang.isBlank()) {
                    langCounts.merge(lang, ((Number) row.get(PROP_CNT)).longValue(), Long::sum);
                }
            }
        }
        if (langCounts.isEmpty()) {
            for (Map<String, Object> row : countByFileExtension()) {
                String ext = String.valueOf(row.get(PROP_EXT)).trim().toLowerCase();
                String lang = extensionToLanguage(ext);
                if (lang != null) {
                    langCounts.merge(lang, ((Number) row.get(PROP_CNT)).longValue(), Long::sum);
                }
            }
        }
        return new LinkedHashMap<>(sortByValueDesc(langCounts));
    }

    private Map<String, Object> computeFrameworkStats() {
        Map<String, Long> fwCounts = new TreeMap<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.prop_framework IS NOT NULL "
                            + "RETURN n.prop_framework AS fw, count(n) AS cnt");
            while (result.hasNext()) {
                var row = result.next();
                String fw = String.valueOf(row.get("fw")).trim();
                if (!fw.isBlank()) {
                    fwCounts.merge(fw, ((Number) row.get(PROP_CNT)).longValue(), Long::sum);
                }
            }
        }
        return new LinkedHashMap<>(sortByValueDesc(fwCounts));
    }

    private Map<String, Object> computeInfraStats() {
        Map<String, Object> infra = new LinkedHashMap<>();

        Map<String, Long> databases = new TreeMap<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.kind = 'database_connection' AND n.prop_db_type IS NOT NULL "
                            + "RETURN n.prop_db_type AS dbType, count(n) AS cnt");
            while (result.hasNext()) {
                var row = result.next();
                String dbType = normalizeDbType(String.valueOf(row.get("dbType")));
                if (dbType != null) {
                    databases.merge(dbType, ((Number) row.get(PROP_CNT)).longValue(), Long::sum);
                }
            }
        }
        infra.put("databases", sortByValueDesc(databases));

        Map<String, Long> messaging = new TreeMap<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.kind IN ['topic', 'queue', 'message_queue'] "
                            + "RETURN coalesce(n.prop_protocol, n.label, 'unknown') AS protocol, count(n) AS cnt");
            while (result.hasNext()) {
                var row = result.next();
                messaging.merge(String.valueOf(row.get("protocol")), ((Number) row.get(PROP_CNT)).longValue(), Long::sum);
            }
        }
        infra.put("messaging", sortByValueDesc(messaging));

        Map<String, Long> cloud = new TreeMap<>();
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.kind IN ['azure_resource', 'infra_resource'] "
                            + "RETURN coalesce(n.prop_resource_type, n.label, 'unknown') AS resType, count(n) AS cnt");
            while (result.hasNext()) {
                var row = result.next();
                cloud.merge(String.valueOf(row.get("resType")), ((Number) row.get(PROP_CNT)).longValue(), Long::sum);
            }
        }
        infra.put("cloud", sortByValueDesc(cloud));

        return infra;
    }

    private Map<String, Object> computeConnectionStats() {
        Map<String, Object> connections = new LinkedHashMap<>();
        try (Transaction tx = graphDb.beginTx()) {
            var restResult = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.kind = 'endpoint' "
                            + "AND (n.prop_protocol IS NULL OR n.prop_protocol <> 'grpc') "
                            + "RETURN coalesce(toUpper(n.prop_http_method), 'UNKNOWN') AS method, count(n) AS cnt");
            Map<String, Long> restByMethod = new TreeMap<>();
            while (restResult.hasNext()) {
                var row = restResult.next();
                restByMethod.put(String.valueOf(row.get(PROP_METHOD)), ((Number) row.get(PROP_CNT)).longValue());
            }
            long restTotal = restByMethod.values().stream().mapToLong(Long::longValue).sum();
            Map<String, Object> rest = new LinkedHashMap<>();
            rest.put("total", restTotal);
            rest.put("by_method", sortByValueDesc(restByMethod));
            connections.put("rest", rest);

            var grpcResult = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.kind = 'endpoint' AND n.prop_protocol = 'grpc' RETURN count(n) AS cnt");
            connections.put("grpc", grpcResult.hasNext() ? ((Number) grpcResult.next().get(PROP_CNT)).longValue() : 0L);

            var wsResult = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.kind = 'websocket_endpoint' RETURN count(n) AS cnt");
            connections.put("websocket", wsResult.hasNext() ? ((Number) wsResult.next().get(PROP_CNT)).longValue() : 0L);

            var prodResult = tx.execute(
                    "MATCH ()-[r:RELATES_TO]->() WHERE r.kind IN ['produces', 'publishes'] RETURN count(r) AS cnt");
            connections.put("producers", prodResult.hasNext() ? ((Number) prodResult.next().get(PROP_CNT)).longValue() : 0L);

            var consResult = tx.execute(
                    "MATCH ()-[r:RELATES_TO]->() WHERE r.kind IN ['consumes', 'listens'] RETURN count(r) AS cnt");
            connections.put("consumers", consResult.hasNext() ? ((Number) consResult.next().get(PROP_CNT)).longValue() : 0L);
        }
        return connections;
    }

    private Map<String, Object> computeAuthStats() {
        Map<String, Long> authCounts = new TreeMap<>();
        try (Transaction tx = graphDb.beginTx()) {
            var guardResult = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.kind = 'guard' AND n.prop_auth_type IS NOT NULL "
                            + "RETURN n.prop_auth_type AS authType, count(n) AS cnt");
            while (guardResult.hasNext()) {
                var row = guardResult.next();
                String authType = String.valueOf(row.get("authType")).trim();
                if (!authType.isBlank()) {
                    authCounts.merge(authType, ((Number) row.get(PROP_CNT)).longValue(), Long::sum);
                }
            }
            var fwResult = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.prop_framework STARTS WITH 'auth:' "
                            + "RETURN n.prop_framework AS fw, count(n) AS cnt");
            while (fwResult.hasNext()) {
                var row = fwResult.next();
                String fw = String.valueOf(row.get("fw")).trim();
                String authType = fw.substring("auth:".length()).trim();
                if (!authType.isEmpty()) {
                    authCounts.merge(authType, ((Number) row.get(PROP_CNT)).longValue(), Long::sum);
                }
            }
        }
        return new LinkedHashMap<>(sortByValueDesc(authCounts));
    }

    private Map<String, Object> computeArchitectureStats() {
        Map<String, Object> arch = new LinkedHashMap<>();
        Map<String, String> kindToLabel = Map.of(
                "class", "classes", "interface", "interfaces",
                "abstract_class", "abstract_classes", "enum", "enums",
                "annotation_type", "annotation_types", PROP_MODULE, "modules",
                PROP_METHOD, "methods");
        List<String> archKinds = List.of("class", "interface", "abstract_class", "enum",
                "annotation_type", PROP_MODULE, PROP_METHOD);
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode) WHERE n.kind IN $kinds RETURN n.kind AS kind, count(n) AS cnt",
                    Map.of(PROP_KINDS, archKinds));
            while (result.hasNext()) {
                var row = result.next();
                String kind = (String) row.get(PROP_KIND);
                long cnt = ((Number) row.get(PROP_CNT)).longValue();
                if (cnt > 0) {
                    arch.put(kindToLabel.getOrDefault(kind, kind), cnt);
                }
            }
        }
        return arch;
    }

    private static final Map<String, String> STATS_DB_TYPE_NORMALIZE = Map.ofEntries(
            Map.entry("mysql", "MySQL"), Map.entry("postgresql", "PostgreSQL"),
            Map.entry("postgres", "PostgreSQL"), Map.entry("sqlserver", "SQL Server"),
            Map.entry("mssql", "SQL Server"), Map.entry("oracle", "Oracle"),
            Map.entry("h2", "H2"), Map.entry("sqlite", "SQLite"),
            Map.entry("mariadb", "MariaDB"), Map.entry("mongo", "MongoDB"),
            Map.entry("mongodb", "MongoDB"), Map.entry("redis", "Redis"),
            Map.entry("neo4j", "Neo4j"));

    private static String normalizeDbType(String raw) {
        String lower = raw.trim().toLowerCase();
        if (lower.contains("@")) lower = lower.substring(0, lower.indexOf('@'));
        return STATS_DB_TYPE_NORMALIZE.getOrDefault(lower, raw.trim());
    }

    private static String extensionToLanguage(String ext) {
        return switch (ext) {
            case "java" -> "java"; case "kt", "kts" -> "kotlin";
            case "py" -> "python"; case "js", "mjs", "cjs" -> "javascript";
            case "ts", "tsx" -> "typescript"; case "go" -> "go";
            case "rs" -> "rust"; case "cs" -> "csharp";
            case "scala" -> "scala"; case "cpp", "cc", "cxx" -> "cpp";
            case "proto" -> "protobuf"; default -> null;
        };
    }

    private static <K> Map<K, Long> sortByValueDesc(Map<K, Long> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<K, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
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
                            + "RETURN r.id AS id, r.kind AS kind, s.id AS sourceId, t.id AS targetId, "
                            + "r.confidence AS confidence, r.source AS source");
            while (result.hasNext()) {
                var row = result.next();
                String sourceId = (String) row.get(PROP_SOURCEID);
                String targetId = (String) row.get(PROP_TARGETID);
                String edgeId = (String) row.get(PROP_ID);
                String kindStr = (String) row.get(PROP_KIND);

                CodeNode source = nodeById.get(sourceId);
                CodeNode target = nodeById.get(targetId);
                if (source != null && target != null) {
                    EdgeKind edgeKind;
                    try {
                        edgeKind = EdgeKind.fromValue(kindStr);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    CodeEdge edge = new CodeEdge(edgeId, edgeKind, sourceId, target);
                    applyEdgeConfidenceAndSource(edge, row);
                    source.getEdges().add(edge);
                }
            }
        }
    }

    /**
     * Apply confidence + source from a Cypher row to an edge. Missing or malformed
     * confidence falls back to {@link Confidence#LEXICAL} — never throws — so legacy
     * edges written before these fields existed read back cleanly. Source stays null
     * when missing.
     */
    private static void applyEdgeConfidenceAndSource(CodeEdge edge, Map<String, Object> row) {
        Object confObj = row.get(PROP_CONFIDENCE);
        if (confObj instanceof String confStr) {
            try {
                edge.setConfidence(Confidence.fromString(confStr));
            } catch (IllegalArgumentException ignored) {
                // keep default LEXICAL
            }
        }
        Object srcObj = row.get(PROP_SOURCE);
        if (srcObj instanceof String src) {
            edge.setSource(src);
        }
    }

    /**
     * Hydrate edges for a single node within an existing transaction.
     * Used by findById() to populate outgoing edges for node detail views.
     */
    private void hydrateEdgesForNode(Transaction tx, CodeNode node) {
        var result = tx.execute(
                "MATCH (s:CodeNode {id: $nodeId})-[r:RELATES_TO]->(t:CodeNode) "
                        + "RETURN r.id AS id, r.kind AS kind, t.id AS targetId, t, "
                        + "r.confidence AS confidence, r.source AS source",
                Map.of(PROP_NODEID, node.getId()));
        while (result.hasNext()) {
            var row = result.next();
            String edgeId = (String) row.get(PROP_ID);
            String kindStr = (String) row.get(PROP_KIND);
            String targetId = (String) row.get(PROP_TARGETID);
            EdgeKind edgeKind;
            try {
                edgeKind = EdgeKind.fromValue(kindStr);
            } catch (IllegalArgumentException e) {
                continue;
            }
            // targetId is read from the row but not used here — the lightweight target
            // node is built from the embedded `t` Node value. Suppress unused warning.
            assert targetId == null || !targetId.isEmpty();
            // Build a lightweight target node (id only for reference)
            var targetNeo4j = (org.neo4j.graphdb.Node) row.get("t");
            CodeNode target = nodeFromNeo4j(targetNeo4j);
            CodeEdge edge = new CodeEdge(edgeId, edgeKind, node.getId(), target);
            applyEdgeConfidenceAndSource(edge, row);
            node.getEdges().add(edge);
        }
    }

    /**
     * Convert a Neo4j embedded Node to a CodeNode without loading relationships.
     * This is the key to avoiding OOM — we read only scalar properties.
     */
    private static CodeNode nodeFromNeo4j(org.neo4j.graphdb.Node neo4jNode) {
        String id = (String) neo4jNode.getProperty(PROP_ID, null);
        String kindStr = (String) neo4jNode.getProperty(PROP_KIND, null);
        NodeKind kind = kindStr != null ? NodeKind.fromValue(kindStr) : NodeKind.MODULE;
        String label = (String) neo4jNode.getProperty(PROP_LABEL, "");

        CodeNode node = new CodeNode(id, kind, label);
        node.setFqn((String) neo4jNode.getProperty("fqn", null));
        node.setModule((String) neo4jNode.getProperty(PROP_MODULE, null));
        node.setFilePath((String) neo4jNode.getProperty(PROP_FILEPATH, null));
        node.setLayer((String) neo4jNode.getProperty(PROP_LAYER, null));
        // Restore confidence + source. Missing/malformed confidence falls back to
        // LEXICAL — least committal — so legacy nodes written before these fields
        // existed read back without surprise. Source stays null when missing.
        String confStr = (String) neo4jNode.getProperty(PROP_CONFIDENCE, null);
        if (confStr != null) {
            try {
                node.setConfidence(Confidence.fromString(confStr));
            } catch (IllegalArgumentException ignored) {
                // keep default LEXICAL — never throw on legacy/garbled values
            }
        }
        node.setSource((String) neo4jNode.getProperty(PROP_SOURCE, null));

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
