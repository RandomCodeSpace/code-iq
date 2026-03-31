package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-loads H2 analysis cache data into Neo4j on serve startup if Neo4j is empty.
 * <p>
 * This bridges the gap between {@code index} (which writes to H2 only) and
 * {@code serve} (which needs Neo4j for graph traversal queries like shortest path,
 * cycles, impact trace, ego graph, and neighbors).
 * <p>
 * Runs after the Spring context is fully ready, so Neo4j and GraphStore are available.
 * Only active in the "serving" profile when GraphStore is present.
 */
@Component
@Profile("serving")
@ConditionalOnBean(GraphStore.class)
public class GraphBootstrapper {

    private static final Logger log = LoggerFactory.getLogger(GraphBootstrapper.class);

    private final GraphStore graphStore;
    private final CodeIqConfig config;

    public GraphBootstrapper(GraphStore graphStore, CodeIqConfig config) {
        this.graphStore = graphStore;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapNeo4jFromCache() {
        // Check if Neo4j already has data
        long existingCount = graphStore.count();
        if (existingCount > 0) {
            log.info("Neo4j already contains {} nodes -- skipping H2 bootstrap", existingCount);
            return;
        }

        // Locate H2 cache
        Path root = Path.of(config.getRootPath()).toAbsolutePath().normalize();
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        Path h2File = root.resolve(config.getCacheDir()).resolve("analysis-cache.mv.db");

        if (!Files.exists(h2File)) {
            log.info("No H2 analysis cache found at {} -- Neo4j will remain empty", h2File);
            return;
        }

        log.info("Neo4j is empty -- bootstrapping from H2 cache at {}", cachePath);

        try (AnalysisCache cache = new AnalysisCache(cachePath)) {
            List<CodeNode> nodes = cache.loadAllNodes();
            List<CodeEdge> edges = cache.loadAllEdges();

            if (nodes.isEmpty()) {
                log.info("H2 cache is empty -- nothing to bootstrap");
                return;
            }

            // Build node map for resolving edge targets
            Map<String, CodeNode> nodeMap = new HashMap<>(nodes.size());
            for (CodeNode node : nodes) {
                nodeMap.put(node.getId(), node);
            }

            // Attach edges to their source nodes for Spring Data Neo4j persistence.
            // SDN saves edges as relationships on the source node, so each edge
            // must be added to its source node's edges list with a resolved target.
            for (CodeEdge edge : edges) {
                String sourceId = edge.getSourceId();
                CodeNode sourceNode = nodeMap.get(sourceId);
                if (sourceNode == null) continue;

                // Resolve the target node from our loaded nodes
                CodeNode targetNode = edge.getTarget();
                if (targetNode != null) {
                    CodeNode resolvedTarget = nodeMap.get(targetNode.getId());
                    if (resolvedTarget != null) {
                        edge.setTarget(resolvedTarget);
                    }
                }

                sourceNode.getEdges().add(edge);
            }

            // Save all nodes (with their attached edges) to Neo4j using bulk Cypher
            // (not SDN saveAll which recursively hydrates @Relationship edges → OOM)
            graphStore.bulkSave(nodes);

            log.info("Bootstrapped Neo4j with {} nodes and {} edges from H2 cache",
                    nodes.size(), edges.size());
        } catch (Exception e) {
            log.warn("Failed to bootstrap Neo4j from H2 cache -- graph traversal queries " +
                    "may not work. Run 'code-iq enrich' to populate Neo4j.", e);
        }
    }
}
