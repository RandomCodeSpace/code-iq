package io.github.randomcodespace.iq.graph;

import io.github.randomcodespace.iq.model.CodeNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data Neo4j repository for CodeNode entities.
 */
@Repository
public interface GraphRepository extends Neo4jRepository<CodeNode, String> {

    @Query("MATCH (n:CodeNode) WHERE n.kind = $kind RETURN n")
    List<CodeNode> findByKind(String kind);

    List<CodeNode> findByLayer(String layer);

    List<CodeNode> findByModule(String module);

    @Query("MATCH (n:CodeNode) WHERE n.filePath = $filePath RETURN n")
    List<CodeNode> findByFilePath(String filePath);

    @Query("MATCH (n:CodeNode) WHERE n.label_lower CONTAINS $text OR n.fqn_lower CONTAINS $text RETURN n LIMIT $limit")
    List<CodeNode> search(String text, int limit);

    @Query("MATCH (n:CodeNode) WHERE n.label CONTAINS $text OR n.fqn CONTAINS $text RETURN n")
    List<CodeNode> search(String text);

    @Query("MATCH (n:CodeNode)-[r]-(m:CodeNode) WHERE n.id = $nodeId RETURN m")
    List<CodeNode> findNeighbors(String nodeId);

    @Query("MATCH (n:CodeNode)-[r]->(m:CodeNode) WHERE n.id = $nodeId RETURN m")
    List<CodeNode> findOutgoingNeighbors(String nodeId);

    @Query("MATCH (n:CodeNode)<-[r]-(m:CodeNode) WHERE n.id = $nodeId RETURN m")
    List<CodeNode> findIncomingNeighbors(String nodeId);

    // --- Graph traversal queries ---

    @Query("MATCH p = shortestPath((a:CodeNode {id: $source})-[*..20]-(b:CodeNode {id: $target})) RETURN [n IN nodes(p) | n.id]")
    List<String> findShortestPath(String source, String target);

    @Query("MATCH (a:CodeNode {id: $center})-[*1..$radius]-(b:CodeNode) RETURN DISTINCT b")
    List<CodeNode> findEgoGraph(String center, int radius);

    @Query("MATCH (a:CodeNode {id: $nodeId})-[:CALLS|DEPENDS_ON|IMPORTS*1..$depth]->(b:CodeNode) RETURN DISTINCT b")
    List<CodeNode> traceImpact(String nodeId, int depth);

    @Query("MATCH p = (a:CodeNode)-[:DEPENDS_ON|CALLS*2..10]->(a) RETURN [n IN nodes(p) | n.id] LIMIT $limit")
    List<List<String>> findCycles(int limit);

    @Query("MATCH (n:CodeNode)<-[:CONSUMES|LISTENS]-(m:CodeNode) WHERE n.id = $targetId RETURN m")
    List<CodeNode> findConsumers(String targetId);

    @Query("MATCH (n:CodeNode)<-[:PRODUCES|PUBLISHES]-(m:CodeNode) WHERE n.id = $targetId RETURN m")
    List<CodeNode> findProducers(String targetId);

    @Query("MATCH (n:CodeNode)<-[:CALLS]-(m:CodeNode) WHERE n.id = $targetId RETURN m")
    List<CodeNode> findCallers(String targetId);

    @Query("MATCH (n:CodeNode)-[:DEPENDS_ON]->(m:CodeNode) WHERE n.id = $moduleId RETURN m")
    List<CodeNode> findDependencies(String moduleId);

    @Query("MATCH (n:CodeNode)<-[:DEPENDS_ON]-(m:CodeNode) WHERE n.id = $moduleId RETURN m")
    List<CodeNode> findDependents(String moduleId);

    @Query("MATCH (n:CodeNode) WHERE n.kind = $kind RETURN n SKIP $offset LIMIT $limit")
    List<CodeNode> findByKindPaginated(String kind, int offset, int limit);

    @Query("MATCH (n:CodeNode) RETURN n SKIP $offset LIMIT $limit")
    List<CodeNode> findAllPaginated(int offset, int limit);

    @Query("MATCH (n:CodeNode) WHERE n.kind = $kind RETURN count(n)")
    long countByKind(String kind);

    // Note: multi-column aggregation queries (countNodesByKind, countNodesByLayer,
    // findEdgesPaginated, findEdgesByKindPaginated) are in GraphStore using the
    // embedded Neo4j API directly, since SDN cannot map multi-column results.

    @Query("MATCH (n:CodeNode) WHERE n.kind IN $kinds AND NOT EXISTS { MATCH (m)-[:RELATES_TO]->(n) } RETURN n SKIP $offset LIMIT $limit")
    List<CodeNode> findNodesWithoutIncoming(List<String> kinds, int offset, int limit);

    @Query("MATCH (n:CodeNode)-[r]->(m:CodeNode) WHERE n.id = $nodeId RETURN type(r) AS kind, m")
    List<CodeNode> findOutgoingWithRelType(String nodeId);
}
