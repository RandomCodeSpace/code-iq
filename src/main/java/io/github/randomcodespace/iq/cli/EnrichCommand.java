package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.analyzer.GraphBuilder;
import io.github.randomcodespace.iq.analyzer.LayerClassifier;
import io.github.randomcodespace.iq.analyzer.linker.Linker;
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Load indexed data from H2 into Neo4j, run linkers and classifiers.
 * <p>
 * This command reads the H2 index produced by {@code index} and bulk-loads
 * all nodes and edges into a Neo4j embedded database. It then runs cross-file
 * linkers (TopicLinker, EntityLinker, ModuleContainmentLinker) and the
 * LayerClassifier to enrich the graph with inferred relationships and
 * layer classifications.
 * <p>
 * Neo4j is started programmatically (not via Spring bean) to avoid starting
 * it during indexing.
 */
@Component
@Command(name = "enrich", mixinStandardHelpOptions = true,
        description = "Load indexed data into Neo4j, run linkers and classifiers")
public class EnrichCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(EnrichCommand.class);

    @Parameters(index = "0", defaultValue = ".", description = "Path to indexed codebase")
    private Path path;

    private final CodeIqConfig config;
    private final LayerClassifier layerClassifier;
    private final List<Linker> linkers;

    public EnrichCommand(CodeIqConfig config, LayerClassifier layerClassifier, List<Linker> linkers) {
        this.config = config;
        this.layerClassifier = layerClassifier;
        this.linkers = linkers;
    }

    @Override
    public Integer call() {
        Instant start = Instant.now();
        Path root = path.toAbsolutePath().normalize();
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);

        // 1. Open H2 file
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        if (!Files.exists(cachePath.getParent())) {
            CliOutput.error("No index found at " + cachePath.getParent());
            CliOutput.info("  Run 'code-iq index " + root + "' first.");
            return 1;
        }

        CliOutput.step("\uD83D\uDCE6", "Loading index from H2...");
        AnalysisCache cache;
        try {
            cache = new AnalysisCache(cachePath);
        } catch (Exception e) {
            CliOutput.error("Failed to open H2 index: " + e.getMessage());
            return 1;
        }

        try {
            return enrichFromCache(cache, root, nf, start);
        } finally {
            cache.close();
        }
    }

    private int enrichFromCache(AnalysisCache cache, Path root, NumberFormat nf, Instant start) {
        // Load all nodes and edges from H2
        List<CodeNode> allNodes = cache.loadAllNodes();
        List<CodeEdge> allEdges = cache.loadAllEdges();

        if (allNodes.isEmpty()) {
            CliOutput.error("No indexed data found in H2. Run 'code-iq index' first.");
            return 1;
        }

        CliOutput.info("  Loaded " + nf.format(allNodes.size()) + " nodes, "
                + nf.format(allEdges.size()) + " edges from H2");

        // 2. Run linkers (these work on in-memory node/edge lists)
        CliOutput.step("\uD83D\uDD17", "Running cross-file linkers...");
        var builder = new GraphBuilder();
        for (CodeNode node : allNodes) {
            builder.addNodes(List.of(node));
        }
        builder.addEdges(allEdges);
        builder.runLinkers(linkers);

        // Flush and collect valid edges
        GraphBuilder.FlushResult flushed = builder.flush();
        List<CodeEdge> recoveredEdges = builder.flushDeferred();

        List<CodeNode> enrichedNodes = builder.getNodes();
        List<CodeEdge> enrichedEdges = builder.getEdges();

        int linkerNodeDelta = enrichedNodes.size() - allNodes.size();
        int linkerEdgeDelta = enrichedEdges.size() - allEdges.size();
        if (linkerNodeDelta > 0 || linkerEdgeDelta > 0) {
            CliOutput.info("  Linkers added " + nf.format(linkerNodeDelta) + " nodes, "
                    + nf.format(linkerEdgeDelta) + " edges");
        }

        // 3. Classify layers
        CliOutput.step("\uD83C\uDFF7\uFE0F", "Classifying layers...");
        layerClassifier.classify(enrichedNodes);

        // 3b. Detect services
        CliOutput.step("\uD83C\uDFD7\uFE0F", "Detecting service boundaries...");
        var serviceDetector = new io.github.randomcodespace.iq.analyzer.ServiceDetector();
        String projectName = root.getFileName().toString();
        var serviceResult = serviceDetector.detect(enrichedNodes, enrichedEdges, projectName);
        if (!serviceResult.serviceNodes().isEmpty()) {
            // Add service nodes and edges to the builder
            builder.addNodes(serviceResult.serviceNodes());
            builder.addEdges(serviceResult.serviceEdges());
            enrichedNodes = builder.getNodes();
            enrichedEdges = builder.getEdges();
            CliOutput.info("  Detected " + serviceResult.serviceNodes().size() + " service(s)");
        }

        // 4. Start Neo4j Embedded and bulk-load
        Path neo4jPath = root.resolve(config.getCacheDir()).resolve("../.osscodeiq/graph.db")
                .normalize();
        // Use the configured graph path
        String graphPathStr = config.getRootPath() != null
                ? root.resolve(".osscodeiq/graph.db").toString()
                : ".osscodeiq/graph.db";
        Path graphPath = root.resolve(".osscodeiq/graph.db");

        CliOutput.step("\uD83D\uDCBE", "Bulk-loading into Neo4j at " + graphPath + "...");

        DatabaseManagementService dbms = null;
        try {
            Files.createDirectories(graphPath);
            dbms = new DatabaseManagementServiceBuilder(graphPath).build();
            GraphDatabaseService db = dbms.database("neo4j");

            // Clear existing data
            try (Transaction tx = db.beginTx()) {
                tx.execute("MATCH (n) DETACH DELETE n");
                tx.commit();
            }

            // Bulk-load nodes in batches of 1000
            int nodeBatchSize = 1000;
            int nodesLoaded = 0;
            for (int i = 0; i < enrichedNodes.size(); i += nodeBatchSize) {
                int end = Math.min(i + nodeBatchSize, enrichedNodes.size());
                try (Transaction tx = db.beginTx()) {
                    for (int j = i; j < end; j++) {
                        CodeNode node = enrichedNodes.get(j);
                        var neo4jNode = tx.createNode(org.neo4j.graphdb.Label.label("CodeNode"));
                        neo4jNode.setProperty("id", node.getId());
                        neo4jNode.setProperty("kind", node.getKind().getValue());
                        neo4jNode.setProperty("label", node.getLabel());
                        if (node.getFqn() != null) neo4jNode.setProperty("fqn", node.getFqn());
                        if (node.getModule() != null) neo4jNode.setProperty("module", node.getModule());
                        if (node.getFilePath() != null) neo4jNode.setProperty("filePath", node.getFilePath());
                        if (node.getLineStart() != null) neo4jNode.setProperty("lineStart", node.getLineStart());
                        if (node.getLineEnd() != null) neo4jNode.setProperty("lineEnd", node.getLineEnd());
                        if (node.getLayer() != null) neo4jNode.setProperty("layer", node.getLayer());
                        nodesLoaded++;
                    }
                    tx.commit();
                }
            }
            CliOutput.info("  Loaded " + nf.format(nodesLoaded) + " nodes into Neo4j");

            // Create index on id for edge resolution
            try (Transaction tx = db.beginTx()) {
                tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.id)");
                tx.commit();
            }

            // Bulk-load edges
            // Use validated edges from flush
            List<CodeEdge> validEdges = flushed.edges();
            if (!recoveredEdges.isEmpty()) {
                var combined = new java.util.ArrayList<>(validEdges);
                combined.addAll(recoveredEdges);
                validEdges = combined;
            }

            int edgeBatchSize = 1000;
            int edgesLoaded = 0;
            for (int i = 0; i < validEdges.size(); i += edgeBatchSize) {
                int end = Math.min(i + edgeBatchSize, validEdges.size());
                try (Transaction tx = db.beginTx()) {
                    for (int j = i; j < end; j++) {
                        CodeEdge edge = validEdges.get(j);
                        String sourceId = edge.getSourceId();
                        String targetId = edge.getTarget() != null ? edge.getTarget().getId() : null;
                        if (sourceId == null || targetId == null) continue;

                        // Validate edge kind comes from EdgeKind enum
                        String edgeKindValue = edge.getKind().getValue();
                        try {
                            EdgeKind.fromValue(edgeKindValue);
                        } catch (IllegalArgumentException ex) {
                            log.warn("Skipping edge with unknown kind: {}", edgeKindValue);
                            continue;
                        }
                        var result = tx.execute(
                                "MATCH (s:CodeNode {id: $sourceId}), (t:CodeNode {id: $targetId}) "
                                        + "CREATE (s)-[r:" + sanitizeRelType(edgeKindValue)
                                        + " {id: $edgeId}]->(t)",
                                java.util.Map.of(
                                        "sourceId", sourceId,
                                        "targetId", targetId,
                                        "edgeId", edge.getId() != null ? edge.getId() : ""
                                ));
                        result.close();
                        edgesLoaded++;
                    }
                    tx.commit();
                }
            }
            CliOutput.info("  Loaded " + nf.format(edgesLoaded) + " edges into Neo4j");

            // Create additional indexes for fast queries
            try (Transaction tx = db.beginTx()) {
                tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.kind)");
                tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.layer)");
                tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.module)");
                tx.execute("CREATE INDEX IF NOT EXISTS FOR (n:CodeNode) ON (n.filePath)");
                tx.commit();
            }
            CliOutput.info("  Created Neo4j indexes");

            Duration elapsed = Duration.between(start, Instant.now());
            long secs = elapsed.toSeconds();
            String timeStr = secs > 0 ? secs + "s" : elapsed.toMillis() + "ms";

            System.out.println();
            CliOutput.success("\u2705 Enrichment complete -- "
                    + nf.format(nodesLoaded) + " nodes, "
                    + nf.format(edgesLoaded) + " edges in " + timeStr);
            System.out.println();
            CliOutput.info("  Graph:   " + graphPath);
            CliOutput.info("  Time:    " + timeStr);
            System.out.println();
            CliOutput.info("  Next step: code-iq serve " + path);

            return 0;

        } catch (Exception e) {
            log.error("Enrichment failed", e);
            CliOutput.error("Enrichment failed: " + e.getMessage());
            return 1;
        } finally {
            if (dbms != null) {
                try {
                    dbms.shutdown();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Sanitize relationship type for Neo4j Cypher.
     * Neo4j relationship types must be alphanumeric + underscore.
     */
    private static String sanitizeRelType(String kind) {
        return kind.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase();
    }
}
