package io.github.randomcodespace.iq.graph;

import io.github.randomcodespace.iq.flow.FlowDataSource;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    // --- Read operations (embedded API, no relationship hydration) ---

    public Optional<CodeNode> findById(String id) {
        try (Transaction tx = graphDb.beginTx()) {
            var result = tx.execute(
                    "MATCH (n:CodeNode {id: $id}) RETURN n", Map.of("id", id));
            if (result.hasNext()) {
                var neo4jNode = (org.neo4j.graphdb.Node) result.next().get("n");
                return Optional.of(nodeFromNeo4j(neo4jNode));
            }
            return Optional.empty();
        }
    }

    public List<CodeNode> findAll() {
        return queryNodes("MATCH (n:CodeNode) RETURN n", Map.of());
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
                            + "RETURN r.id AS id, r.kind AS kind, r.sourceId AS sourceId, t.id AS targetId "
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
                            + "RETURN r.id AS id, r.kind AS kind, r.sourceId AS sourceId, t.id AS targetId "
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

    public List<CodeNode> findNodesWithoutIncoming(List<String> kinds, int offset, int limit) {
        return queryNodes(
                "MATCH (n:CodeNode) WHERE n.kind IN $kinds "
                        + "AND NOT EXISTS { MATCH (m)-[:RELATES_TO]->(n) } "
                        + "RETURN n SKIP $offset LIMIT $limit",
                Map.of("kinds", kinds, "offset", offset, "limit", limit));
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

        return node;
    }
}
